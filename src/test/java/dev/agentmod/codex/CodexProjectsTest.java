package dev.agentmod.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import dev.agentmod.core.ProjectChoice;
import dev.agentmod.util.Json;
import org.junit.jupiter.api.Test;

class CodexProjectsTest {
	@Test
	void readsCodexAppProjects() {
		var state = Json.parseObject("{\"local-projects\":{"
				+ "\"a\":{\"id\":\"a\",\"name\":\"Bien\",\"rootPaths\":[\"/Users/me/Developer/Bien\"],\"createdAt\":100,\"updatedAt\":200},"
				+ "\"b\":{\"id\":\"b\",\"rootPaths\":[\"/Users/me/x/tool\"],\"createdAt\":300},"
				+ "\"c\":{\"id\":\"c\",\"name\":\"Empty\",\"rootPaths\":[]}},"
				+ "\"project-order\":[\"a\",\"b\",\"c\"]}");
		assertEquals(List.of(
				new ProjectChoice("Bien", "/Users/me/Developer/Bien", 200),
				new ProjectChoice("tool", "/Users/me/x/tool", 300)), CodexBackend.desktopProjects(state));
		assertEquals(List.of(), CodexBackend.desktopProjects(Json.parseObject("{}")));
	}
}
