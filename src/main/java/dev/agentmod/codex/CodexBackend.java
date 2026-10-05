package dev.agentmod.codex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentmod.AgentModConfig;
import dev.agentmod.core.AgentBackend;
import dev.agentmod.core.AgentSource;
import dev.agentmod.core.AgentStatus;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.core.BackendHealth;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.core.Conversation;
import dev.agentmod.core.PendingAction;
import dev.agentmod.core.ReplyResult;
import dev.agentmod.util.Json;
import dev.agentmod.util.ShellEnv;
import dev.agentmod.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Codex threads (CLI, IDE extension and desktop app) through a private {@code codex app-server}.
 *
 * <p>Replies go to the Codex desktop app when it has the thread open, so the turn shows up there live.
 * Otherwise the thread is resumed in our own app-server and the turn runs from Minecraft; command and
 * file-change approvals for those turns are surfaced in the agent window.
 */
public final class CodexBackend implements AgentBackend {
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");
	private static final long ACTIVE_WINDOW_MS = Duration.ofHours(6).toMillis();
	private static final long STALE_TURN_MS = Duration.ofHours(3).toMillis();
	private static final int TURN_CHECKS_PER_POLL = 10;
	private static final int HISTORY_TURNS = 25;
	private static final String REPLY_HINT = "Goes to the Codex app if it has this chat open, otherwise Codex runs it from Minecraft";

	private static final List<String> BUNDLED_BINARIES = List.of(
			"/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex",
			"/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex",
			"/Applications/Codex.app/Contents/Resources/codex");

	private final AgentModConfig.Codex config;
	private final CodexAppServer server;
	private final CodexDesktopIpc ipc = new CodexDesktopIpc();
	private final Map<String, ThreadState> states = new ConcurrentHashMap<>();
	private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
	private final ExecutorService background = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("AgentMod-codex-bg").unstarted(r));
	private volatile Runnable changeListener = () -> {
	};
	private volatile BackendHealth health;

	private static final class ThreadState {
		volatile long checkedUpdatedAt = Long.MIN_VALUE;
		volatile long checkedAtMs;
		volatile String turnStatus;
		volatile String turnError;
		volatile String latestText;
		/** The thread is resumed in our app-server. */
		volatile boolean loadedHere;
		/** A turn we started (or the app-server reported) that hasn't completed yet. */
		volatile String activeTurnId;
	}

	private record Approval(String id, String threadId, JsonElement requestId, PendingAction action, CompletableFuture<JsonElement> answer) {
	}

	public CodexBackend(AgentModConfig.Codex config, String clientVersion) {
		this.config = config;
		String binary = resolveBinary(config.binaryPath);
		if (binary == null) {
			server = null;
			health = new BackendHealth(AgentSource.CODEX, false, "Codex CLI not found (set codex.binaryPath in config/agentmod.json)");
		} else {
			server = new CodexAppServer(List.of(binary, "app-server"), ShellEnv.get(), clientVersion, new Handler());
			health = new BackendHealth(AgentSource.CODEX, false, "Starting…");
		}
	}

	static String resolveBinary(String override) {
		List<String> candidates = new ArrayList<>();
		if (override != null && !override.isBlank()) {
			candidates.add(override);
		}
		candidates.addAll(BUNDLED_BINARIES);
		String home = System.getProperty("user.home");
		candidates.add(home + "/.local/bin/codex");
		candidates.add("/opt/homebrew/bin/codex");
		candidates.add("/usr/local/bin/codex");
		for (String candidate : candidates) {
			Path path = Path.of(candidate);
			if (Files.isRegularFile(path) && Files.isExecutable(path)) {
				return candidate;
			}
		}
		Path found = ShellEnv.which(ShellEnv.isWindows() ? "codex.exe" : "codex");
		return found != null ? found.toString() : null;
	}

	@Override
	public AgentSource source() {
		return AgentSource.CODEX;
	}

	@Override
	public BackendHealth health() {
		return health;
	}

	@Override
	public void setChangeListener(Runnable listener) {
		this.changeListener = listener == null ? () -> {
		} : listener;
	}

	private void changed() {
		changeListener.run();
	}

	@Override
	public List<AgentSummary> listAgents() throws Exception {
		if (server == null) {
			return List.of();
		}
		JsonArray sourceKinds = config.includeExecThreads
				? Json.strings("cli", "vscode", "appServer", "exec")
				: Json.strings("cli", "vscode", "appServer");
		JsonObject result;
		try {
			result = server.call("thread/list", Json.object(
					"limit", Math.max(1, config.maxThreads),
					"sortKey", "updated_at",
					"sourceKinds", sourceKinds), Duration.ofSeconds(20));
		} catch (Exception e) {
			health = new BackendHealth(AgentSource.CODEX, false, server.lastError() != null ? server.lastError() : e.getMessage());
			throw e;
		}

		long now = System.currentTimeMillis();
		int budget = TURN_CHECKS_PER_POLL;
		List<AgentSummary> agents = new ArrayList<>();
		JsonArray data = Json.arr(result, "data");
		if (data != null) {
			for (JsonElement element : data) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject thread = element.getAsJsonObject();
				String id = Json.str(thread, "id");
				if (id == null || Json.bool(thread, "ephemeral")) {
					continue;
				}
				long updatedSec = Json.lng(thread, 0, "updatedAt");
				long updatedMs = updatedSec * 1000;
				ThreadState state = states.computeIfAbsent(id, k -> new ThreadState());

				boolean recent = now - updatedMs < ACTIVE_WINDOW_MS;
				boolean changedSinceCheck = state.checkedUpdatedAt != updatedSec;
				boolean recheckRunning = "inProgress".equals(state.turnStatus) && now - state.checkedAtMs > 4000;
				if (recent && (changedSinceCheck || recheckRunning) && budget > 0) {
					budget--;
					refreshLatestTurn(id, state, updatedSec, now);
				}

				AgentStatus status = statusOf(Json.obj(thread, "status"), state, updatedMs, now);
				if (hasApprovals(id)) {
					status = AgentStatus.WAITING;
				}

				String preview = Texts.cleanUserText(Json.str(thread, "preview"));
				String name = Json.str(thread, "name");
				String title = name != null && !name.isBlank() ? name : Texts.truncate(Texts.firstLine(preview), 90);
				if (title.isBlank()) {
					title = "Untitled thread";
				}
				String subtitle;
				if (status == AgentStatus.ERROR && state.turnError != null) {
					subtitle = state.turnError;
				} else if (state.latestText != null) {
					subtitle = state.latestText;
				} else {
					subtitle = Texts.oneLine(preview);
				}

				agents.add(new AgentSummary(
						AgentSource.CODEX,
						id,
						Texts.oneLine(title),
						Texts.truncate(Texts.oneLine(subtitle), 200),
						Texts.baseName(Json.str(thread, "cwd")),
						status,
						updatedMs,
						false,
						true,
						REPLY_HINT));
			}
		}
		String detail = "Connected";
		if (ipc.socketExists()) {
			detail += " · replies reach the Codex app";
		}
		health = new BackendHealth(AgentSource.CODEX, true, detail);
		return agents;
	}

	private static AgentStatus statusOf(JsonObject serverStatus, ThreadState state, long updatedMs, long now) {
		String type = Json.str(serverStatus, "type");
		if ("active".equals(type)) {
			JsonArray flags = Json.arr(serverStatus, "activeFlags");
			return flags != null && !flags.isEmpty() ? AgentStatus.WAITING : AgentStatus.RUNNING;
		}
		if ("systemError".equals(type)) {
			return AgentStatus.ERROR;
		}
		if (state.activeTurnId != null) {
			return AgentStatus.RUNNING;
		}
		String turnStatus = state.turnStatus;
		if ("inProgress".equals(turnStatus)) {
			return now - updatedMs > STALE_TURN_MS ? AgentStatus.IDLE : AgentStatus.RUNNING;
		}
		if ("failed".equals(turnStatus)) {
			return AgentStatus.ERROR;
		}
		return AgentStatus.IDLE;
	}

	private void refreshLatestTurn(String threadId, ThreadState state, long updatedSec, long now) {
		state.checkedUpdatedAt = updatedSec;
		state.checkedAtMs = now;
		try {
			JsonObject result = server.call("thread/turns/list", Json.object(
					"threadId", threadId,
					"limit", 1,
					"itemsView", "summary",
					"sortDirection", "desc"), Duration.ofSeconds(10));
			JsonArray turns = Json.arr(result, "data");
			if (turns == null || turns.isEmpty() || !turns.get(0).isJsonObject()) {
				return;
			}
			JsonObject turn = turns.get(0).getAsJsonObject();
			state.turnStatus = Json.str(turn, "status");
			state.turnError = Json.str(turn, "error", "message");
			String text = latestActivity(Json.arr(turn, "items"));
			if (text != null) {
				state.latestText = text;
			}
		} catch (Exception e) {
			LOG.debug("[AgentMod] Couldn't read latest Codex turn of {}: {}", threadId, e.toString());
		}
	}

	private static String latestActivity(JsonArray items) {
		if (items == null) {
			return null;
		}
		String fallback = null;
		for (int i = items.size() - 1; i >= 0; i--) {
			JsonObject item = items.get(i).isJsonObject() ? items.get(i).getAsJsonObject() : null;
			if (item == null) {
				continue;
			}
			if ("agentMessage".equals(Json.str(item, "type"))) {
				String text = Json.str(item, "text");
				if (text != null && !text.isBlank()) {
					return Texts.truncate(Texts.oneLine(text), 200);
				}
			}
			if (fallback == null) {
				ChatMessage message = toMessage(item, 0);
				if (message != null && message.role() == ChatMessage.Role.TOOL) {
					fallback = message.text();
				}
			}
		}
		return fallback;
	}

	@Override
	public Conversation loadConversation(String id) throws Exception {
		if (server == null) {
			return Conversation.EMPTY;
		}
		JsonObject result = server.call("thread/turns/list", Json.object(
				"threadId", id,
				"limit", HISTORY_TURNS,
				"itemsView", "full",
				"sortDirection", "desc"), Duration.ofSeconds(30));
		JsonArray turns = Json.arr(result, "data");
		List<ChatMessage> messages = new ArrayList<>();
		if (turns != null) {
			for (int t = turns.size() - 1; t >= 0; t--) {
				if (!turns.get(t).isJsonObject()) {
					continue;
				}
				JsonObject turn = turns.get(t).getAsJsonObject();
				long startedAt = Json.lng(turn, 0, "startedAt") * 1000;
				JsonArray items = Json.arr(turn, "items");
				if (items != null) {
					for (JsonElement item : items) {
						if (item.isJsonObject()) {
							ChatMessage message = toMessage(item.getAsJsonObject(), startedAt);
							if (message != null) {
								messages.add(message);
							}
						}
					}
				}
				String status = Json.str(turn, "status");
				long completedAt = Json.lng(turn, startedAt / 1000, "completedAt") * 1000;
				if ("failed".equals(status)) {
					String error = Json.str(turn, "error", "message");
					messages.add(ChatMessage.notice("Turn failed" + (error != null ? ": " + error : ""), completedAt));
				} else if ("interrupted".equals(status)) {
					messages.add(ChatMessage.notice("Interrupted", completedAt));
				}
			}
		}
		boolean truncated = Json.str(result, "nextCursor") != null;
		return new Conversation(messages, actionsFor(id), truncated);
	}

	static ChatMessage toMessage(JsonObject item, long ts) {
		String type = Json.str(item, "type");
		if (type == null) {
			return null;
		}
		return switch (type) {
			case "userMessage" -> {
				String text = Texts.cleanUserText(userText(Json.arr(item, "content")));
				yield text.isBlank() ? null : ChatMessage.user(text, ts);
			}
			case "agentMessage" -> {
				String text = Json.str(item, "text");
				yield text == null || text.isBlank() ? null : ChatMessage.assistant(text.strip(), ts);
			}
			case "commandExecution" -> {
				String command = Texts.truncate(Texts.oneLine(Json.str(item, "command")), 240);
				long exit = Json.lng(item, 0, "exitCode");
				yield ChatMessage.tool("Shell  " + command + (exit != 0 ? "  (exit " + exit + ")" : ""), ts);
			}
			case "fileChange" -> {
				List<String> files = new ArrayList<>();
				JsonArray changes = Json.arr(item, "changes");
				if (changes != null) {
					for (JsonElement change : changes) {
						String path = Json.str(change, "path");
						if (path != null) {
							files.add(Texts.baseName(path));
						}
					}
				}
				yield ChatMessage.tool("Edit  " + (files.isEmpty() ? "files" : String.join(", ", files)), ts);
			}
			case "mcpToolCall" -> ChatMessage.tool("MCP  " + Json.str(item, "server") + " · " + Json.str(item, "tool"), ts);
			case "dynamicToolCall" -> ChatMessage.tool("Tool  " + Json.str(item, "tool"), ts);
			case "webSearch" -> ChatMessage.tool("Web search  " + Texts.oneLine(Json.str(item, "query")), ts);
			case "imageView" -> ChatMessage.tool("Viewed image  " + Texts.baseName(Json.str(item, "path")), ts);
			case "imageGeneration" -> ChatMessage.tool("Generated an image", ts);
			case "collabAgentToolCall" -> ChatMessage.tool("Subagent  " + Texts.truncate(Texts.oneLine(Json.str(item, "prompt")), 160), ts);
			case "plan" -> ChatMessage.tool("Updated plan", ts);
			case "contextCompaction" -> ChatMessage.notice("Context compacted", ts);
			case "enteredReviewMode" -> ChatMessage.notice("Started a review", ts);
			case "exitedReviewMode" -> ChatMessage.notice("Review finished", ts);
			default -> null;
		};
	}

	private static String userText(JsonArray content) {
		if (content == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (JsonElement part : content) {
			String kind = Json.str(part, "type");
			String piece = switch (kind == null ? "" : kind) {
				case "text" -> Json.str(part, "text");
				case "image", "localImage" -> "[image]";
				case "mention", "skill" -> "@" + Json.str(part, "name");
				default -> null;
			};
			if (piece != null && !piece.isBlank()) {
				if (!out.isEmpty()) {
					out.append('\n');
				}
				out.append(piece);
			}
		}
		return out.toString();
	}

	@Override
	public ReplyResult sendReply(String id, String text) throws Exception {
		if (server == null) {
			return ReplyResult.failed("Codex CLI not found");
		}
		String message = text == null ? "" : text.strip();
		if (message.isEmpty()) {
			return ReplyResult.failed("Nothing to send");
		}
		JsonArray input = Json.array(Json.object("type", "text", "text", message, "text_elements", new JsonArray()));
		ThreadState state = states.computeIfAbsent(id, k -> new ThreadState());

		String activeTurn = state.activeTurnId;
		if (activeTurn != null && state.loadedHere) {
			server.call("turn/steer", Json.object("threadId", id, "input", input, "expectedTurnId", activeTurn), Duration.ofSeconds(20));
			changed();
			return ReplyResult.ok("Added to the running turn");
		}

		String owner = ipc.findOwner(id);
		if (owner != null) {
			CodexDesktopIpc.Result result = ipc.request("thread-follower-start-turn", CodexDesktopIpc.VERSION_FOLLOWER_START_TURN,
					Json.object(
							"conversationId", id,
							"turnStart", Json.object(
									"request", Json.object("threadId", id, "input", input),
									"context", new JsonObject())),
					owner, 30_000);
			if (!result.ok()) {
				return ReplyResult.failed("The Codex app didn't accept the message (" + result.error() + ")");
			}
			state.checkedUpdatedAt = Long.MIN_VALUE;
			changed();
			return ReplyResult.ok("Sent to the Codex app");
		}

		if ("inProgress".equals(state.turnStatus) && !state.loadedHere) {
			return ReplyResult.failed("This thread is busy in another Codex session. Wait for it to finish, then reply.");
		}
		if (!state.loadedHere) {
			server.call("thread/resume", Json.object("threadId", id, "excludeTurns", true), Duration.ofSeconds(60));
			state.loadedHere = true;
		}
		JsonObject started = server.call("turn/start", Json.object("threadId", id, "input", input), Duration.ofSeconds(30));
		String turnId = Json.str(started, "turn", "id");
		if (turnId != null) {
			state.activeTurnId = turnId;
		}
		state.turnStatus = "inProgress";
		changed();
		return ReplyResult.ok("Sent. Codex is working on it from Minecraft");
	}

	@Override
	public ReplyResult resolveAction(String id, String actionId, String optionId) {
		Approval approval = approvals.remove(actionId);
		if (approval == null) {
			return ReplyResult.failed("That request was already answered");
		}
		approval.answer().complete(Json.object("decision", optionId));
		changed();
		return ReplyResult.ok("Answered");
	}

	private boolean hasApprovals(String threadId) {
		for (Approval approval : approvals.values()) {
			if (approval.threadId().equals(threadId)) {
				return true;
			}
		}
		return false;
	}

	private List<PendingAction> actionsFor(String threadId) {
		List<PendingAction> actions = new ArrayList<>();
		for (Approval approval : approvals.values()) {
			if (approval.threadId().equals(threadId)) {
				actions.add(approval.action());
			}
		}
		return actions;
	}

	private void dropApprovals(String threadId) {
		approvals.values().removeIf(approval -> {
			if (approval.threadId().equals(threadId)) {
				approval.answer().complete(Json.object("decision", "cancel"));
				return true;
			}
			return false;
		});
	}

	private final class Handler implements CodexAppServer.Handler {
		@Override
		public void onNotification(String method, JsonObject params) {
			String threadId = Json.str(params, "threadId");
			switch (method) {
				case "turn/started" -> {
					if (threadId != null) {
						ThreadState state = states.computeIfAbsent(threadId, k -> new ThreadState());
						state.activeTurnId = Json.str(params, "turn", "id");
						state.turnStatus = "inProgress";
						changed();
					}
				}
				case "turn/completed" -> {
					if (threadId != null) {
						ThreadState state = states.computeIfAbsent(threadId, k -> new ThreadState());
						state.activeTurnId = null;
						state.turnStatus = Json.str(params, "turn", "status");
						state.turnError = Json.str(params, "turn", "error", "message");
						state.checkedUpdatedAt = Long.MIN_VALUE;
						dropApprovals(threadId);
						if (state.loadedHere) {
							background.execute(() -> unsubscribe(threadId, state));
						}
						changed();
					}
				}
				case "serverRequest/resolved" -> {
					JsonElement requestId = params.get("requestId");
					if (requestId != null && approvals.values().removeIf(a -> a.requestId().equals(requestId))) {
						changed();
					}
				}
				case "thread/status/changed", "item/completed" -> changed();
				default -> {
				}
			}
		}

		@Override
		public CompletableFuture<JsonElement> onRequest(String method, JsonElement requestId, JsonObject params) {
			String threadId;
			String prompt;
			List<PendingAction.Option> options;
			switch (method) {
				case "item/commandExecution/requestApproval" -> {
					threadId = Json.str(params, "threadId");
					prompt = "Codex wants to run a command:\n" + Texts.truncate(Json.str(params, "command"), 600)
							+ reasonSuffix(Json.str(params, "reason"));
					options = List.of(
							new PendingAction.Option("accept", "Allow", true),
							new PendingAction.Option("acceptForSession", "Allow for session", false),
							new PendingAction.Option("decline", "Deny", false),
							new PendingAction.Option("cancel", "Deny & stop", false));
				}
				case "item/fileChange/requestApproval" -> {
					threadId = Json.str(params, "threadId");
					String root = Json.str(params, "grantRoot");
					prompt = "Codex wants to change files" + (root != null ? " under " + root : "") + reasonSuffix(Json.str(params, "reason"));
					options = List.of(
							new PendingAction.Option("accept", "Allow", true),
							new PendingAction.Option("acceptForSession", "Allow for session", false),
							new PendingAction.Option("decline", "Deny", false),
							new PendingAction.Option("cancel", "Deny & stop", false));
				}
				case "execCommandApproval" -> {
					threadId = Json.str(params, "conversationId");
					List<String> parts = new ArrayList<>();
					JsonArray command = Json.arr(params, "command");
					if (command != null) {
						command.forEach(part -> parts.add(part.getAsString()));
					}
					prompt = "Codex wants to run a command:\n" + Texts.truncate(String.join(" ", parts), 600) + reasonSuffix(Json.str(params, "reason"));
					options = List.of(new PendingAction.Option("approved", "Allow", true), new PendingAction.Option("denied", "Deny", false));
				}
				case "applyPatchApproval" -> {
					threadId = Json.str(params, "conversationId");
					List<String> files = new ArrayList<>();
					JsonObject changes = Json.obj(params, "fileChanges");
					if (changes != null) {
						changes.keySet().forEach(path -> files.add(Texts.baseName(path)));
					}
					prompt = "Codex wants to edit " + (files.isEmpty() ? "files" : String.join(", ", files)) + reasonSuffix(Json.str(params, "reason"));
					options = List.of(new PendingAction.Option("approved", "Allow", true), new PendingAction.Option("denied", "Deny", false));
				}
				default -> {
					return null;
				}
			}
			if (threadId == null) {
				return null;
			}
			String approvalId = "codex-approval-" + requestId;
			CompletableFuture<JsonElement> answer = new CompletableFuture<>();
			approvals.put(approvalId, new Approval(approvalId, threadId, requestId, new PendingAction(approvalId, prompt, options), answer));
			changed();
			return answer;
		}
	}

	private static String reasonSuffix(String reason) {
		return reason == null || reason.isBlank() ? "" : "\n" + reason.strip();
	}

	private void unsubscribe(String threadId, ThreadState state) {
		if (state.activeTurnId != null) {
			return;
		}
		try {
			server.call("thread/unsubscribe", Json.object("threadId", threadId), Duration.ofSeconds(10));
		} catch (Exception e) {
			LOG.debug("[AgentMod] thread/unsubscribe failed for {}: {}", threadId, e.toString());
		}
		state.loadedHere = false;
	}

	@Override
	public void close() {
		background.shutdownNow();
		for (Approval approval : approvals.values()) {
			approval.answer().complete(Json.object("decision", "cancel"));
		}
		approvals.clear();
		ipc.close();
		if (server != null) {
			server.close();
		}
	}
}
