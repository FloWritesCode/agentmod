package dev.agentmod.core;

import java.util.List;

/**
 * Something the agent is blocked on that the player can resolve from inside the game,
 * for example a Codex command approval.
 */
public record PendingAction(String id, String prompt, List<Option> options) {
	public record Option(String id, String label, boolean primary) {
	}
}
