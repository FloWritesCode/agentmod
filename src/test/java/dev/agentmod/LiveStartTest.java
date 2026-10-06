package dev.agentmod;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.agentmod.codex.CodexBackend;
import dev.agentmod.core.AgentBackend;
import dev.agentmod.core.AgentStatus;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.core.NewAgentRequest;
import dev.agentmod.core.ProjectChoice;
import dev.agentmod.core.StartResult;
import dev.agentmod.cursor.CursorCliBackend;
import dev.agentmod.util.Texts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Starts a real agent with a tiny prompt in a scratch folder (build/live-start). Opt in with -Dagentmod.liveStart=codex|cursor.
 * The chat stays in your Codex app or Cursor CLI history afterwards.
 */
class LiveStartTest {
	private static final String PROMPT = "Reply with exactly the word AGENTMOD_OK and nothing else. Don't run commands or read files.";

	@Test
	@EnabledIfSystemProperty(named = "agentmod.liveStart", matches = "codex")
	void codex() throws Exception {
		try (CodexBackend backend = new CodexBackend(new AgentModConfig.Codex(), "live-test")) {
			run(backend);
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "agentmod.liveStart", matches = "cursor")
	void cursor() throws Exception {
		try (CursorCliBackend backend = new CursorCliBackend(new AgentModConfig.Cursor(), "live-test")) {
			run(backend);
		}
	}

	private static void run(AgentBackend backend) throws Exception {
		List<ProjectChoice> projects = backend.recentProjects();
		System.out.printf("%n== %s knows %d project folders, e.g. %s%n", backend.source(), projects.size(),
				projects.stream().limit(3).map(ProjectChoice::path).toList());
		Path folder = Files.createDirectories(Path.of("build", "live-start").toAbsolutePath());
		long start = System.currentTimeMillis();
		StartResult started = backend.startAgent(new NewAgentRequest(folder.toString(), PROMPT, null));
		System.out.printf("start: %s (%s) in %d ms%n", started.ok(), started.message(), System.currentTimeMillis() - start);
		assertTrue(started.ok(), started.message());
		String id = started.agent().id();

		AgentSummary agent = null;
		long deadline = System.currentTimeMillis() + 180_000;
		while (System.currentTimeMillis() < deadline) {
			agent = backend.listAgents().stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
			if (agent != null && !agent.status().isActive()) {
				break;
			}
			Thread.sleep(1000);
		}
		System.out.printf("listed: %s%n", agent);
		assertTrue(agent != null && agent.status() == AgentStatus.IDLE, "agent finished");
		assertTrue(agent.project().equals("live-start"), "tied to the folder");
		List<ChatMessage> messages = backend.loadConversation(id).messages();
		for (ChatMessage message : messages) {
			System.out.printf("  %-9s %s%n", message.role(), Texts.truncate(Texts.oneLine(message.text()), 110));
		}
		assertTrue(messages.stream().anyMatch(m -> m.role() == ChatMessage.Role.ASSISTANT && m.text().contains("AGENTMOD_OK")));
	}
}
