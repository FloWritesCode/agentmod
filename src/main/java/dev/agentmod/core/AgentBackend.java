package dev.agentmod.core;

import java.util.List;

/**
 * A source of agents. All methods are called from AgentMod's worker threads, never the render thread,
 * so implementations may block on IO.
 */
public interface AgentBackend extends AutoCloseable {
	AgentSource source();

	List<AgentSummary> listAgents() throws Exception;

	Conversation loadConversation(String id) throws Exception;

	ReplyResult sendReply(String id, String text) throws Exception;

	default ReplyResult resolveAction(String id, String actionId, String optionId) throws Exception {
		return ReplyResult.failed("Not supported");
	}

	BackendHealth health();

	/** Lets a backend push "something changed" signals (e.g. a turn finished) to trigger an early refresh. */
	default void setChangeListener(Runnable listener) {
	}

	/** Whether this backend can start new agents right now. Must be cheap: the new-agent form asks every frame. */
	default boolean canStartAgents() {
		return false;
	}

	/** Name of this backend on the new-agent form. */
	default String startLabel() {
		return source().displayName();
	}

	/** One or two sentences on the new-agent form explaining where a new agent runs. */
	default String startHint() {
		return "";
	}

	/** Modes the new-agent form offers, default first. Empty means the backend has no mode choice. */
	default List<AgentMode> agentModes() {
		return List.of();
	}

	/** Starts a new agent in {@code request.projectPath()} and sends it the first prompt. Returns once the turn runs. */
	default StartResult startAgent(NewAgentRequest request) throws Exception {
		return StartResult.failed(source().displayName() + " can't start agents");
	}

	/** Folders the source app knows about. The hub merges and sorts these across backends. */
	default List<ProjectChoice> recentProjects() throws Exception {
		return List.of();
	}

	/** True when starting agents needs a one-time sign-in first ({@link #signIn()}). Must be cheap. */
	default boolean needsSignIn() {
		return false;
	}

	/** Runs the source's sign-in flow. May open a browser and block until the user finishes there. */
	default ReplyResult signIn() throws Exception {
		return ReplyResult.failed("Not supported");
	}

	@Override
	void close();
}
