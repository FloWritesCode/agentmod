package dev.agentmod.core;

public enum AgentSource {
	CURSOR("Cursor", 0xFFB9A8FF),
	CODEX("Codex", 0xFF6EE7B7);

	private final String displayName;
	private final int color;

	AgentSource(String displayName, int color) {
		this.displayName = displayName;
		this.color = color;
	}

	public String displayName() {
		return displayName;
	}

	public int color() {
		return color;
	}

	public String keyPrefix() {
		return name().toLowerCase();
	}
}
