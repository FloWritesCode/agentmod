package dev.agentmod.cursor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import dev.agentmod.AgentModConfig;
import dev.agentmod.core.AgentBackend;
import dev.agentmod.core.AgentSource;
import dev.agentmod.core.AgentStatus;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.core.BackendHealth;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.core.Conversation;
import dev.agentmod.core.ReplyResult;
import dev.agentmod.util.Json;
import dev.agentmod.util.Texts;

/**
 * Cursor agents: the list, status and history come from Cursor's local state database;
 * replies go through the Desktop Bridge when the user has enabled it.
 */
public final class CursorBackend implements AgentBackend {
	static final String ENABLE_BRIDGE_HINT = "To reply, enable Cursor Settings → Beta → Desktop Bridge";
	private static final long STALE_RUN_MS = 3 * 60 * 60 * 1000L;
	private static final int MAX_BUBBLES = 400;
	private static final Pattern FIRST_STRING_FIELD = Pattern.compile("\"(command|targetFile|target_file|path|relativeWorkspacePath|file_path|filePath|pattern|query|searchTerm|search_term|url|glob_pattern|globPattern|description|toolName)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)");
	private static final Set<String> FILE_KEYS = Set.of("targetFile", "target_file", "path", "relativeWorkspacePath", "file_path", "filePath");

	private final AgentModConfig.Cursor config;
	private final CursorStateDb db;
	private final DesktopBridge bridge = new DesktopBridge();
	private volatile BackendHealth health = new BackendHealth(AgentSource.CURSOR, false, "Starting…");

	public CursorBackend(AgentModConfig.Cursor config) {
		this.config = config;
		Path dbPath = config.stateDbPath == null || config.stateDbPath.isBlank() ? CursorStateDb.defaultDbPath() : Path.of(config.stateDbPath);
		this.db = new CursorStateDb(dbPath, CursorStateDb.findSqlite(config.sqlitePath));
	}

	@Override
	public AgentSource source() {
		return AgentSource.CURSOR;
	}

	@Override
	public BackendHealth health() {
		return health;
	}

	@Override
	public List<AgentSummary> listAgents() throws Exception {
		if (!db.available()) {
			health = new BackendHealth(AgentSource.CURSOR, false, "Cursor data not found (needs sqlite3 and " + db.dbPath().getFileName() + ")");
			return List.of();
		}
		List<JsonObject> rows = db.headers(config.maxAgents);
		Map<String, DesktopBridge.BridgeThread> live = new HashMap<>();
		boolean bridgeOn = bridge.endpoint() != null;
		if (bridgeOn) {
			try {
				for (DesktopBridge.BridgeThread t : bridge.listThreads()) {
					live.put(t.id(), t);
				}
			} catch (Exception e) {
				bridgeOn = false;
			}
		}
		String replyHint = bridgeOn ? "Replies are sent to Cursor" : ENABLE_BRIDGE_HINT;
		long now = System.currentTimeMillis();
		List<AgentSummary> agents = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonObject row : rows) {
			String id = Json.str(row, "id");
			JsonObject header = Json.parseObject(Json.str(row, "value"));
			if (id == null || header == null || Json.bool(header, "isDraft") || Json.bool(header, "isEphemeral") || Json.bool(header, "isBestOfNSubcomposer")) {
				continue;
			}
			DesktopBridge.BridgeThread liveThread = live.get(id);
			String name = Json.str(header, "name");
			String subtitle = Json.str(header, "subtitle");
			if (name == null && subtitle == null && liveThread == null) {
				continue;
			}
			seen.add(id);
			long updatedAt = Math.max(Json.lng(header, 0, "lastUpdatedAt"),
					Math.max(Json.lng(row, 0, "checkpointAt"), Json.lng(header, 0, "conversationCheckpointLastUpdatedAt")));
			long unfinishedRunAt = Json.lng(header, 0, "unfinishedRunAt");
			AgentStatus status = unfinishedRunAt > 0 && now - Math.max(updatedAt, unfinishedRunAt) < STALE_RUN_MS ? AgentStatus.RUNNING : AgentStatus.IDLE;
			if (liveThread != null) {
				status = fromBridgeStatus(liveThread.status(), status);
			}
			if (Json.bool(header, "hasBlockingPendingActions")) {
				status = AgentStatus.WAITING;
			}
			String title = name != null && !name.isBlank() ? name : liveThread != null && liveThread.title() != null ? liveThread.title() : "Untitled chat";
			String project = Texts.baseName(Json.str(header, "workspaceIdentifier", "uri", "fsPath"));
			if ("cloud".equals(Json.str(header, "agentLocation", "type"))) {
				project = project.isEmpty() ? "cloud" : project + " (cloud)";
			}
			agents.add(new AgentSummary(AgentSource.CURSOR, id, title, subtitle == null ? "" : subtitle, project, status,
					updatedAt, Json.bool(header, "hasUnreadMessages"), bridgeOn, replyHint));
		}
		for (DesktopBridge.BridgeThread t : live.values()) {
			if (seen.contains(t.id()) || "draft".equals(t.source())) {
				continue;
			}
			String project = "cloud".equals(t.source()) ? "cloud" : "claude-code".equals(t.source()) ? "Claude Code" : "";
			agents.add(new AgentSummary(AgentSource.CURSOR, t.id(), t.title() == null ? "Untitled chat" : t.title(), "", project,
					fromBridgeStatus(t.status(), AgentStatus.IDLE), t.lastUpdatedAt(), false, true, replyHint));
		}
		health = new BackendHealth(AgentSource.CURSOR, true, bridgeOn ? "Replies on (Desktop Bridge)" : "Read-only: Desktop Bridge is off");
		return agents;
	}

	private static AgentStatus fromBridgeStatus(String status, AgentStatus fallback) {
		if (status == null) {
			return fallback;
		}
		return switch (status) {
			case "running" -> AgentStatus.RUNNING;
			case "error" -> AgentStatus.ERROR;
			case "idle", "completed" -> AgentStatus.IDLE;
			default -> fallback;
		};
	}

	@Override
	public Conversation loadConversation(String id) throws Exception {
		List<JsonObject> rows = db.bubbles(id, MAX_BUBBLES);
		List<ChatMessage> messages = new ArrayList<>();
		for (JsonObject row : rows) {
			long type = Json.lng(row, 0, "type");
			String text = Json.str(row, "text");
			String tool = Json.str(row, "tool");
			long ts = Texts.parseIsoMillis(Json.str(row, "createdAt"));
			if (type == 1) {
				String clean = Texts.cleanUserText(text);
				if (!clean.isEmpty()) {
					messages.add(ChatMessage.user(clean, ts));
				}
			} else if (type == 2) {
				if (tool != null) {
					messages.add(ChatMessage.tool(describeTool(tool, Json.str(row, "params")), ts));
				} else if (text != null && !text.isBlank()) {
					int last = messages.size() - 1;
					if (last >= 0 && messages.get(last).role() == ChatMessage.Role.ASSISTANT) {
						ChatMessage previous = messages.get(last);
						messages.set(last, ChatMessage.assistant(previous.text() + "\n\n" + text.strip(), previous.timestamp()));
					} else {
						messages.add(ChatMessage.assistant(text.strip(), ts));
					}
				}
			}
		}
		return new Conversation(messages, List.of(), rows.size() >= MAX_BUBBLES);
	}

	static String describeTool(String tool, String params) {
		String label = switch (tool) {
			case "run_terminal_command_v2", "run_terminal_cmd", "run_terminal_command" -> "Shell";
			case "read_file", "read_file_v2" -> "Read";
			case "edit_file", "edit_file_v2", "search_replace", "write", "apply_patch", "edit" -> "Edit";
			case "delete_file" -> "Delete";
			case "ripgrep_raw_search", "grep_search", "grep", "ripgrep" -> "Grep";
			case "glob_file_search", "file_search" -> "Find files";
			case "codebase_search", "semantic_search" -> "Search";
			case "list_dir", "list_dir_v2" -> "List";
			case "web_search" -> "Web search";
			case "web_fetch", "fetch" -> "Fetch";
			case "todo_write" -> "Updated todos";
			case "task", "task_v2" -> "Subagent";
			case "read_lints" -> "Lints";
			default -> tool.startsWith("mcp") ? "MCP" : tool.replace('_', ' ');
		};
		if (params == null) {
			return label;
		}
		Matcher m = FIRST_STRING_FIELD.matcher(params);
		if (!m.find()) {
			return label;
		}
		String value = m.group(2).replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
		if (FILE_KEYS.contains(m.group(1))) {
			value = Texts.baseName(value);
		}
		return label + "  " + Texts.oneLine(value);
	}

	@Override
	public ReplyResult sendReply(String id, String text) throws Exception {
		if (bridge.endpoint() == null) {
			return ReplyResult.failed(ENABLE_BRIDGE_HINT + " → \"Allow CLI to access desktop agents\".");
		}
		DesktopBridge.SendOutcome outcome = bridge.sendMessage(id, text);
		return switch (outcome.status()) {
			case "submitted" -> ReplyResult.ok("Sent to Cursor");
			case "queued" -> ReplyResult.ok("Queued in Cursor: it sends once the agent finishes its current step");
			case "not-sendable" -> ReplyResult.failed("Cursor can't send to this chat right now" + (outcome.detail() != null ? ": " + outcome.detail() : ""));
			case "unknown-thread" -> ReplyResult.failed("No open Cursor window has this chat. Open it in Cursor once, then retry.");
			case "timeout" -> ReplyResult.failed("Cursor didn't respond in time");
			default -> ReplyResult.failed("Cursor: " + (outcome.detail() != null ? outcome.detail() : outcome.status()));
		};
	}

	@Override
	public void close() {
	}
}
