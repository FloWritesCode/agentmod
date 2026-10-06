package dev.agentmod.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import dev.agentmod.core.ChatMessage;
import dev.agentmod.util.Json;
import org.junit.jupiter.api.Test;

class AcpTranscriptTest {
	private static void apply(AcpTranscript transcript, String json, long ts) {
		transcript.apply(Json.parseObject(json), ts);
	}

	private static List<String> texts(AcpTranscript transcript) {
		return transcript.messages().stream().map(m -> m.role() + ": " + m.text()).toList();
	}

	@Test
	void mergesStreamedChunksAndSplitsAroundToolCalls() {
		AcpTranscript transcript = new AcpTranscript();
		transcript.addUser("fix the build", 1);
		apply(transcript, "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Looking\"}}", 2);
		apply(transcript, "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\" at it.\"}}", 3);
		apply(transcript, "{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hmm\"}}", 3);
		apply(transcript, "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"a\",\"title\":\"`npm  test`\",\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{\"command\":\"npm  test\"}}", 4);
		apply(transcript, "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"a\",\"status\":\"completed\",\"rawOutput\":{\"exitCode\":1,\"stdout\":\"\"}}", 5);
		apply(transcript, "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"b\",\"title\":\"Edit `src/Main.java`\",\"kind\":\"edit\",\"rawInput\":{\"path\":\"/p/src/Main.java\"}}", 6);
		apply(transcript, "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"c\",\"title\":\"Read src/A.java (1 - 20)\",\"kind\":\"read\",\"rawInput\":{\"path\":\"/p/src/A.java\"}}", 6);
		apply(transcript, "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"d\",\"title\":\"grep -n \\\"TODO\\\"\",\"kind\":\"search\",\"status\":\"failed\"}", 6);
		apply(transcript, "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Fixed.\"}}", 7);

		assertEquals(List.of(
				"USER: fix the build",
				"ASSISTANT: Looking at it.",
				"TOOL: Shell  npm test  (exit 1)",
				"TOOL: Edit  Main.java",
				"TOOL: Read  A.java",
				"TOOL: grep -n \"TODO\"  (failed)",
				"ASSISTANT: Fixed."), texts(transcript));
		assertEquals("Fixed.", transcript.latestActivity());
		assertEquals(2, transcript.messages().get(1).timestamp(), "a streamed reply keeps its first chunk's time");
	}

	@Test
	void replayedUserMessagesStaySeparate() {
		AcpTranscript transcript = new AcpTranscript();
		apply(transcript, "{\"sessionUpdate\":\"user_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"first\"}}", 0);
		apply(transcript, "{\"sessionUpdate\":\"user_message_chunk\",\"content\":{\"type\":\"image\",\"data\":\"AAAA\",\"mimeType\":\"image/png\"}}", 0);
		apply(transcript, "{\"sessionUpdate\":\"user_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"second\"}}", 0);
		assertEquals(List.of("USER: first\n[image]", "USER: second"), texts(transcript));
		assertEquals("first\n[image]", transcript.firstUserText());
	}

	@Test
	void tracksPlanTitleAndPrintedErrors() {
		AcpTranscript transcript = new AcpTranscript();
		transcript.addUser("plan it", 1);
		apply(transcript, "{\"sessionUpdate\":\"plan\",\"entries\":[{\"content\":\"Read code\",\"status\":\"completed\"},{\"content\":\"Write tests\",\"status\":\"in_progress\"},{\"content\":\"Ship\",\"status\":\"pending\"}]}", 2);
		apply(transcript, "{\"sessionUpdate\":\"plan\",\"entries\":[{\"content\":\"Read code\",\"status\":\"completed\"},{\"content\":\"Write tests\",\"status\":\"completed\"},{\"content\":\"Ship\",\"status\":\"in_progress\"}]}", 3);
		apply(transcript, "{\"sessionUpdate\":\"session_info_update\",\"title\":\" Ship the thing \"}", 3);
		assertEquals(List.of("USER: plan it", "TOOL: Plan  2/3 done · Ship"), texts(transcript), "plan updates replace the turn's plan line");
		assertEquals("Ship the thing", transcript.title());
		assertNull(transcript.trailingError());

		apply(transcript, "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Partial answer\\n\\nError: Usage limit reached\"}}", 4);
		assertEquals("Usage limit reached", transcript.trailingError());
	}

	@Test
	void describesToolsWithoutInput() {
		assertEquals("Find src *.java", AcpTranscript.describeTool("search", "Find `src` `*.java`", null, null, "completed", 0));
		assertEquals("Shell  echo `hi`", AcpTranscript.describeTool("execute", "`echo \\`hi\\``", "echo `hi`", null, null, 0));
		assertEquals("Read Lints Main.java", AcpTranscript.describeTool("read", "Read Lints `Main.java`", null, "/p/Main.java", null, 0));
		assertEquals("Tool call", AcpTranscript.describeTool(null, null, null, null, null, 0));
	}
}
