package dev.agentmod.cursor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentmod.util.Json;

/**
 * A stand-in for {@code cursor-agent} in tests: answers {@code status} and speaks enough ACP to start, prompt
 * (with one shell permission request), load and cancel sessions. Sessions persist under {@code $FAKE_ACP_SESSIONS}
 * in the same layout as the real CLI, with a JSON-lines log as {@code store.db}.
 */
public final class FakeAcpAgent {
	private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
	private static final AtomicLong IDS = new AtomicLong(1000);
	private static final Map<Long, CompletableFuture<JsonObject>> WAITING = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> CANCELLED = new ConcurrentHashMap<>();
	private static Path sessions;

	private FakeAcpAgent() {
	}

	public static void main(String[] args) throws Exception {
		boolean signedOut = "required".equals(System.getenv("FAKE_ACP_AUTH"));
		if (args.length > 0 && args[0].equals("status")) {
			OUT.println(signedOut ? "Not logged in" : "\u001B[32m✓\u001B[0m Logged in as test@example.com");
			return;
		}
		sessions = Path.of(System.getenv("FAKE_ACP_SESSIONS"));
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			JsonObject message = JsonParser.parseString(line).getAsJsonObject();
			String method = Json.str(message, "method");
			JsonElement id = message.get("id");
			if (method == null) {
				CompletableFuture<JsonObject> waiting = WAITING.remove(id.getAsLong());
				if (waiting != null) {
					waiting.complete(message);
				}
				continue;
			}
			JsonObject params = Json.obj(message, "params");
			switch (method) {
				case "initialize" -> respond(id, Json.object("protocolVersion", 1,
						"agentCapabilities", Json.object("loadSession", true),
						"authMethods", Json.array(Json.object("id", "cursor_login", "name", "Cursor login"))));
				case "authenticate" -> respond(id, new JsonObject());
				case "session/new" -> {
					if (signedOut) {
						error(id, -32000, "Authentication required");
						continue;
					}
					String sessionId = UUID.randomUUID().toString();
					Path dir = sessions.resolve(sessionId);
					Files.createDirectories(dir);
					Files.writeString(dir.resolve("meta.json"), Json.GSON.toJson(Json.object("schemaVersion", 1, "cwd", Json.str(params, "cwd"))));
					Files.writeString(dir.resolve("store.db"), "");
					respond(id, Json.object("sessionId", sessionId));
				}
				case "session/set_mode" -> {
					log(Json.str(params, "sessionId"), "mode", Json.str(params, "modeId"));
					respond(id, new JsonObject());
				}
				case "session/load" -> {
					String sessionId = Json.str(params, "sessionId");
					for (String entry : Files.readAllLines(sessions.resolve(sessionId).resolve("store.db"))) {
						JsonObject logged = Json.parseObject(entry);
						String kind = Json.str(logged, "kind");
						String text = Json.str(logged, "text");
						if ("user".equals(kind)) {
							update(sessionId, Json.object("sessionUpdate", "user_message_chunk", "content", text(text)));
						} else if ("agent".equals(kind)) {
							update(sessionId, Json.object("sessionUpdate", "agent_message_chunk", "content", text(text)));
						}
					}
					respond(id, new JsonObject());
				}
				case "session/prompt" -> Thread.ofPlatform().start(() -> prompt(id, params));
				case "session/cancel" -> CANCELLED.put(Json.str(params, "sessionId"), true);
				default -> error(id, -32601, "Method not found: " + method);
			}
		}
	}

	private static void prompt(JsonElement id, JsonObject params) {
		try {
			String sessionId = Json.str(params, "sessionId");
			CANCELLED.remove(sessionId);
			String text = Json.str(Json.arr(params, "prompt").get(0), "text");
			log(sessionId, "user", text);
			String reply = "Working in " + Path.of("").toAbsolutePath() + " on: " + text;
			update(sessionId, Json.object("sessionUpdate", "agent_message_chunk", "content", text("Working in " + Path.of("").toAbsolutePath())));
			update(sessionId, Json.object("sessionUpdate", "agent_message_chunk", "content", text(" on: " + text)));
			update(sessionId, Json.object("sessionUpdate", "session_info_update", "title", "Fake title"));
			update(sessionId, Json.object("sessionUpdate", "tool_call", "toolCallId", "t1", "title", "`ls -la`", "kind", "execute",
					"status", "pending", "rawInput", Json.object("command", "ls -la")));

			long requestId = IDS.incrementAndGet();
			CompletableFuture<JsonObject> answer = new CompletableFuture<>();
			WAITING.put(requestId, answer);
			send(Json.object("jsonrpc", "2.0", "id", requestId, "method", "session/request_permission", "params", Json.object(
					"sessionId", sessionId,
					"toolCall", Json.object("toolCallId", "t1", "title", "`ls -la`", "kind", "execute", "status", "pending",
							"content", Json.array(Json.object("type", "content", "content", text("List the files")))),
					"options", Json.array(
							Json.object("optionId", "allow-once", "name", "Allow once", "kind", "allow_once"),
							Json.object("optionId", "allow-always", "name", "Allow always", "kind", "allow_always"),
							Json.object("optionId", "reject-once", "name", "Reject", "kind", "reject_once")))));
			JsonObject response = answer.get(60, TimeUnit.SECONDS);
			String outcome = Json.str(response, "result", "outcome", "outcome");
			if (!"selected".equals(outcome) || Boolean.TRUE.equals(CANCELLED.get(sessionId))) {
				log(sessionId, "agent", reply);
				respond(id, Json.object("stopReason", "cancelled"));
				return;
			}
			boolean allowed = Json.str(response, "result", "outcome", "optionId").startsWith("allow");
			update(sessionId, Json.object("sessionUpdate", "tool_call_update", "toolCallId", "t1", "status", allowed ? "completed" : "failed",
					"rawOutput", Json.object("exitCode", allowed ? 0 : 1)));
			update(sessionId, Json.object("sessionUpdate", "agent_message_chunk", "content", text("\n\nDone.")));
			log(sessionId, "agent", reply + "\n\nDone.");
			respond(id, Json.object("stopReason", "end_turn"));
		} catch (Exception e) {
			error(id, -32603, e.toString());
		}
	}

	private static JsonObject text(String text) {
		return Json.object("type", "text", "text", text);
	}

	private static void log(String sessionId, String kind, String text) {
		try {
			Files.writeString(sessions.resolve(sessionId).resolve("store.db"), Json.GSON.toJson(Json.object("kind", kind, "text", text)) + "\n",
					StandardOpenOption.APPEND);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void update(String sessionId, JsonObject update) {
		send(Json.object("jsonrpc", "2.0", "method", "session/update", "params", Json.object("sessionId", sessionId, "update", update)));
	}

	private static void respond(JsonElement id, JsonObject result) {
		JsonObject message = Json.object("jsonrpc", "2.0", "result", result);
		message.add("id", id);
		send(message);
	}

	private static void error(JsonElement id, int code, String text) {
		JsonObject message = Json.object("jsonrpc", "2.0", "error", Json.object("code", code, "message", text));
		message.add("id", id);
		send(message);
	}

	private static synchronized void send(JsonObject message) {
		OUT.println(Json.GSON.toJson(message));
	}
}
