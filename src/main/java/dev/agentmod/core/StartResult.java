package dev.agentmod.core;

/**
 * @param agent the new agent, shown in the agent window until the next poll lists it; null when starting failed
 */
public record StartResult(boolean ok, String message, AgentSummary agent) {
	public static StartResult ok(String message, AgentSummary agent) {
		return new StartResult(true, message, agent);
	}

	public static StartResult failed(String message) {
		return new StartResult(false, message, null);
	}
}
