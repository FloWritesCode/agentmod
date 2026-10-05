package dev.agentmod.core;

/**
 * A one-line status for a backend, shown at the bottom of the agent list.
 */
public record BackendHealth(AgentSource source, boolean ok, String detail) {
}
