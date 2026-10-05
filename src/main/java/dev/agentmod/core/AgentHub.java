package dev.agentmod.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.agentmod.AgentModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls every backend off the game thread and publishes an immutable {@link Snapshot} the HUD and screens read.
 */
public final class AgentHub implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");

	public record Entry(AgentSummary agent, boolean unread, long activityAt) {
		public String key() {
			return agent.key();
		}
	}

	public record Snapshot(List<Entry> all, List<Entry> sidebar, List<BackendHealth> health, int active, int waiting, int unread, boolean loaded) {
		static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of(), 0, 0, 0, false);

		public Entry find(String key) {
			for (Entry entry : all) {
				if (entry.key().equals(key)) {
					return entry;
				}
			}
			return null;
		}
	}

	public enum NotificationKind {
		FINISHED,
		FAILED,
		NEEDS_INPUT
	}

	public record Notification(NotificationKind kind, AgentSummary agent) {
	}

	private final AgentModConfig config;
	private final List<AgentBackend> backends;
	private final SeenStore seen;
	private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("AgentMod-poll").unstarted(r));
	private final ExecutorService workers = Executors.newFixedThreadPool(3, r -> Thread.ofPlatform().daemon().name("AgentMod-worker").unstarted(r));
	private final Map<AgentSource, List<AgentSummary>> latest = new ConcurrentHashMap<>();
	private final Map<AgentSource, String> lastErrors = new ConcurrentHashMap<>();
	private final Map<String, AgentStatus> previousStatus = new HashMap<>();
	private final Map<String, Long> finishedAt = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Notification> notifications = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean refreshQueued = new AtomicBoolean();
	private final AtomicLong changes = new AtomicLong();
	private volatile Snapshot snapshot = Snapshot.EMPTY;
	private boolean firstPollDone;

	public AgentHub(AgentModConfig config, List<AgentBackend> backends, SeenStore seen) {
		this.config = config;
		this.backends = List.copyOf(backends);
		this.seen = seen;
		for (AgentBackend backend : this.backends) {
			backend.setChangeListener(this::onBackendChanged);
		}
	}

	public void start() {
		long interval = Math.max(750, config.pollIntervalMs);
		poller.scheduleWithFixedDelay(this::pollSafely, 0, interval, TimeUnit.MILLISECONDS);
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	/** Increments whenever a backend reports that something changed, so open screens know to reload. */
	public long changeCounter() {
		return changes.get();
	}

	public List<Notification> drainNotifications() {
		List<Notification> drained = new ArrayList<>();
		Notification next;
		while ((next = notifications.poll()) != null) {
			drained.add(next);
		}
		return drained;
	}

	public void requestRefresh() {
		if (refreshQueued.compareAndSet(false, true)) {
			poller.schedule(() -> {
				refreshQueued.set(false);
				pollSafely();
			}, 150, TimeUnit.MILLISECONDS);
		}
	}

	private void onBackendChanged() {
		changes.incrementAndGet();
		requestRefresh();
	}

	public void markSeen(String key) {
		Entry entry = snapshot.find(key);
		long ts = Math.max(System.currentTimeMillis(), entry != null ? entry.activityAt() : 0);
		seen.markSeen(key, ts);
		poller.execute(() -> rebuild(System.currentTimeMillis()));
	}

	public CompletableFuture<Conversation> loadConversation(String key) {
		return async(key, (backend, id) -> backend.loadConversation(id));
	}

	public CompletableFuture<ReplyResult> sendReply(String key, String text) {
		return this.<ReplyResult>async(key, (backend, id) -> backend.sendReply(id, text))
				.exceptionally(e -> ReplyResult.failed(rootMessage(e)))
				.whenComplete((r, e) -> requestRefresh());
	}

	public CompletableFuture<ReplyResult> resolveAction(String key, String actionId, String optionId) {
		return this.<ReplyResult>async(key, (backend, id) -> backend.resolveAction(id, actionId, optionId))
				.exceptionally(e -> ReplyResult.failed(rootMessage(e)))
				.whenComplete((r, e) -> requestRefresh());
	}

	private interface BackendCall<T> {
		T apply(AgentBackend backend, String id) throws Exception;
	}

	private <T> CompletableFuture<T> async(String key, BackendCall<T> call) {
		int colon = key.indexOf(':');
		AgentBackend backend = colon > 0 ? backendFor(key.substring(0, colon)) : null;
		if (backend == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown agent " + key));
		}
		String id = key.substring(colon + 1);
		return CompletableFuture.supplyAsync(() -> {
			try {
				return call.apply(backend, id);
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, workers);
	}

	private AgentBackend backendFor(String prefix) {
		for (AgentBackend backend : backends) {
			if (backend.source().keyPrefix().equals(prefix)) {
				return backend;
			}
		}
		return null;
	}

	public static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
	}

	private void pollSafely() {
		try {
			poll();
		} catch (Throwable t) {
			LOG.warn("[AgentMod] Poll failed", t);
		}
	}

	private void poll() {
		for (AgentBackend backend : backends) {
			try {
				latest.put(backend.source(), backend.listAgents());
				if (lastErrors.remove(backend.source()) != null) {
					LOG.info("[AgentMod] {} is reachable again", backend.source().displayName());
				}
			} catch (Exception e) {
				String message = rootMessage(e);
				if (!message.equals(lastErrors.put(backend.source(), message))) {
					LOG.warn("[AgentMod] {} unavailable: {}", backend.source().displayName(), message);
				}
			}
		}
		long now = System.currentTimeMillis();
		detectTransitions(now);
		rebuild(now);
		seen.saveIfDirty();
	}

	private void detectTransitions(long now) {
		for (List<AgentSummary> agents : latest.values()) {
			for (AgentSummary agent : agents) {
				AgentStatus before = previousStatus.put(agent.key(), agent.status());
				if (!firstPollDone || before == null || before == agent.status()) {
					continue;
				}
				if (before.isActive() && !agent.status().isActive()) {
					finishedAt.put(agent.key(), now);
					notifications.add(new Notification(agent.status() == AgentStatus.ERROR ? NotificationKind.FAILED : NotificationKind.FINISHED, agent));
				} else if (agent.status() == AgentStatus.WAITING) {
					notifications.add(new Notification(NotificationKind.NEEDS_INPUT, agent));
				}
			}
		}
		firstPollDone = true;
	}

	private synchronized void rebuild(long now) {
		long listCutoff = now - Duration.ofDays(Math.max(1, config.windowListDays)).toMillis();
		long unreadCutoff = now - Duration.ofHours(Math.max(1, config.unreadMaxAgeHours)).toMillis();
		long recentCutoff = now - Duration.ofMinutes(Math.max(0, config.recentMinutes)).toMillis();

		List<Entry> all = new ArrayList<>();
		int active = 0;
		int waiting = 0;
		int unread = 0;
		for (AgentBackend backend : backends) {
			for (AgentSummary agent : latest.getOrDefault(backend.source(), List.of())) {
				long activity = Math.max(agent.updatedAt(), finishedAt.getOrDefault(agent.key(), 0L));
				boolean isActive = agent.status().isActive();
				if (!isActive && activity < listCutoff) {
					continue;
				}
				boolean isUnread = !isActive && isUnread(agent, activity);
				all.add(new Entry(agent, isUnread, activity));
				if (isActive) {
					active++;
				}
				if (agent.status() == AgentStatus.WAITING) {
					waiting++;
				}
				if (isUnread && activity >= unreadCutoff) {
					unread++;
				}
			}
		}
		all.sort(Comparator.comparingInt(AgentHub::rank).thenComparing(Comparator.comparingLong(Entry::activityAt).reversed()));

		List<Entry> sidebar = new ArrayList<>();
		for (Entry entry : all) {
			boolean show = entry.agent().status().isActive()
					|| (entry.unread() && entry.activityAt() >= unreadCutoff)
					|| entry.activityAt() >= recentCutoff;
			if (show) {
				sidebar.add(entry);
			}
		}
		sidebar.sort(Comparator.comparingInt(AgentHub::sidebarRank).thenComparing(Comparator.comparingLong(Entry::activityAt).reversed()));

		List<BackendHealth> health = new ArrayList<>();
		for (AgentBackend backend : backends) {
			health.add(backend.health());
		}
		snapshot = new Snapshot(List.copyOf(all), List.copyOf(sidebar), List.copyOf(health), active, waiting, unread, true);
	}

	private boolean isUnread(AgentSummary agent, long activity) {
		if (activity > seen.readUpTo(agent.key())) {
			return true;
		}
		return agent.sourceUnread() && !seen.everOpened(agent.key());
	}

	private static int rank(Entry entry) {
		return entry.agent().status().isActive() ? 0 : 1;
	}

	private static int sidebarRank(Entry entry) {
		return switch (entry.agent().status()) {
			case WAITING -> 0;
			case RUNNING -> 1;
			default -> entry.unread() ? 2 : 3;
		};
	}

	@Override
	public void close() {
		poller.shutdownNow();
		workers.shutdownNow();
		for (AgentBackend backend : backends) {
			try {
				backend.close();
			} catch (Exception e) {
				LOG.debug("[AgentMod] Closing {} failed: {}", backend.source(), e.toString());
			}
		}
		seen.saveIfDirty();
	}
}
