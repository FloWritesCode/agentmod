package dev.agentmod.cursor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import dev.agentmod.util.Json;

/**
 * Sessions Cursor's CLI agent saved for ACP clients: {@code <cursor config dir>/acp-sessions/<id>/meta.json}
 * (working folder and title) next to {@code store.db} (the conversation; its mtime is the last activity).
 */
final class AcpSessionStore {
	record Entry(String id, Path cwd, String title, long updatedAt) {
	}

	private record Meta(long modifiedAt, Path cwd, String title) {
	}

	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

	private final Path root;
	private final Map<String, Meta> metaCache = new ConcurrentHashMap<>();

	AcpSessionStore(Path root) {
		this.root = root;
	}

	/** Mirrors the CLI: {@code $CURSOR_CONFIG_DIR}, else {@code $XDG_CONFIG_HOME/cursor}, else {@code ~/.cursor}. */
	static Path defaultRoot() {
		String configDir = System.getenv("CURSOR_CONFIG_DIR");
		if (configDir != null && !configDir.isBlank()) {
			return Path.of(configDir, "acp-sessions");
		}
		String xdg = System.getenv("XDG_CONFIG_HOME");
		if (xdg != null && !xdg.isBlank()) {
			return Path.of(xdg, "cursor", "acp-sessions");
		}
		return Path.of(System.getProperty("user.home"), ".cursor", "acp-sessions");
	}

	Path root() {
		return root;
	}

	/** The newest {@code limit} sessions, newest first. */
	List<Entry> list(int limit) throws IOException {
		List<Entry> entries = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return entries;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
			for (Path dir : dirs) {
				Entry entry = read(dir);
				if (entry != null) {
					entries.add(entry);
				}
			}
		}
		entries.sort(Comparator.comparingLong(Entry::updatedAt).reversed());
		return entries.size() > limit ? new ArrayList<>(entries.subList(0, Math.max(0, limit))) : entries;
	}

	Entry find(String id) {
		return SAFE_ID.matcher(id).matches() ? read(root.resolve(id)) : null;
	}

	/** Last write to the session's conversation store, or 0. */
	long updatedAt(String id) {
		if (!SAFE_ID.matcher(id).matches()) {
			return 0;
		}
		try {
			return Files.getLastModifiedTime(root.resolve(id).resolve("store.db")).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private Entry read(Path dir) {
		String id = dir.getFileName().toString();
		if (!SAFE_ID.matcher(id).matches()) {
			return null;
		}
		Path store = dir.resolve("store.db");
		Path metaFile = dir.resolve("meta.json");
		try {
			if (!Files.isRegularFile(store) || !Files.isRegularFile(metaFile)) {
				return null;
			}
			long metaModified = Files.getLastModifiedTime(metaFile).toMillis();
			Meta meta = metaCache.get(id);
			if (meta == null || meta.modifiedAt() != metaModified) {
				meta = parseMeta(Files.readString(metaFile), metaModified);
				if (meta == null) {
					return null;
				}
				metaCache.put(id, meta);
			}
			return new Entry(id, meta.cwd(), meta.title(), Files.getLastModifiedTime(store).toMillis());
		} catch (Exception e) {
			return null;
		}
	}

	private static Meta parseMeta(String json, long modifiedAt) {
		JsonObject meta = Json.parseObject(json);
		String cwd = Json.str(meta, "cwd");
		if (cwd == null || cwd.isBlank()) {
			return null;
		}
		String title = Json.str(meta, "title");
		return new Meta(modifiedAt, Path.of(cwd), title == null || title.isBlank() ? null : title.strip());
	}
}
