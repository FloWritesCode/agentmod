package dev.agentmod.core;

public enum AgentSource {
	CURSOR("Cursor", 0xFFB9A8FF),
	/** Agents run by Cursor's CLI agent ({@code cursor-agent acp}), including the ones started from Minecraft. */
	CURSOR_CLI("Cursor CLI", 0xFFB9A8FF),
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

	/** The source of an {@link AgentSummary#key()}, or null. */
	public static AgentSource fromKey(String key) {
		if (key != null) {
			for (AgentSource source : values()) {
				if (key.startsWith(source.keyPrefix() + ":")) {
					return source;
				}
			}
		}
		return null;
	}
}
