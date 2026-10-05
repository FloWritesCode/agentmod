package dev.agentmod;

import java.util.List;

import dev.agentmod.codex.CodexBackend;
import dev.agentmod.core.AgentBackend;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.core.Conversation;
import dev.agentmod.cursor.CursorBackend;
import dev.agentmod.util.Texts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Read-only smoke test against the real Cursor and Codex data on this machine. Never sends anything. */
@EnabledIfSystemProperty(named = "agentmod.live", matches = "true")
class LiveProbeTest {
	@Test
	void cursor() throws Exception {
		try (CursorBackend backend = new CursorBackend(new AgentModConfig.Cursor())) {
			probe(backend);
		}
	}

	@Test
	void codex() throws Exception {
		try (CodexBackend backend = new CodexBackend(new AgentModConfig.Codex(), "test")) {
			probe(backend);
			long start = System.nanoTime();
			backend.listAgents();
			System.out.printf("second list: %d ms%n", (System.nanoTime() - start) / 1_000_000);
		}
	}

	private static void probe(AgentBackend backend) throws Exception {
		long start = System.nanoTime();
		List<AgentSummary> agents = backend.listAgents();
		long now = System.currentTimeMillis();
		System.out.printf("%n== %s: %d agents in %d ms · %s%n", backend.source(), agents.size(), (System.nanoTime() - start) / 1_000_000, backend.health());
		for (AgentSummary agent : agents.subList(0, Math.min(10, agents.size()))) {
			System.out.printf("  %-9s %5s  %-40s | %-14s | %s%n", agent.status(), Texts.ago(agent.updatedAt(), now),
					Texts.truncate(agent.title(), 40), Texts.truncate(agent.project(), 14), Texts.truncate(agent.subtitle(), 70));
		}
		if (agents.isEmpty()) {
			return;
		}
		start = System.nanoTime();
		Conversation conversation = backend.loadConversation(agents.getFirst().id());
		System.out.printf("  history of \"%s\": %d messages (truncated=%s) in %d ms%n", Texts.truncate(agents.getFirst().title(), 40),
				conversation.messages().size(), conversation.truncated(), (System.nanoTime() - start) / 1_000_000);
		List<ChatMessage> messages = conversation.messages();
		for (ChatMessage message : messages.subList(Math.max(0, messages.size() - 6), messages.size())) {
			System.out.printf("    %-9s %s%n", message.role(), Texts.truncate(Texts.oneLine(message.text()), 110));
		}
	}
}
