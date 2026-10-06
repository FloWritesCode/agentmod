package dev.agentmod.cursor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentmod.AgentModConfig;
import dev.agentmod.core.AgentBackend;
import dev.agentmod.core.AgentMode;
import dev.agentmod.core.AgentSource;
import dev.agentmod.core.AgentStatus;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.core.BackendHealth;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.core.Conversation;
import dev.agentmod.core.NewAgentRequest;
import dev.agentmod.core.PendingAction;
import dev.agentmod.core.ProjectChoice;
import dev.agentmod.core.ReplyResult;
import dev.agentmod.core.StartResult;
import dev.agentmod.util.Json;
import dev.agentmod.util.ShellEnv;
import dev.agentmod.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cursor agents run by Cursor's CLI agent over the Agent Client Protocol ({@code cursor-agent acp}).
 *
 * <p>This is how new Cursor agents start from Minecraft: the Cursor app can't be told to start a chat, but its
 * CLI agent can, with the same account, models, rules and permission settings. Each session runs in its own
 * process whose working directory is the project; processes start on demand and stop when idle. Sessions the
 * CLI saved earlier (from Minecraft or another ACP client) are listed and can be continued.
 */
public final class CursorCliBackend implements AgentBackend {
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");
	private static final long IDLE_STOP_MS = Duration.ofMinutes(3).toMillis();
	private static final int MAX_PROCESSES = 4;
	private static final long SIGN_IN_RECHECK_MS = 15_000;
	private static final String STOP_OPTION = "__agentmod_stop__";
	private static final String NOT_INSTALLED = "Cursor's CLI agent not found (install it with: curl https://cursor.com/install -fsS | bash, or set cursor.cliBinaryPath)";
	private static final String SIGN_IN_FIRST = "Sign in to Cursor first: + New → Sign in";
	private static final String REPLY_HINT = "Replies run Cursor's agent from Minecraft";
	private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
	private static final List<AgentMode> MODES = List.of(
			new AgentMode("agent", "Agent", "Edits files and runs commands"),
			new AgentMode("plan", "Plan", "Researches and writes a plan before changing anything"),
			new AgentMode("ask", "Ask", "Answers questions without changing files"));

	private final AgentModConfig.Cursor config;
	private final String clientVersion;
	private final String binary;
	private final AcpSessionStore store;
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final AtomicLong permissionIds = new AtomicLong();
	private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("AgentMod-cursor-cli").unstarted(r));
	private volatile Boolean signedIn;
	private volatile long signInCheckedAt;
	private volatile BackendHealth health;
	private volatile Runnable changeListener = () -> {
	};

	private record Permission(String id, PendingAction action, CompletableFuture<JsonElement> answer) {
	}

	private final class Session implements AcpConnection.Handler {
		final Path cwd;
		/** Guards the process lifecycle (connect / stop). Never held by the ACP reader thread. */
		final Object processLock = new Object();
		/** Guards {@link #running} and {@link #queued} transitions. */
		final Object turnLock = new Object();
		final Deque<String> queued = new ConcurrentLinkedDeque<>();
		final Map<String, Permission> permissions = Collections.synchronizedMap(new LinkedHashMap<>());
		volatile String id;
		volatile AcpConnection connection;
		volatile AcpTranscript transcript = new AcpTranscript();
		/** Receives replayed history while {@code session/load} runs. */
		volatile AcpTranscript replayTarget;
		volatile boolean historyLoaded;
		/** store.db mtime the transcript reflects, to notice when another client continued the chat. */
		volatile long syncedAt;
		volatile boolean running;
		volatile String error;
		volatile String firstPrompt;
		/** Last live update or prompt (shown as the agent's activity). */
		volatile long activityAt;
		/** Last use of the process, for idle shutdown. */
		volatile long touchedAt = System.currentTimeMillis();
		volatile long changedAt;

		Session(String id, Path cwd) {
			this.id = id;
			this.cwd = cwd;
		}

		@Override
		public void onNotification(String method, JsonObject params) {
			if (!"session/update".equals(method)) {
				return;
			}
			JsonObject update = Json.obj(params, "update");
			if (update == null) {
				return;
			}
			AcpTranscript replay = replayTarget;
			if (replay != null) {
				replay.apply(update, 0);
				return;
			}
			long now = System.currentTimeMillis();
			transcript.apply(update, now);
			String kind = Json.str(update, "sessionUpdate");
			if ("available_commands_update".equals(kind)) {
				return;
			}
			activityAt = now;
			touchedAt = now;
			boolean structural = "tool_call".equals(kind) || "session_info_update".equals(kind) || "plan".equals(kind);
			if (structural || now - changedAt > 1000) {
				changedAt = now;
				changed();
			}
		}

		@Override
		public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
			if (!"session/request_permission".equals(method)) {
				return null;
			}
			Permission permission = toPermission(params);
			permissions.put(permission.id(), permission);
			touchedAt = System.currentTimeMillis();
			changed();
			return permission.answer();
		}

		@Override
		public void onExit(String reason) {
			synchronized (processLock) {
				connection = null;
			}
			cancelPermissions(this);
			LOG.info("[AgentMod] Cursor's agent for {} stopped: {}", id, reason);
			changed();
		}
	}

	public CursorCliBackend(AgentModConfig.Cursor config, String clientVersion) {
		this(config, clientVersion, new AcpSessionStore(AcpSessionStore.defaultRoot()));
	}

	CursorCliBackend(AgentModConfig.Cursor config, String clientVersion, AcpSessionStore store) {
		this.config = config;
		this.clientVersion = clientVersion;
		this.binary = resolveBinary(config.cliBinaryPath);
		this.store = store;
		this.health = binary == null
				? new BackendHealth(AgentSource.CURSOR_CLI, false, NOT_INSTALLED)
				: new BackendHealth(AgentSource.CURSOR_CLI, true, "Starting…");
		if (binary != null) {
			housekeeping.execute(this::checkSignIn);
			housekeeping.scheduleWithFixedDelay(this::stopIdleProcesses, 30, 30, TimeUnit.SECONDS);
		}
	}

	static String resolveBinary(String override) {
		List<Path> candidates = new ArrayList<>();
		if (override != null && !override.isBlank()) {
			candidates.add(Path.of(override));
		}
		String home = System.getProperty("user.home");
		candidates.add(Path.of(home, ".local", "bin", "cursor-agent"));
		Path onPath = ShellEnv.which("cursor-agent");
		if (onPath != null) {
			candidates.add(onPath);
		}
		Path globalStorage = CursorStateDb.defaultDbPath().getParent();
		candidates.add(globalStorage.resolve(Path.of("anysphere.cursor-agent-worker", "agent-cli", ".local", "bin", "cursor-agent")));
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return candidate.toString();
			}
		}
		return null;
	}

	@Override
	public AgentSource source() {
		return AgentSource.CURSOR_CLI;
	}

	@Override
	public BackendHealth health() {
		return health;
	}

	@Override
	public void setChangeListener(Runnable listener) {
		this.changeListener = listener == null ? () -> {
		} : listener;
	}

	private void changed() {
		changeListener.run();
	}

	@Override
	public boolean canStartAgents() {
		return binary != null;
	}

	@Override
	public String startLabel() {
		return "Cursor";
	}

	@Override
	public String startHint() {
		return "Runs Cursor's agent from Minecraft with your Cursor account, models, rules and permission settings. "
				+ "It's listed as Cursor CLI here; Cursor's sidebar doesn't show it.";
	}

	@Override
	public List<AgentMode> agentModes() {
		return MODES;
	}

	@Override
	public boolean needsSignIn() {
		boolean signedOut = Boolean.FALSE.equals(signedIn);
		if (signedOut && System.currentTimeMillis() - signInCheckedAt > SIGN_IN_RECHECK_MS) {
			signInCheckedAt = System.currentTimeMillis();
			housekeeping.execute(this::checkSignIn);
		}
		return signedOut;
	}

	@Override
	public List<AgentSummary> listAgents() throws Exception {
		if (binary == null) {
			return List.of();
		}
		List<AgentSummary> agents = new ArrayList<>();
		Set<String> listed = new HashSet<>();
		for (AcpSessionStore.Entry entry : store.list(Math.max(1, config.maxAgents))) {
			listed.add(entry.id());
			agents.add(summarize(sessions.get(entry.id()), entry));
		}
		int running = 0;
		for (Session session : sessions.values()) {
			if (session.running) {
				running++;
			}
			if (session.id != null && !listed.contains(session.id) && (session.running || session.connection != null)) {
				agents.add(summarize(session, null));
			}
		}
		updateHealth(running);
		return agents;
	}

	private void updateHealth(int running) {
		if (Boolean.FALSE.equals(signedIn)) {
			health = new BackendHealth(AgentSource.CURSOR_CLI, false, "Not signed in (+ New → Sign in)");
		} else {
			health = new BackendHealth(AgentSource.CURSOR_CLI, true, running > 0 ? running + " running from Minecraft" : "Ready to start agents");
		}
	}

	private AgentSummary summarize(Session session, AcpSessionStore.Entry entry) {
		String id = session != null ? session.id : entry.id();
		Path cwd = session != null ? session.cwd : entry.cwd();
		String title = session != null ? session.transcript.title() : null;
		if (title == null && entry != null) {
			title = entry.title();
		}
		if (title == null && session != null) {
			title = session.firstPrompt != null ? session.firstPrompt : session.transcript.firstUserText();
		}
		if (title == null || title.isBlank()) {
			title = "New agent";
		}
		long updatedAt = Math.max(entry != null ? entry.updatedAt() : 0, session != null ? session.activityAt : 0);
		AgentStatus status = AgentStatus.IDLE;
		String subtitle = "";
		if (session != null) {
			if (!session.permissions.isEmpty()) {
				status = AgentStatus.WAITING;
			} else if (session.running) {
				status = AgentStatus.RUNNING;
			} else if (session.error != null) {
				status = AgentStatus.ERROR;
			}
			String activity = status == AgentStatus.ERROR ? session.error : session.transcript.latestActivity();
			subtitle = activity != null ? activity : "";
		}
		String hint = Boolean.FALSE.equals(signedIn) ? SIGN_IN_FIRST : REPLY_HINT;
		return new AgentSummary(AgentSource.CURSOR_CLI, id, Texts.truncate(Texts.oneLine(Texts.firstLine(title)), 90),
				Texts.truncate(Texts.oneLine(subtitle), 200), Texts.baseName(cwd.toString()), status, updatedAt, false, true, hint);
	}

	@Override
	public Conversation loadConversation(String id) throws Exception {
		Session session = session(id);
		if (session.historyLoaded && session.connection == null && !session.running && store.updatedAt(id) > session.syncedAt + 2000) {
			session.historyLoaded = false;
		}
		if (!session.historyLoaded) {
			if (binary == null) {
				return notice(NOT_INSTALLED);
			}
			try {
				connect(session);
			} catch (IOException e) {
				if (Boolean.FALSE.equals(signedIn)) {
					return notice("Sign in to Cursor to load this chat: press + New, then Sign in.");
				}
				throw e;
			}
		}
		return new Conversation(session.transcript.messages(), actions(session), false);
	}

	private static Conversation notice(String text) {
		return new Conversation(List.of(ChatMessage.notice(text, 0)), List.of(), false);
	}

	private List<PendingAction> actions(Session session) {
		synchronized (session.permissions) {
			List<PendingAction> actions = new ArrayList<>();
			for (Permission permission : session.permissions.values()) {
				actions.add(permission.action());
			}
			return actions;
		}
	}

	@Override
	public ReplyResult sendReply(String id, String text) throws Exception {
		if (binary == null) {
			return ReplyResult.failed(NOT_INSTALLED);
		}
		String message = text == null ? "" : text.strip();
		if (message.isEmpty()) {
			return ReplyResult.failed("Nothing to send");
		}
		Session session = session(id);
		AcpConnection connection;
		try {
			connection = connect(session);
		} catch (IOException e) {
			return ReplyResult.failed(e.getMessage());
		}
		synchronized (session.turnLock) {
			if (session.running) {
				session.queued.add(message);
				changed();
				return ReplyResult.ok("Queued: Cursor gets it when the current turn ends");
			}
			prompt(session, connection, message);
		}
		return ReplyResult.ok("Sent. Cursor is working on it from Minecraft");
	}

	@Override
	public StartResult startAgent(NewAgentRequest request) throws Exception {
		if (binary == null) {
			return StartResult.failed(NOT_INSTALLED);
		}
		Path cwd = Path.of(request.projectPath());
		if (!Files.isDirectory(cwd)) {
			return StartResult.failed("Folder not found: " + cwd);
		}
		String prompt = request.prompt() == null ? "" : request.prompt().strip();
		if (prompt.isEmpty()) {
			return StartResult.failed("Write a first message for the agent");
		}
		makeRoom();
		Session session = new Session(null, cwd);
		AcpConnection connection;
		synchronized (session.processLock) {
			connection = open(session);
			JsonObject created;
			try {
				created = connection.call("session/new", Json.object("cwd", cwd.toString(), "mcpServers", new JsonArray()), Duration.ofSeconds(90));
			} catch (IOException e) {
				connection.close();
				return StartResult.failed(signInAware(e).getMessage());
			}
			String id = Json.str(created, "sessionId");
			if (id == null) {
				connection.close();
				return StartResult.failed("Cursor's agent didn't create a session");
			}
			markSignedIn(true);
			session.id = id;
			session.connection = connection;
			session.historyLoaded = true;
			session.firstPrompt = Texts.firstLine(prompt);
			sessions.put(id, session);
			String mode = request.mode();
			if (mode != null && !"agent".equals(mode)) {
				try {
					connection.call("session/set_mode", Json.object("sessionId", id, "modeId", mode), Duration.ofSeconds(20));
				} catch (IOException e) {
					session.transcript.addNotice("Couldn't switch to " + mode + " mode: " + e.getMessage(), System.currentTimeMillis());
				}
			}
		}
		synchronized (session.turnLock) {
			prompt(session, connection, prompt);
		}
		return StartResult.ok("Cursor is working on it", summarize(session, null));
	}

	@Override
	public ReplyResult resolveAction(String id, String actionId, String optionId) {
		Session session = sessions.get(id);
		Permission permission = session != null ? session.permissions.remove(actionId) : null;
		if (permission == null) {
			return ReplyResult.failed("That request was already answered");
		}
		if (STOP_OPTION.equals(optionId)) {
			session.queued.clear();
			permission.answer().complete(Json.object("outcome", Json.object("outcome", "cancelled")));
			AcpConnection connection = session.connection;
			if (connection != null) {
				connection.notify("session/cancel", Json.object("sessionId", session.id));
			}
			changed();
			return ReplyResult.ok("Stopping");
		}
		permission.answer().complete(Json.object("outcome", Json.object("outcome", "selected", "optionId", optionId)));
		changed();
		return ReplyResult.ok("Answered");
	}

	@Override
	public List<ProjectChoice> recentProjects() throws Exception {
		List<ProjectChoice> projects = new ArrayList<>();
		for (AcpSessionStore.Entry entry : store.list(200)) {
			projects.add(new ProjectChoice(Texts.baseName(entry.cwd().toString()), entry.cwd().toString(), entry.updatedAt()));
		}
		for (Session session : sessions.values()) {
			projects.add(new ProjectChoice(Texts.baseName(session.cwd.toString()), session.cwd.toString(), session.activityAt));
		}
		return projects;
	}

	@Override
	public ReplyResult signIn() throws Exception {
		if (binary == null) {
			return ReplyResult.failed(NOT_INSTALLED);
		}
		AcpConnection.Handler ignore = new AcpConnection.Handler() {
			@Override
			public void onNotification(String method, JsonObject params) {
			}

			@Override
			public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
				return null;
			}

			@Override
			public void onExit(String reason) {
			}
		};
		try (AcpConnection connection = AcpConnection.open(command(), Path.of(System.getProperty("user.home")), ShellEnv.get(), clientVersion, ignore)) {
			connection.call("authenticate", Json.object("methodId", "cursor_login"), Duration.ofMinutes(10));
		} catch (IOException e) {
			return ReplyResult.failed("Sign-in didn't finish: " + e.getMessage());
		}
		markSignedIn(true);
		return ReplyResult.ok("Signed in to Cursor");
	}

	private List<String> command() {
		return List.of(binary, "acp");
	}

	private AcpConnection open(Session session) throws IOException {
		return AcpConnection.open(command(), session.cwd, ShellEnv.get(), clientVersion, session);
	}

	private Session session(String id) throws IOException {
		Session session = sessions.get(id);
		if (session != null) {
			return session;
		}
		AcpSessionStore.Entry entry = store.find(id);
		if (entry == null) {
			throw new IOException("This Cursor CLI chat no longer exists");
		}
		return sessions.computeIfAbsent(id, key -> new Session(key, entry.cwd()));
	}

	/** Returns the session's live process, starting one and replaying the saved history if needed. */
	private AcpConnection connect(Session session) throws IOException {
		AcpConnection live = session.connection;
		if (live != null && live.isAlive()) {
			session.touchedAt = System.currentTimeMillis();
			return live;
		}
		makeRoom();
		synchronized (session.processLock) {
			live = session.connection;
			if (live != null && live.isAlive()) {
				return live;
			}
			if (!Files.isDirectory(session.cwd)) {
				throw new IOException("The chat's folder is gone: " + session.cwd);
			}
			AcpConnection connection = open(session);
			AcpTranscript replay = new AcpTranscript();
			session.replayTarget = replay;
			try {
				connection.call("session/load", Json.object("sessionId", session.id, "cwd", session.cwd.toString(), "mcpServers", new JsonArray()), Duration.ofSeconds(120));
			} catch (IOException e) {
				connection.close();
				throw signInAware(e);
			} finally {
				session.replayTarget = null;
			}
			markSignedIn(true);
			session.transcript = replay;
			session.historyLoaded = true;
			session.syncedAt = store.updatedAt(session.id);
			session.touchedAt = System.currentTimeMillis();
			session.connection = connection;
			return connection;
		}
	}

	/** Called with {@code session.turnLock} held. */
	private void prompt(Session session, AcpConnection connection, String text) {
		long now = System.currentTimeMillis();
		session.running = true;
		session.error = null;
		session.activityAt = now;
		session.touchedAt = now;
		session.transcript.addUser(text, now);
		connection.request("session/prompt", Json.object(
						"sessionId", session.id,
						"prompt", Json.array(Json.object("type", "text", "text", text))))
				.whenComplete((result, failure) -> turnEnded(session, connection, result, failure));
		changed();
	}

	private void turnEnded(Session session, AcpConnection connection, JsonObject result, Throwable failure) {
		long now = System.currentTimeMillis();
		synchronized (session.turnLock) {
			session.running = false;
			session.activityAt = now;
			session.touchedAt = now;
			if (failure != null) {
				Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
				String message = cause instanceof AcpConnection.AcpException acp && acp.authRequired() ? SIGN_IN_FIRST : cause.getMessage();
				if (cause instanceof AcpConnection.AcpException acp && acp.authRequired()) {
					markSignedIn(false);
				}
				session.error = message;
				session.transcript.addNotice("Turn failed: " + message, now);
			} else {
				String stopReason = Json.str(result, "stopReason");
				switch (stopReason == null ? "" : stopReason) {
					case "cancelled" -> session.transcript.addNotice("Stopped", now);
					case "refusal" -> {
						session.error = "Cursor refused the request";
						session.transcript.addNotice(session.error, now);
					}
					case "max_tokens", "max_turn_requests" -> session.transcript.addNotice("Stopped: hit Cursor's limit for one turn", now);
					default -> {
					}
				}
				String printedError = session.transcript.trailingError();
				if (printedError != null) {
					session.error = printedError;
					String lower = printedError.toLowerCase();
					if (lower.contains("sign in") || lower.contains("log in") || lower.contains("login")) {
						markSignedIn(false);
					}
				}
			}
			cancelPermissions(session);
			String next = session.queued.poll();
			if (next != null) {
				if (connection.isAlive()) {
					prompt(session, connection, next);
				} else {
					session.queued.clear();
					session.transcript.addNotice("Queued messages weren't sent because Cursor's agent stopped", now);
				}
			}
		}
		changed();
	}

	private Permission toPermission(JsonObject params) {
		JsonObject toolCall = Json.obj(params, "toolCall");
		String title = cleanTitle(Json.str(toolCall, "title"));
		String kind = Json.str(toolCall, "kind");
		String prompt = switch (kind == null ? "" : kind) {
			case "execute" -> "Cursor wants to run a command:\n" + Texts.truncate(title, 600);
			case "edit", "read", "delete", "fetch", "search" -> "Cursor wants to " + lowerFirst(title);
			default -> "Cursor asks: " + title;
		};
		String detail = contentText(Json.arr(toolCall, "content"));
		if (detail != null && !detail.isBlank() && !detail.strip().equals(title)) {
			prompt += "\n" + Texts.truncate(detail.strip(), 400);
		}
		List<PendingAction.Option> options = new ArrayList<>();
		JsonArray offered = Json.arr(params, "options");
		boolean primaryTaken = false;
		if (offered != null) {
			for (JsonElement option : offered) {
				String optionId = Json.str(option, "optionId");
				if (optionId == null) {
					continue;
				}
				boolean primary = !primaryTaken && "allow_once".equals(Json.str(option, "kind"));
				primaryTaken |= primary;
				options.add(new PendingAction.Option(optionId, optionLabel(Json.str(option, "name"), optionId), primary));
			}
		}
		options.add(new PendingAction.Option(STOP_OPTION, "Stop", false));
		String id = "cursor-cli-permission-" + permissionIds.incrementAndGet();
		return new Permission(id, new PendingAction(id, prompt, options), new CompletableFuture<>());
	}

	private static String optionLabel(String name, String optionId) {
		if (name == null || name.isBlank()) {
			return optionId;
		}
		return switch (name) {
			case "Allow once" -> "Allow";
			case "Allow always" -> "Always allow";
			default -> name;
		};
	}

	private static String cleanTitle(String title) {
		if (title == null || title.isBlank()) {
			return "Permission needed";
		}
		return title.replace("\\`", "\u0000").replace("`", "").replace('\u0000', '`').strip();
	}

	private static String lowerFirst(String text) {
		return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}

	private static String contentText(JsonArray content) {
		if (content == null) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (JsonElement block : content) {
			String text = "content".equals(Json.str(block, "type")) ? Json.str(block, "content", "text") : null;
			if (text != null && !text.isBlank()) {
				if (!out.isEmpty()) {
					out.append('\n');
				}
				out.append(text.strip());
			}
		}
		return out.isEmpty() ? null : out.toString();
	}

	private void cancelPermissions(Session session) {
		List<Permission> pending;
		synchronized (session.permissions) {
			pending = new ArrayList<>(session.permissions.values());
			session.permissions.clear();
		}
		for (Permission permission : pending) {
			permission.answer().complete(Json.object("outcome", Json.object("outcome", "cancelled")));
		}
	}

	private IOException signInAware(IOException e) {
		if (e instanceof AcpConnection.AcpException acp && acp.authRequired()) {
			markSignedIn(false);
			return new IOException(SIGN_IN_FIRST, e);
		}
		return e;
	}

	private void markSignedIn(boolean value) {
		if (!Objects.equals(signedIn, value)) {
			signedIn = value;
			updateHealth(0);
			changed();
		}
	}

	/** Asks {@code cursor-agent status} whether the CLI has credentials. */
	private void checkSignIn() {
		signInCheckedAt = System.currentTimeMillis();
		try {
			ProcessBuilder builder = new ProcessBuilder(binary, "status")
					.directory(Path.of(System.getProperty("user.home")).toFile())
					.redirectErrorStream(true);
			builder.environment().putAll(ShellEnv.get());
			builder.environment().put("NO_COLOR", "1");
			Process process = builder.start();
			process.getOutputStream().close();
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return;
			}
			String output = ANSI.matcher(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).replaceAll("").toLowerCase();
			if (output.contains("not logged in") || output.contains("not authenticated")) {
				markSignedIn(false);
			} else if (output.contains("logged in")) {
				markSignedIn(true);
			}
		} catch (Exception e) {
			LOG.debug("[AgentMod] cursor-agent status failed: {}", e.toString());
		}
	}

	/** Stops idle processes until a new one fits under {@link #MAX_PROCESSES}. Never stops a busy agent. */
	private void makeRoom() {
		while (true) {
			int live = 0;
			Session oldestIdle = null;
			for (Session session : sessions.values()) {
				if (session.connection == null) {
					continue;
				}
				live++;
				if (isIdle(session) && (oldestIdle == null || session.touchedAt < oldestIdle.touchedAt)) {
					oldestIdle = session;
				}
			}
			if (live < MAX_PROCESSES || oldestIdle == null) {
				return;
			}
			stopProcess(oldestIdle);
		}
	}

	private static boolean isIdle(Session session) {
		return !session.running && session.permissions.isEmpty() && session.queued.isEmpty();
	}

	private void stopIdleProcesses() {
		long now = System.currentTimeMillis();
		for (Session session : sessions.values()) {
			if (session.connection != null && isIdle(session) && now - session.touchedAt > IDLE_STOP_MS) {
				stopProcess(session);
			}
		}
	}

	private void stopProcess(Session session) {
		AcpConnection connection;
		synchronized (session.processLock) {
			connection = session.connection;
			session.connection = null;
		}
		if (connection != null) {
			connection.close();
			session.syncedAt = store.updatedAt(session.id);
		}
	}

	@Override
	public void close() {
		housekeeping.shutdownNow();
		for (Session session : sessions.values()) {
			cancelPermissions(session);
			stopProcess(session);
		}
	}
}
