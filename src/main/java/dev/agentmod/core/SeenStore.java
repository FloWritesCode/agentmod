package dev.agentmod.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import dev.agentmod.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers when the player last looked at each agent so finished work can be flagged as unread.
 * Anything that happened before the mod was first installed counts as read.
 */
public final class SeenStore {
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");
	private static final long PRUNE_AFTER_MS = Duration.ofDays(30).toMillis();

	private final Path file;
	private final Map<String, Long> seen = new ConcurrentHashMap<>();
	private volatile long baseline;
	private volatile boolean dirty;

	public SeenStore(Path file) {
		this.file = file;
		load();
	}

	private void load() {
		baseline = System.currentTimeMillis();
		dirty = true;
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonObject root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
			if (root == null) {
				return;
			}
			baseline = Json.lng(root, baseline, "baseline");
			JsonObject entries = Json.obj(root, "seen");
			if (entries != null) {
				for (String key : entries.keySet()) {
					seen.put(key, Json.lng(entries, 0, key));
				}
			}
			dirty = false;
		} catch (Exception e) {
			LOG.warn("[AgentMod] Ignoring unreadable {}: {}", file, e.toString());
		}
	}

	/** Activity after this timestamp is unread. */
	public long readUpTo(String key) {
		return seen.getOrDefault(key, baseline);
	}

	public boolean everOpened(String key) {
		return seen.containsKey(key);
	}

	public void markSeen(String key, long timestamp) {
		Long previous = seen.get(key);
		if (previous == null || previous < timestamp) {
			seen.put(key, timestamp);
			dirty = true;
		}
	}

	public void saveIfDirty() {
		if (!dirty) {
			return;
		}
		dirty = false;
		long cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS;
		seen.values().removeIf(ts -> ts < cutoff);
		JsonObject entries = new JsonObject();
		seen.forEach(entries::addProperty);
		JsonObject root = Json.object("baseline", baseline, "seen", entries);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			dirty = true;
			LOG.warn("[AgentMod] Couldn't save {}: {}", file, e.toString());
		}
	}
}
