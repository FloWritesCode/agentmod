package dev.agentmod.core;

public record ReplyResult(boolean ok, String message) {
	public static ReplyResult ok(String message) {
		return new ReplyResult(true, message);
	}

	public static ReplyResult failed(String message) {
		return new ReplyResult(false, message);
	}
}
