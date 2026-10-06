package dev.agentmod.core;

/**
 * @param projectPath absolute path of an existing folder; the agent works there
 * @param mode        one of the backend's {@link AgentBackend#agentModes()} ids, or null for its default
 */
public record NewAgentRequest(String projectPath, String prompt, String mode) {
}
