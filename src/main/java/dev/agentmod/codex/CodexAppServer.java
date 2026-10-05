package dev.agentmod.codex;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentmod.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A private {@code codex app-server} child process, spoken to with newline-delimited JSON-RPC over stdio.
 * It reads the same thread history as the Codex CLI and desktop app.
 */
final class CodexAppServer implements AutoCloseable {
	interface Handler {
		/** Called on the reader thread; must not block. */
		void onNotification(String method, JsonObject params);

		/**
		 * Called on the reader thread for server-to-client requests (approvals). Returns the eventual result,
		 * or null to answer with "not supported".
		 */
		CompletableFuture<JsonElement> onRequest(String method, JsonElement id, JsonObject params);
	}

	static final class RpcException extends IOException {
		RpcException(String message) {
			super(message);
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");
	private static final long RESTART_BACKOFF_MS = 10_000;

	private final List<String> command;
	private final Map<String, String> environment;
	private final Handler handler;
	private final String clientVersion;
	private final AtomicLong ids = new AtomicLong();
	private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final Deque<String> stderrTail = new ArrayDeque<>();
	private final Object writeLock = new Object();

	private Process process;
	private BufferedWriter stdin;
	private long lastStartAttempt;
	private volatile String lastError;
	private volatile String serverVersion;

	CodexAppServer(List<String> command, Map<String, String> environment, String clientVersion, Handler handler) {
		this.command = command;
		this.environment = environment;
		this.clientVersion = clientVersion;
		this.handler = handler;
	}

	String lastError() {
		return lastError;
	}

	String serverVersion() {
		return serverVersion;
	}

	synchronized boolean isRunning() {
		return process != null && process.isAlive();
	}

	synchronized void ensureStarted() throws IOException {
		if (process != null && process.isAlive()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastStartAttempt != 0 && now - lastStartAttempt < RESTART_BACKOFF_MS) {
			throw new IOException(lastError != null ? lastError : "Codex app-server is restarting");
		}
		lastStartAttempt = now;
		failPending("Codex app-server restarted");

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().putAll(environment);
		try {
			process = builder.start();
		} catch (IOException e) {
			lastError = "Could not start " + command.getFirst() + ": " + e.getMessage();
			throw new IOException(lastError, e);
		}
		Process started = process;
		stdin = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
		Thread.ofPlatform().daemon().name("AgentMod-codex-stdout").start(() -> readLoop(started));
		Thread.ofPlatform().daemon().name("AgentMod-codex-stderr").start(() -> drainStderr(started));

		try {
			JsonObject result = send("initialize", Json.object(
					"clientInfo", Json.object("name", "agentmod", "title", "AgentMod (Minecraft)", "version", clientVersion)
			), Duration.ofSeconds(20));
			String userAgent = Json.str(result, "userAgent");
			serverVersion = userAgent;
			notifyServer("initialized", null);
			lastError = null;
		} catch (IOException e) {
			lastError = "Codex app-server didn't start: " + e.getMessage() + stderrSummary();
			started.destroyForcibly();
			throw new IOException(lastError, e);
		}
	}

	JsonObject call(String method, JsonObject params, Duration timeout) throws IOException {
		ensureStarted();
		return send(method, params, timeout);
	}

	private JsonObject send(String method, JsonObject params, Duration timeout) throws IOException {
		long id = ids.incrementAndGet();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		pending.put(id, future);
		JsonObject message = Json.object("id", id, "method", method, "params", params == null ? new JsonObject() : params);
		try {
			write(message);
			JsonObject response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			JsonObject error = Json.obj(response, "error");
			if (error != null) {
				String text = Json.str(error, "message");
				throw new RpcException(text != null ? text : error.toString());
			}
			JsonElement result = response.get("result");
			return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
		} catch (TimeoutException e) {
			throw new IOException("Codex " + method + " timed out");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		} catch (ExecutionException e) {
			throw new IOException(e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
		} finally {
			pending.remove(id);
		}
	}

	void notifyServer(String method, JsonObject params) throws IOException {
		JsonObject message = Json.object("method", method);
		if (params != null) {
			message.add("params", params);
		}
		write(message);
	}

	private void write(JsonObject message) throws IOException {
		synchronized (writeLock) {
			BufferedWriter out = stdin;
			if (out == null) {
				throw new IOException("Codex app-server is not running");
			}
			out.write(Json.GSON.toJson(message));
			out.write('\n');
			out.flush();
		}
	}

	private void respond(JsonElement id, JsonElement result, String error) {
		JsonObject message = new JsonObject();
		message.add("id", id);
		if (error != null) {
			message.add("error", Json.object("code", -32000, "message", error));
		} else {
			message.add("result", result == null ? new JsonObject() : result);
		}
		try {
			write(message);
		} catch (IOException e) {
			LOG.debug("[AgentMod] Failed to answer Codex request: {}", e.toString());
		}
	}

	private void readLoop(Process owner) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.charAt(0) != '{') {
					continue;
				}
				JsonObject message;
				try {
					message = JsonParser.parseString(line).getAsJsonObject();
				} catch (Exception e) {
					continue;
				}
				dispatch(message);
			}
		} catch (IOException ignored) {
			// Process went away; handled below.
		}
		lastError = "Codex app-server exited" + stderrSummary();
		failPending(lastError);
	}

	private void dispatch(JsonObject message) {
		String method = Json.str(message, "method");
		JsonElement id = message.get("id");
		if (method == null) {
			if (id != null && id.isJsonPrimitive()) {
				CompletableFuture<JsonObject> future = pending.get(id.getAsLong());
				if (future != null) {
					future.complete(message);
				}
			}
			return;
		}
		JsonObject params = Json.obj(message, "params");
		if (params == null) {
			params = new JsonObject();
		}
		if (id == null) {
			try {
				handler.onNotification(method, params);
			} catch (Exception e) {
				LOG.debug("[AgentMod] Codex notification handler failed for {}: {}", method, e.toString());
			}
			return;
		}
		CompletableFuture<JsonElement> answer;
		try {
			answer = handler.onRequest(method, id, params);
		} catch (Exception e) {
			answer = null;
		}
		if (answer == null) {
			respond(id, null, "Not supported by AgentMod");
		} else {
			answer.whenComplete((result, error) -> respond(id, result, error != null ? error.getMessage() : null));
		}
	}

	private void drainStderr(Process owner) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(owner.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				synchronized (stderrTail) {
					stderrTail.addLast(line);
					while (stderrTail.size() > 8) {
						stderrTail.removeFirst();
					}
				}
			}
		} catch (IOException ignored) {
			// Process exited.
		}
	}

	private String stderrSummary() {
		synchronized (stderrTail) {
			for (var it = stderrTail.descendingIterator(); it.hasNext(); ) {
				String line = it.next().strip();
				if (!line.isEmpty() && !line.startsWith("WARNING: proceeding")) {
					return " (" + line + ")";
				}
			}
		}
		return "";
	}

	private void failPending(String reason) {
		for (CompletableFuture<JsonObject> future : pending.values()) {
			future.completeExceptionally(new IOException(reason));
		}
		pending.clear();
	}

	@Override
	public synchronized void close() {
		if (process != null) {
			process.destroy();
			try {
				if (!process.waitFor(2, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				process.destroyForcibly();
			}
		}
		synchronized (writeLock) {
			stdin = null;
		}
		failPending("Closed");
	}
}
