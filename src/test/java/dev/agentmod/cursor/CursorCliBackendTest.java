package dev.agentmod.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import dev.agentmod.AgentModConfig;
import dev.agentmod.core.AgentStatus;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.core.Conversation;
import dev.agentmod.core.NewAgentRequest;
import dev.agentmod.core.PendingAction;
import dev.agentmod.core.StartResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Drives the backend against {@link FakeAcpAgent}, a stand-in for {@code cursor-agent acp}. */
@DisabledOnOs(OS.WINDOWS)
class CursorCliBackendTest {
	@TempDir
	Path dir;

	private Path fakeAgent(Path sessions, boolean signedOut) throws IOException {
		String java = ProcessHandle.current().info().command().orElseThrow();
		Path script = dir.resolve(signedOut ? "cursor-agent-signed-out" : "cursor-agent");
		Files.writeString(script, "#!/bin/sh\n"
				+ "export FAKE_ACP_SESSIONS='" + sessions + "'\n"
				+ (signedOut ? "export FAKE_ACP_AUTH=required\n" : "")
				+ "exec '" + java + "' -cp '" + System.getProperty("java.class.path") + "' " + FakeAcpAgent.class.getName() + " \"$@\"\n");
		assertTrue(script.toFile().setExecutable(true));
		return script;
	}

	private static <T> T await(Callable<T> probe) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			T value = probe.call();
			if (value != null) {
				return value;
			}
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Timed out");
			}
			Thread.sleep(50);
		}
	}

	private static AgentSummary find(List<AgentSummary> agents, String id) {
		return agents.stream().filter(agent -> agent.id().equals(id)).findFirst().orElse(null);
	}

	private static PendingAction pendingAction(CursorCliBackend backend, String id) throws Exception {
		return await(() -> {
			Conversation conversation = backend.loadConversation(id);
			return conversation.actions().isEmpty() ? null : conversation.actions().getFirst();
		});
	}

	private static void awaitStatus(CursorCliBackend backend, String id, AgentStatus status) throws Exception {
		await(() -> {
			AgentSummary agent = find(backend.listAgents(), id);
			return agent != null && agent.status() == status ? agent : null;
		});
	}

	@Test
	void startsAgentInProjectAndRunsTurnsWithPermissions() throws Exception {
		Path sessions = Files.createDirectories(dir.resolve("acp-sessions"));
		Path project = Files.createDirectories(dir.resolve("my-project"));
		AgentModConfig.Cursor config = new AgentModConfig.Cursor();
		config.cliBinaryPath = fakeAgent(sessions, false).toString();
		String id;
		try (CursorCliBackend backend = new CursorCliBackend(config, "test", new AcpSessionStore(sessions))) {
			assertTrue(backend.canStartAgents());
			StartResult started = backend.startAgent(new NewAgentRequest(project.toString(), "list the files", "plan"));
			assertTrue(started.ok(), started.message());
			id = started.agent().id();
			assertEquals(AgentStatus.RUNNING, started.agent().status());
			assertEquals("my-project", started.agent().project());
			assertEquals("list the files", started.agent().title());

			PendingAction action = pendingAction(backend, id);
			assertEquals("Cursor wants to run a command:\nls -la\nList the files", action.prompt());
			assertEquals(List.of("Allow", "Always allow", "Reject", "Stop"), action.options().stream().map(PendingAction.Option::label).toList());
			assertTrue(action.options().getFirst().primary());
			assertEquals(AgentStatus.WAITING, find(backend.listAgents(), id).status());
			assertTrue(backend.resolveAction(id, action.id(), "allow-once").ok());
			assertFalse(backend.resolveAction(id, action.id(), "allow-once").ok(), "answering twice fails");

			awaitStatus(backend, id, AgentStatus.IDLE);
			AgentSummary finished = find(backend.listAgents(), id);
			assertEquals("Fake title", finished.title());
			assertEquals("Done.", finished.subtitle());
			List<ChatMessage> messages = backend.loadConversation(id).messages();
			assertEquals(List.of(
					ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.TOOL, ChatMessage.Role.ASSISTANT
			), messages.stream().map(ChatMessage::role).toList());
			assertEquals("list the files", messages.get(0).text());
			assertEquals("Working in " + project.toRealPath() + " on: list the files", messages.get(1).text(), "runs in the project folder");
			assertEquals("Shell  ls -la", messages.get(2).text());
			assertTrue(Files.readString(sessions.resolve(id).resolve("store.db")).contains("\"plan\""), "mode was applied");

			assertTrue(backend.sendReply(id, "again").ok());
			PendingAction second = pendingAction(backend, id);
			assertTrue(backend.sendReply(id, "queued one").message().startsWith("Queued"));
			assertTrue(backend.resolveAction(id, second.id(), "__agentmod_stop__").ok());
			await(() -> backend.loadConversation(id).messages().stream().anyMatch(m -> m.text().equals("Stopped")) ? true : null);
			assertTrue(backend.loadConversation(id).messages().stream().noneMatch(m -> m.text().equals("queued one")), "stopping drops the queue");
		}

		try (CursorCliBackend reopened = new CursorCliBackend(config, "test", new AcpSessionStore(sessions))) {
			AgentSummary listed = find(reopened.listAgents(), id);
			assertEquals(AgentStatus.IDLE, listed.status());
			assertEquals("my-project", listed.project());
			List<ChatMessage> replayed = reopened.loadConversation(id).messages();
			assertEquals(List.of("list the files", "again"), replayed.stream().filter(m -> m.role() == ChatMessage.Role.USER).map(ChatMessage::text).toList());
			assertEquals(4, replayed.size());

			assertTrue(reopened.sendReply(id, "continue").ok(), "replies continue a saved session");
			PendingAction action = pendingAction(reopened, id);
			assertTrue(reopened.resolveAction(id, action.id(), "reject-once").ok());
			awaitStatus(reopened, id, AgentStatus.IDLE);
			assertTrue(reopened.loadConversation(id).messages().stream().anyMatch(m -> m.text().equals("Shell  ls -la  (failed)")));
		}
	}

	@Test
	void reportsWhenTheCliNeedsSignIn() throws Exception {
		Path sessions = Files.createDirectories(dir.resolve("acp-sessions"));
		Path project = Files.createDirectories(dir.resolve("p"));
		AgentModConfig.Cursor config = new AgentModConfig.Cursor();
		config.cliBinaryPath = fakeAgent(sessions, true).toString();
		try (CursorCliBackend backend = new CursorCliBackend(config, "test", new AcpSessionStore(sessions))) {
			await(() -> backend.needsSignIn() ? true : null);
			StartResult result = backend.startAgent(new NewAgentRequest(project.toString(), "hi", null));
			assertFalse(result.ok());
			assertTrue(result.message().startsWith("Sign in to Cursor first"), result.message());
			backend.listAgents();
			assertFalse(backend.health().ok());
			assertTrue(backend.signIn().ok());
			assertFalse(backend.needsSignIn());
		}
	}
}
