package dev.agentmod.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.agentmod.core.ChatMessage;
import dev.agentmod.util.Json;
import org.junit.jupiter.api.Test;

class CodexItemsTest {
	@Test
	void mapsUserMessage() {
		var item = Json.parseObject("{\"type\":\"userMessage\",\"id\":\"1\",\"content\":[{\"type\":\"text\",\"text\":\"run the tests\"},{\"type\":\"localImage\",\"path\":\"/a.png\"}]}");
		ChatMessage message = CodexBackend.toMessage(item, 5);
		assertEquals(ChatMessage.Role.USER, message.role());
		assertEquals("run the tests\n[image]", message.text());
		assertEquals(5, message.timestamp());
	}

	@Test
	void mapsCommandWithExitCode() {
		var item = Json.parseObject("{\"type\":\"commandExecution\",\"id\":\"2\",\"command\":\"npm  test\",\"exitCode\":1,\"status\":\"completed\"}");
		ChatMessage message = CodexBackend.toMessage(item, 0);
		assertEquals(ChatMessage.Role.TOOL, message.role());
		assertEquals("Shell  npm test  (exit 1)", message.text());
	}

	@Test
	void mapsFileChange() {
		var item = Json.parseObject("{\"type\":\"fileChange\",\"id\":\"3\",\"changes\":[{\"path\":\"/x/src/A.java\",\"kind\":{\"type\":\"update\"},\"diff\":\"\"}]}");
		assertEquals("Edit  A.java", CodexBackend.toMessage(item, 0).text());
	}

	@Test
	void skipsReasoning() {
		assertNull(CodexBackend.toMessage(Json.parseObject("{\"type\":\"reasoning\",\"id\":\"4\",\"summary\":[]}"), 0));
	}
}
