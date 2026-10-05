package dev.agentmod.codex;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentmod.util.Json;

/**
 * Talks to the Codex desktop app's local IPC router ({@code ~/.codex/ipc/ipc.sock}), the same channel the Codex
 * IDE extension uses to drive threads the app has open. Frames are a little-endian uint32 length followed by JSON.
 *
 * <p>We only use it to find which app window owns a thread and to start a turn there, so a reply from Minecraft
 * shows up live in the app instead of running in a second Codex process.
 */
final class CodexDesktopIpc implements AutoCloseable {
	record Result(boolean ok, JsonObject message, String error) {
	}

	/** Protocol versions the app expects for each method; a mismatch is rejected with "request-version-mismatch". */
	static final int VERSION_OWNER_DISCOVERY = 1;
	static final int VERSION_FOLLOWER_START_TURN = 2;

	private static final int MAX_FRAME = 64 * 1024 * 1024;
	private static final String INITIALIZING_CLIENT_ID = "initializing-client";

	private final Path socket;
	private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final Object writeLock = new Object();
	private SocketChannel channel;
	private volatile String clientId;
	private long lastAttempt;

	CodexDesktopIpc() {
		String codexHome = System.getenv("CODEX_HOME");
		Path home = codexHome != null && !codexHome.isBlank() ? Path.of(codexHome) : Path.of(System.getProperty("user.home"), ".codex");
		this.socket = home.resolve("ipc").resolve("ipc.sock");
	}

	boolean socketExists() {
		return Files.exists(socket);
	}

	boolean isConnected() {
		return clientId != null;
	}

	private synchronized boolean connect() {
		if (clientId != null && channel != null && channel.isOpen()) {
			return true;
		}
		if (!Files.exists(socket)) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (now - lastAttempt < 3000) {
			return false;
		}
		lastAttempt = now;
		disconnect();
		try {
			SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
			ch.connect(UnixDomainSocketAddress.of(socket));
			channel = ch;
			Thread.ofPlatform().daemon().name("AgentMod-codex-ipc").start(() -> readLoop(ch));
			CompletableFuture<JsonObject> init = send("initialize", 0, Json.object("clientType", "agentmod"), null, 3000, INITIALIZING_CLIENT_ID);
			JsonObject response = init.get(3500, TimeUnit.MILLISECONDS);
			clientId = Json.str(response, "result", "clientId");
			return clientId != null;
		} catch (Exception e) {
			disconnect();
			return false;
		}
	}

	/** Returns the IPC client id of the Codex window that has this thread open, or null. */
	String findOwner(String threadId) {
		Result result = request("thread-owner-discovery", VERSION_OWNER_DISCOVERY,
				Json.object("hostId", "local", "conversationId", threadId), null, 2500);
		return result.ok() ? Json.str(result.message(), "handledByClientId") : null;
	}

	Result request(String method, int version, JsonObject params, String targetClientId, long timeoutMs) {
		if (!connect()) {
			return new Result(false, null, "Codex app isn't running");
		}
		try {
			JsonObject response = send(method, version, params, targetClientId, timeoutMs, clientId).get(timeoutMs + 1500, TimeUnit.MILLISECONDS);
			if ("success".equals(Json.str(response, "resultType"))) {
				return new Result(true, response, null);
			}
			String error = Json.str(response, "error");
			return new Result(false, response, error != null ? error : "unknown error");
		} catch (java.util.concurrent.TimeoutException e) {
			return new Result(false, null, "timeout");
		} catch (Exception e) {
			return new Result(false, null, e.getMessage());
		}
	}

	private CompletableFuture<JsonObject> send(String method, int version, JsonObject params, String targetClientId, long timeoutMs, String sourceClientId) throws IOException {
		String requestId = UUID.randomUUID().toString();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		pending.put(requestId, future);
		future.whenComplete((r, e) -> pending.remove(requestId));
		JsonObject message = Json.object(
				"type", "request",
				"requestId", requestId,
				"sourceClientId", sourceClientId,
				"version", version,
				"method", method,
				"params", params,
				"targetClientId", targetClientId,
				"timeoutMs", timeoutMs);
		write(message);
		return future;
	}

	private void write(JsonObject message) throws IOException {
		byte[] payload = Json.GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
		ByteBuffer frame = ByteBuffer.allocate(4 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		frame.putInt(payload.length).put(payload).flip();
		synchronized (writeLock) {
			SocketChannel ch = channel;
			if (ch == null || !ch.isOpen()) {
				throw new IOException("Codex IPC disconnected");
			}
			while (frame.hasRemaining()) {
				ch.write(frame);
			}
		}
	}

	private void readLoop(SocketChannel ch) {
		ByteBuffer header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		try {
			while (ch.isOpen()) {
				header.clear();
				readFully(ch, header);
				int length = header.getInt(0);
				if (length < 0 || length > MAX_FRAME) {
					break;
				}
				ByteBuffer body = ByteBuffer.allocate(length);
				readFully(ch, body);
				JsonObject message;
				try {
					message = JsonParser.parseString(new String(body.array(), StandardCharsets.UTF_8)).getAsJsonObject();
				} catch (Exception e) {
					continue;
				}
				handle(message);
			}
		} catch (IOException ignored) {
			// Router closed the connection.
		} finally {
			synchronized (this) {
				if (channel == ch) {
					clientId = null;
				}
			}
			for (CompletableFuture<JsonObject> f : pending.values()) {
				f.completeExceptionally(new IOException("Codex IPC disconnected"));
			}
		}
	}

	private void handle(JsonObject message) throws IOException {
		String type = Json.str(message, "type");
		String requestId = Json.str(message, "requestId");
		if (type == null) {
			return;
		}
		switch (type) {
			case "response" -> {
				CompletableFuture<JsonObject> f = requestId != null ? pending.get(requestId) : null;
				if (f != null) {
					f.complete(message);
				}
			}
			// The router asks every client whether it can serve a request; answer quickly so we never slow the app down.
			case "client-discovery-request" -> write(Json.object(
					"type", "client-discovery-response",
					"requestId", requestId,
					"response", Json.object("canHandle", false)));
			case "request" -> write(Json.object(
					"type", "response",
					"requestId", requestId,
					"resultType", "error",
					"error", "no-handler-for-request"));
			default -> {
			}
		}
	}

	private static void readFully(SocketChannel ch, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (ch.read(buffer) < 0) {
				throw new IOException("EOF");
			}
		}
	}

	private synchronized void disconnect() {
		clientId = null;
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// Already closed.
			}
			channel = null;
		}
	}

	@Override
	public void close() {
		disconnect();
	}
}
