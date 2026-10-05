package dev.agentmod.core;

public enum AgentStatus {
	RUNNING("Running"),
	WAITING("Needs you"),
	IDLE("Finished"),
	ERROR("Failed");

	private final String label;

	AgentStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public boolean isActive() {
		return this == RUNNING || this == WAITING;
	}
}
