package dev.agentmod.cursor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentmod.util.Json;
import dev.agentmod.util.UnixSocketHttp;

/**
 * Client for Cursor's Desktop Bridge (Cursor Settings → Beta → Desktop Bridge →
 * "Allow CLI to access desktop agents").
 *
 * <p>When enabled, each running Cursor instance writes a discovery file to {@code ~/.cursor/desktop-bridge/}
 * with a Unix socket path and bearer token. The socket speaks HTTP: {@code POST /} with
 * {@code {"type":"listThreads"}} or {@code {"type":"sendMessage","threadId":...,"text":...}}.
 */
final class DesktopBridge {
	record Endpoint(Path socket, String token, String appVersion, long pid) {
	}

	record BridgeThread(String id, String title, String source, String status, long lastUpdatedAt) {
	}

	record SendOutcome(String status, String detail) {
	}

	private static final Duration LIST_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration SEND_TIMEOUT = Duration.ofSeconds(20);

	private final Path dir;
	private Endpoint cached;
	private long cachedAt;

	DesktopBridge() {
		String override = System.getenv("CURSOR_DESKTOP_BRIDGE_DIR");
		this.dir = override != null && !override.isBlank()
				? Path.of(override)
				: Path.of(System.getProperty("user.home"), ".cursor", "desktop-bridge");
	}

	synchronized Endpoint endpoint() {
		long now = System.currentTimeMillis();
		if (now - cachedAt < 5000) {
			return cached;
		}
		cachedAt = now;
		cached = discover();
		return cached;
	}

	synchronized void invalidate() {
		cachedAt = 0;
	}

	private Endpoint discover() {
		if (!Files.isDirectory(dir)) {
			return null;
		}
		Endpoint best = null;
		long bestCreated = Long.MIN_VALUE;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path file : files) {
				try {
					JsonObject info = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
					String socket = Json.str(info, "socketPath");
					String token = Json.str(info, "token");
					long pid = Json.lng(info, -1, "pid");
					long created = Json.lng(info, 0, "createdAt");
					if (socket == null || token == null || !Files.exists(Path.of(socket))) {
						continue;
					}
					if (pid > 0 && !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
						continue;
					}
					if (created > bestCreated) {
						bestCreated = created;
						best = new Endpoint(Path.of(socket), token, Json.str(info, "appVersion"), pid);
					}
				} catch (Exception ignored) {
					// Half-written or stale discovery file.
				}
			}
		} catch (IOException ignored) {
			return null;
		}
		return best;
	}

	List<BridgeThread> listThreads() throws IOException {
		JsonObject response = call(Json.object("type", "listThreads"), LIST_TIMEOUT);
		List<BridgeThread> threads = new ArrayList<>();
		JsonArray array = Json.arr(response, "threads");
		if (array != null) {
			for (JsonElement e : array) {
				String id = Json.str(e, "id");
				if (id != null) {
					threads.add(new BridgeThread(id, Json.str(e, "title"), Json.str(e, "source"), Json.str(e, "status"), Json.lng(e, 0, "lastUpdatedAt")));
				}
			}
		}
		return threads;
	}

	SendOutcome sendMessage(String threadId, String text) throws IOException {
		JsonObject response = call(Json.object("type", "sendMessage", "threadId", threadId, "text", text), SEND_TIMEOUT);
		String status = Json.str(response, "status");
		String detail = Json.str(response, "reason");
		if (detail == null) {
			detail = Json.str(response, "message");
		}
		if (detail == null) {
			detail = Json.str(response, "threadTitle");
		}
		return new SendOutcome(status == null ? "error" : status, detail);
	}

	private JsonObject call(JsonObject body, Duration timeout) throws IOException {
		Endpoint endpoint = endpoint();
		if (endpoint == null) {
			throw new IOException("Desktop Bridge is not enabled");
		}
		UnixSocketHttp.Response response;
		try {
			response = UnixSocketHttp.postJson(endpoint.socket(), "/", Map.of("Authorization", "Bearer " + endpoint.token()), Json.GSON.toJson(body), timeout);
		} catch (IOException e) {
			invalidate();
			throw e;
		}
		if (response.status() == 401) {
			invalidate();
			throw new IOException("Desktop Bridge rejected the token");
		}
		JsonObject json = Json.parseObject(response.body());
		if (response.status() != 200 || json == null) {
			String error = json != null ? Json.str(json, "error") : null;
			throw new IOException("Desktop Bridge HTTP " + response.status() + (error != null ? " (" + error + ")" : ""));
		}
		return json;
	}
}
