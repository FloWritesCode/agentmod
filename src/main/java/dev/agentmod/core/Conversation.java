package dev.agentmod.core;

import java.util.List;

/**
 * @param truncated older history exists that wasn't loaded
 */
public record Conversation(List<ChatMessage> messages, List<PendingAction> actions, boolean truncated) {
	public static final Conversation EMPTY = new Conversation(List.of(), List.of(), false);

	public Conversation withActions(List<PendingAction> newActions) {
		return new Conversation(messages, newActions, truncated);
	}
}
