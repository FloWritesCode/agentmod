package dev.agentmod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.agentmod.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stored as {@code config/agentmod.json}. Unknown or missing fields fall back to the defaults below.
 */
public final class AgentModConfig {
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");

	/** Show the agent list on the left edge of the HUD. */
	public boolean sidebarEnabled = true;
	public int sidebarWidth = 168;
	/** Scale of the HUD sidebar relative to the GUI scale. */
	public float sidebarScale = 1.0f;
	public int sidebarMaxEntries = 6;
	/** Finished agents stay in the sidebar this long even after you've read them. */
	public int recentMinutes = 20;
	/** Unread agents older than this drop out of the sidebar (they stay in the full window). */
	public int unreadMaxAgeHours = 48;
	/** How far back the full agent window lists conversations. */
	public int windowListDays = 14;
	public int pollIntervalMs = 2500;
	public boolean toastOnFinish = true;
	public boolean soundOnFinish = true;

	public Cursor cursor = new Cursor();
	public Codex codex = new Codex();

	public static final class Cursor {
		public boolean enabled = true;
		/** Override for Cursor's {@code state.vscdb}; empty means auto-detect. */
		public String stateDbPath = "";
		/** Override for the sqlite3 binary; empty means auto-detect. */
		public String sqlitePath = "";
		public int maxAgents = 60;
	}

	public static final class Codex {
		public boolean enabled = true;
		/** Override for the codex binary; empty means prefer the one bundled with the Codex desktop app. */
		public String binaryPath = "";
		/** Also list non-interactive {@code codex exec} runs. */
		public boolean includeExecThreads = false;
		public int maxThreads = 40;
	}

	public static AgentModConfig load(Path file) {
		AgentModConfig config = new AgentModConfig();
		if (Files.isRegularFile(file)) {
			try {
				AgentModConfig loaded = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AgentModConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				LOG.warn("[AgentMod] Ignoring unreadable config {}: {}", file, e.toString());
			}
		}
		if (config.cursor == null) {
			config.cursor = new Cursor();
		}
		if (config.codex == null) {
			config.codex = new Codex();
		}
		config.save(file);
		return config;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, Json.PRETTY.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("[AgentMod] Could not write config {}: {}", file, e.toString());
		}
	}
}
