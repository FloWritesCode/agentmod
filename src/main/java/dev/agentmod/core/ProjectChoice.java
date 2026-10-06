package dev.agentmod.core;

/**
 * A folder a new agent can be started in, as remembered by one of the agent apps.
 *
 * @param lastUsedAt epoch milliseconds, or 0 when the source only knows the order
 */
public record ProjectChoice(String name, String path, long lastUsedAt) {
}
