package dev.agentmod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import dev.agentmod.AgentModConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentHubTest {
	@TempDir
	Path dir;

	private static final class FakeBackend implements AgentBackend {
		volatile List<AgentSummary> agents = List.of();

		@Override
		public AgentSource source() {
			return AgentSource.CODEX;
		}

		@Override
		public List<AgentSummary> listAgents() {
			return agents;
		}

		@Override
		public Conversation loadConversation(String id) {
			return Conversation.EMPTY;
		}

		@Override
		public ReplyResult sendReply(String id, String text) {
			return ReplyResult.ok("sent " + id);
		}

		@Override
		public BackendHealth health() {
			return new BackendHealth(AgentSource.CODEX, true, "ok");
		}

		@Override
		public void close() {
		}
	}

	private static AgentSummary agent(String id, AgentStatus status, long updatedAt) {
		return new AgentSummary(AgentSource.CODEX, id, "Agent " + id, "", "proj", status, updatedAt, false, true, "");
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Timed out");
			}
			Thread.sleep(20);
		}
	}

	@Test
	void finishedAgentBecomesUnreadAndNotifies() throws Exception {
		AgentModConfig config = new AgentModConfig();
		config.pollIntervalMs = 60_000;
		FakeBackend backend = new FakeBackend();
		long now = System.currentTimeMillis();
		backend.agents = List.of(agent("a", AgentStatus.RUNNING, now), agent("old", AgentStatus.IDLE, now - 3_600_000));
		try (AgentHub hub = new AgentHub(config, List.of(backend), new SeenStore(dir.resolve("seen.json")))) {
			hub.start();
			await(() -> hub.snapshot().loaded());

			AgentHub.Snapshot first = hub.snapshot();
			assertEquals(1, first.active());
			assertEquals(List.of("codex:a"), first.sidebar().stream().map(AgentHub.Entry::key).toList());
			assertFalse(first.find("codex:old").unread(), "history from before install counts as read");

			backend.agents = List.of(agent("a", AgentStatus.IDLE, System.currentTimeMillis() + 10), agent("old", AgentStatus.IDLE, now - 3_600_000));
			hub.requestRefresh();
			await(() -> hub.snapshot().active() == 0);

			AgentHub.Entry finished = hub.snapshot().find("codex:a");
			assertTrue(finished.unread());
			assertEquals(1, hub.snapshot().unread());
			List<AgentHub.Notification> notifications = new ArrayList<>(hub.drainNotifications());
			assertEquals(1, notifications.size());
			assertEquals(AgentHub.NotificationKind.FINISHED, notifications.getFirst().kind());

			hub.markSeen("codex:a");
			await(() -> !hub.snapshot().find("codex:a").unread());
			assertTrue(hub.snapshot().sidebar().stream().anyMatch(e -> e.key().equals("codex:a")), "recently finished stays visible");
			assertEquals("sent a", hub.sendReply("codex:a", "hi").get().message());
		}
		assertTrue(Files.exists(dir.resolve("seen.json")));
	}

	@Test
	void mergesProjectsAcrossApps() {
		List<ProjectChoice> cursor = List.of(
				new ProjectChoice("AgentMod", "/Users/me/Developer/AgentMod", 500),
				new ProjectChoice("", "/Users/me/Developer/Old/", 0));
		List<ProjectChoice> codex = List.of(
				new ProjectChoice("agentmod", "/Users/me/Developer/./AgentMod", 900),
				new ProjectChoice("Bien", "/Users/me/Developer/Bien", 700));
		assertEquals(List.of(
				new ProjectChoice("AgentMod", "/Users/me/Developer/AgentMod", 900),
				new ProjectChoice("Bien", "/Users/me/Developer/Bien", 700),
				new ProjectChoice("Old", "/Users/me/Developer/Old", 0)), AgentHub.mergeProjects(List.of(cursor, codex)));
	}
}
