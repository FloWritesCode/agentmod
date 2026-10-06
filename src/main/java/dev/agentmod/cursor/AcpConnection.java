package dev.agentmod.cursor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * One {@code cursor-agent acp} child process, spoken to with the Agent Client Protocol
 * (newline-delimited JSON-RPC 2.0 over stdio). The process's working directory is the agent's project.
 */
final class AcpConnection implements AutoCloseable {
	interface Handler {
		/** Called on the reader thread; must not block. */
		void onNotification(String method, JsonObject params);

		/** Called on the reader thread for agent-to-client requests. Returns the eventual result, or null for "not supported". */
		CompletableFuture<JsonElement> onRequest(String method, JsonObject params);

		/** The process exited without {@link #close()} being called. */
		void onExit(String reason);
	}

	static final class AcpException extends IOException {
		private final int code;

		AcpException(int code, String message) {
			super(message);
			this.code = code;
		}

		int code() {
			return code;
		}

		boolean authRequired() {
			String message = getMessage();
			return code == AUTH_REQUIRED && message != null && message.toLowerCase().contains("authentication required");
		}
	}

	static final int PROTOCOL_VERSION = 1;
	private static final int AUTH_REQUIRED = -32000;
	private static final int METHOD_NOT_FOUND = -32601;
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");

	private final Process process;
	private final BufferedWriter stdin;
	private final Handler handler;
	private final AtomicLong ids = new AtomicLong();
	private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final Deque<String> stderrTail = new ArrayDeque<>();
	private final Object writeLock = new Object();
	private volatile boolean closing;

	private AcpConnection(Process process, Handler handler) {
		this.process = process;
		this.handler = handler;
		this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
	}

	/** Starts the process in {@code cwd} and runs the ACP handshake. */
	static AcpConnection open(List<String> command, Path cwd, Map<String, String> environment, String clientVersion, Handler handler) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
		builder.environment().putAll(environment);
		Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			throw new IOException("Could not start " + command.getFirst() + ": " + e.getMessage(), e);
		}
		AcpConnection connection = new AcpConnection(process, handler);
		Thread.ofPlatform().daemon().name("AgentMod-acp-stdout").start(connection::readLoop);
		Thread.ofPlatform().daemon().name("AgentMod-acp-stderr").start(connection::drainStderr);
		try {
			connection.call("initialize", Json.object(
					"protocolVersion", PROTOCOL_VERSION,
					"clientCapabilities", Json.object(
							"fs", Json.object("readTextFile", false, "writeTextFile", false),
							"terminal", false),
					"clientInfo", Json.object("name", "agentmod", "title", "AgentMod (Minecraft)", "version", clientVersion)
			), Duration.ofSeconds(30));
		} catch (IOException e) {
			String detail = connection.stderrSummary();
			connection.close();
			throw new IOException("Cursor's agent didn't start: " + e.getMessage() + detail, e);
		}
		return connection;
	}

	boolean isAlive() {
		return !closing && process.isAlive();
	}

	/** Sends a request and returns its eventual result; long-running requests (prompts) shouldn't block a thread. */
	CompletableFuture<JsonObject> request(String method, JsonObject params) {
		long id = ids.incrementAndGet();
		CompletableFuture<JsonObject> response = new CompletableFuture<>();
		pending.put(id, response);
		try {
			write(Json.object("jsonrpc", "2.0", "id", id, "method", method, "params", params == null ? new JsonObject() : params));
		} catch (IOException e) {
			pending.remove(id);
			return CompletableFuture.failedFuture(e);
		}
		return response.whenComplete((r, e) -> pending.remove(id)).thenApply(message -> {
			JsonObject error = Json.obj(message, "error");
			if (error != null) {
				String text = Json.str(error, "message");
				throw new java.util.concurrent.CompletionException(new AcpException((int) Json.lng(error, 0, "code"), text != null ? text : error.toString()));
			}
			JsonElement result = message.get("result");
			return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
		});
	}

	JsonObject call(String method, JsonObject params, Duration timeout) throws IOException {
		try {
			return request(method, params).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IOException("Cursor's agent didn't answer " + method + " in time");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof IOException io) {
				throw io;
			}
			throw new IOException(cause.getMessage(), cause);
		}
	}

	void notify(String method, JsonObject params) {
		try {
			write(Json.object("jsonrpc", "2.0", "method", method, "params", params == null ? new JsonObject() : params));
		} catch (IOException e) {
			LOG.debug("[AgentMod] ACP notification {} failed: {}", method, e.toString());
		}
	}

	private void write(JsonObject message) throws IOException {
		synchronized (writeLock) {
			if (!process.isAlive()) {
				throw new IOException("Cursor's agent is not running");
			}
			stdin.write(Json.GSON.toJson(message));
			stdin.write('\n');
			stdin.flush();
		}
	}

	private void respond(JsonElement id, JsonElement result, int errorCode, String error) {
		JsonObject message = Json.object("jsonrpc", "2.0");
		message.add("id", id);
		if (error != null) {
			message.add("error", Json.object("code", errorCode, "message", error));
		} else {
			message.add("result", result == null ? new JsonObject() : result);
		}
		try {
			write(message);
		} catch (IOException e) {
			LOG.debug("[AgentMod] Failed to answer Cursor's agent: {}", e.toString());
		}
	}

	private void readLoop() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
		String reason = "Cursor's agent exited" + stderrSummary();
		for (CompletableFuture<JsonObject> future : pending.values()) {
			future.completeExceptionally(new IOException(closing ? "Closed" : reason));
		}
		pending.clear();
		if (!closing) {
			try {
				handler.onExit(reason);
			} catch (Exception e) {
				LOG.debug("[AgentMod] ACP exit handler failed: {}", e.toString());
			}
		}
	}

	private void dispatch(JsonObject message) {
		String method = Json.str(message, "method");
		JsonElement id = message.get("id");
		if (method == null) {
			if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
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
		if (id == null || id.isJsonNull()) {
			try {
				handler.onNotification(method, params);
			} catch (Exception e) {
				LOG.debug("[AgentMod] ACP notification handler failed for {}: {}", method, e.toString());
			}
			return;
		}
		CompletableFuture<JsonElement> answer;
		try {
			answer = handler.onRequest(method, params);
		} catch (Exception e) {
			answer = null;
		}
		if (answer == null) {
			// The CLI falls back to plain permission prompts and local plan files when its extension methods aren't supported.
			respond(id, null, METHOD_NOT_FOUND, "Method not supported");
		} else {
			answer.whenComplete((result, error) -> respond(id, result, -32603, error != null ? error.getMessage() : null));
		}
	}

	private void drainStderr() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
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

	String stderrSummary() {
		synchronized (stderrTail) {
			for (var it = stderrTail.descendingIterator(); it.hasNext(); ) {
				String line = it.next().strip();
				if (!line.isEmpty()) {
					return " (" + line + ")";
				}
			}
		}
		return "";
	}

	@Override
	public void close() {
		closing = true;
		synchronized (writeLock) {
			try {
				stdin.close();
			} catch (IOException ignored) {
				// Already gone.
			}
		}
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
}
