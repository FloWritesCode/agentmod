package dev.agentmod.cursor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.util.Json;
import dev.agentmod.util.Texts;

/**
 * The conversation of one ACP session, built from {@code session/update} events: replayed history after
 * {@code session/load}, then live updates while a prompt runs. Thread-safe.
 */
final class AcpTranscript {
	private static final int MAX_ENTRIES = 800;

	private static final class Entry {
		final ChatMessage.Role role;
		final long timestamp;
		final StringBuilder text = new StringBuilder();
		String toolKind;
		String toolTitle;
		String toolStatus;
		String command;
		String path;
		long exitCode;

		Entry(ChatMessage.Role role, long timestamp) {
			this.role = role;
			this.timestamp = timestamp;
		}
	}

	private final List<Entry> entries = new ArrayList<>();
	private final Map<String, Entry> tools = new HashMap<>();
	private Entry plan;
	private String title;

	synchronized void addUser(String text, long ts) {
		Entry entry = add(ChatMessage.Role.USER, ts);
		entry.text.append(text);
		plan = null;
	}

	synchronized void addNotice(String text, long ts) {
		add(ChatMessage.Role.NOTICE, ts).text.append(text);
	}

	/** Applies one {@code session/update} payload ({@code params.update}). */
	synchronized void apply(JsonObject update, long ts) {
		String kind = Json.str(update, "sessionUpdate");
		if (kind == null) {
			return;
		}
		switch (kind) {
			case "user_message_chunk" -> {
				String text = contentText(Json.obj(update, "content"));
				Entry last = entries.isEmpty() ? null : entries.getLast();
				if ("image".equals(Json.str(update, "content", "type")) && last != null && last.role == ChatMessage.Role.USER) {
					last.text.append("\n[image]");
				} else if (text != null) {
					addUser(text, ts);
				}
			}
			case "agent_message_chunk" -> {
				String text = contentText(Json.obj(update, "content"));
				if (text == null || text.isEmpty()) {
					return;
				}
				Entry last = entries.isEmpty() ? null : entries.getLast();
				if (last == null || last.role != ChatMessage.Role.ASSISTANT) {
					last = add(ChatMessage.Role.ASSISTANT, ts);
				}
				last.text.append(text);
			}
			case "tool_call" -> {
				String id = Json.str(update, "toolCallId");
				Entry tool = id != null ? tools.get(id) : null;
				if (tool == null) {
					tool = add(ChatMessage.Role.TOOL, ts);
					if (id != null) {
						tools.put(id, tool);
					}
				}
				updateTool(tool, update);
			}
			case "tool_call_update" -> {
				String id = Json.str(update, "toolCallId");
				Entry tool = id != null ? tools.get(id) : null;
				if (tool != null) {
					updateTool(tool, update);
				}
			}
			case "plan" -> {
				if (plan == null) {
					plan = add(ChatMessage.Role.TOOL, ts);
					plan.toolKind = "plan";
				}
				plan.toolTitle = describePlan(Json.arr(update, "entries"));
			}
			case "current_mode_update" -> {
				String mode = Json.str(update, "currentModeId");
				if (mode != null && ts > 0) {
					addNotice("Switched to " + mode + " mode", ts);
				}
			}
			case "session_info_update" -> {
				String newTitle = Json.str(update, "title");
				if (newTitle != null && !newTitle.isBlank()) {
					title = newTitle.strip();
				}
			}
			default -> {
			}
		}
	}

	private Entry add(ChatMessage.Role role, long ts) {
		Entry entry = new Entry(role, ts);
		entries.add(entry);
		if (entries.size() > MAX_ENTRIES) {
			Entry dropped = entries.removeFirst();
			tools.values().remove(dropped);
		}
		return entry;
	}

	private static void updateTool(Entry tool, JsonObject update) {
		String kind = Json.str(update, "kind");
		if (kind != null) {
			tool.toolKind = kind;
		}
		String title = Json.str(update, "title");
		if (title != null && !title.isBlank()) {
			tool.toolTitle = title;
		}
		String status = Json.str(update, "status");
		if (status != null) {
			tool.toolStatus = status;
		}
		String command = Json.str(update, "rawInput", "command");
		if (command != null) {
			tool.command = command;
		}
		String path = Json.str(update, "rawInput", "path");
		if (path == null) {
			JsonArray locations = Json.arr(update, "locations");
			if (locations != null && !locations.isEmpty()) {
				path = Json.str(locations.get(0), "path");
			}
		}
		if (path != null) {
			tool.path = path;
		}
		long exit = Json.lng(update, Long.MIN_VALUE, "rawOutput", "exitCode");
		if (exit != Long.MIN_VALUE) {
			tool.exitCode = exit;
		}
	}

	private static String contentText(JsonObject content) {
		if (content == null) {
			return null;
		}
		String type = Json.str(content, "type");
		if ("text".equals(type)) {
			return Json.str(content, "text");
		}
		if ("resource_link".equals(type)) {
			return "@" + Json.str(content, "name");
		}
		return null;
	}

	private static String describePlan(JsonArray items) {
		if (items == null || items.isEmpty()) {
			return "Updated plan";
		}
		int done = 0;
		String current = null;
		for (JsonElement item : items) {
			String status = Json.str(item, "status");
			if ("completed".equals(status)) {
				done++;
			} else if (current == null && "in_progress".equals(status)) {
				current = Json.str(item, "content");
			}
		}
		String summary = "Plan  " + done + "/" + items.size() + " done";
		return current != null ? summary + " · " + Texts.oneLine(current) : summary;
	}

	/** One-line tool summary in the same style as the other backends ("Shell  ls -la", "Edit  Foo.java"). */
	static String describeTool(String kind, String title, String command, String path, String status, long exitCode) {
		String raw = title == null ? "" : title.replace("\\`", "\u0000").replace("`", "").replace('\u0000', '`');
		String clean = "plan".equals(kind) ? raw : Texts.oneLine(raw);
		String text = switch (kind == null ? "" : kind) {
			case "plan" -> clean;
			case "execute" -> command != null ? "Shell  " + Texts.oneLine(command) : clean;
			case "edit" -> path != null ? "Edit  " + Texts.baseName(path) : clean;
			case "delete" -> path != null ? "Delete  " + Texts.baseName(path) : clean;
			case "read" -> path != null && clean.startsWith("Read ") && !clean.startsWith("Read Lints") ? "Read  " + Texts.baseName(path) : clean;
			default -> clean;
		};
		if (text.isBlank()) {
			text = "Tool call";
		}
		text = Texts.truncate(text, 240);
		if ("failed".equals(status)) {
			text += "  (failed)";
		} else if (exitCode != 0) {
			text += "  (exit " + exitCode + ")";
		}
		return text;
	}

	synchronized List<ChatMessage> messages() {
		List<ChatMessage> out = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			ChatMessage message = toMessage(entry);
			if (message != null) {
				out.add(message);
			}
		}
		return out;
	}

	private static ChatMessage toMessage(Entry entry) {
		return switch (entry.role) {
			case USER -> {
				String text = Texts.cleanUserText(entry.text.toString());
				yield text.isEmpty() ? null : ChatMessage.user(text, entry.timestamp);
			}
			case ASSISTANT -> {
				String text = entry.text.toString().strip();
				yield text.isEmpty() ? null : ChatMessage.assistant(text, entry.timestamp);
			}
			case TOOL -> ChatMessage.tool(describeTool(entry.toolKind, entry.toolTitle, entry.command, entry.path, entry.toolStatus, entry.exitCode), entry.timestamp);
			case NOTICE -> ChatMessage.notice(entry.text.toString(), entry.timestamp);
		};
	}

	/** Last line of the newest reply, or the newest tool call: what the agent list shows under the title. */
	synchronized String latestActivity() {
		for (int i = entries.size() - 1; i >= 0; i--) {
			Entry entry = entries.get(i);
			if (entry.role == ChatMessage.Role.ASSISTANT || entry.role == ChatMessage.Role.TOOL) {
				ChatMessage message = toMessage(entry);
				if (message != null) {
					return Texts.truncate(Texts.oneLine(message.text()), 200);
				}
			}
		}
		return null;
	}

	/** The error Cursor's agent printed as its final reply ("Error: ..."), if the last turn ended that way. */
	synchronized String trailingError() {
		if (entries.isEmpty() || entries.getLast().role != ChatMessage.Role.ASSISTANT) {
			return null;
		}
		String text = entries.getLast().text.toString().strip();
		int index = text.lastIndexOf("Error: ");
		if (index < 0 || (index > 0 && text.charAt(index - 1) != '\n')) {
			return null;
		}
		return Texts.truncate(Texts.oneLine(text.substring(index + "Error: ".length())), 200);
	}

	synchronized String firstUserText() {
		for (Entry entry : entries) {
			if (entry.role == ChatMessage.Role.USER) {
				return Texts.cleanUserText(entry.text.toString());
			}
		}
		return null;
	}

	synchronized String title() {
		return title;
	}
}
