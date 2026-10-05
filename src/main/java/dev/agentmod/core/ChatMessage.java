package dev.agentmod.core;

public record ChatMessage(Role role, String text, long timestamp) {
	public enum Role {
		USER,
		ASSISTANT,
		/** A one-line summary of a tool call (shell command, file edit, search...). */
		TOOL,
		/** Status information that isn't part of the conversation itself (errors, compaction...). */
		NOTICE
	}

	public static ChatMessage user(String text, long ts) {
		return new ChatMessage(Role.USER, text, ts);
	}

	public static ChatMessage assistant(String text, long ts) {
		return new ChatMessage(Role.ASSISTANT, text, ts);
	}

	public static ChatMessage tool(String text, long ts) {
		return new ChatMessage(Role.TOOL, text, ts);
	}

	public static ChatMessage notice(String text, long ts) {
		return new ChatMessage(Role.NOTICE, text, ts);
	}
}
