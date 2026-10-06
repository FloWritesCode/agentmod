package dev.agentmod.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import dev.agentmod.core.ProjectChoice;
import org.junit.jupiter.api.Test;

class CursorProjectsTest {
	@Test
	void readsCursorSidebarProjects() {
		String json = "[{\"type\":\"workspace\",\"id\":\"workspace:1\",\"name\":\"AgentMod\",\"lastUsedAt\":1791186679158,"
				+ "\"workspaceIdentifier\":{\"id\":\"1\",\"uri\":{\"$mid\":1,\"fsPath\":\"/Users/me/Developer/AgentMod\",\"scheme\":\"file\"}}},"
				+ "{\"type\":\"workspace\",\"id\":\"workspace:2\",\"name\":\"Remote\",\"workspaceIdentifier\":{\"id\":\"2\"}}]";
		assertEquals(List.of(new ProjectChoice("AgentMod", "/Users/me/Developer/AgentMod", 1791186679158L)), CursorBackend.glassProjects(json));
		assertEquals(List.of(), CursorBackend.glassProjects(null));
	}

	@Test
	void readsRecentlyOpenedFoldersOnly() {
		String json = "{\"entries\":[{\"fileUri\":\"file:///Users/me/notes.md\"},{\"folderUri\":\"file:///Users/me/My%20App\"},"
				+ "{\"folderUri\":\"vscode-remote://ssh-remote%2Bbox/home/me\"}]}";
		assertEquals(List.of(new ProjectChoice("My App", "/Users/me/My App", 0)), CursorBackend.recentFolders(json));
	}
}
