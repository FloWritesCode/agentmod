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

	@Override
	void close();
}
