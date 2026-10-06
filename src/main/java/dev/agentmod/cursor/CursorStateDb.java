package dev.agentmod.cursor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentmod.util.ShellEnv;

/**
 * Reads Cursor's agent list and conversations from its global {@code state.vscdb}.
 *
 * <p>We shell out to the {@code sqlite3} CLI in read-only mode instead of bundling a JDBC driver.
 * The database is in WAL mode, so readers never block Cursor.
 */
final class CursorStateDb {
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
	private static final Pattern SAFE_ITEM_KEY = Pattern.compile("[A-Za-z0-9_./-]{1,120}");

	private final Path dbPath;
	private final Path sqlite;

	CursorStateDb(Path dbPath, Path sqlite) {
		this.dbPath = dbPath;
		this.sqlite = sqlite;
	}

	static Path defaultDbPath() {
		String home = System.getProperty("user.home");
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support", "Cursor", "User", "globalStorage", "state.vscdb");
		}
		if (os.contains("win")) {
			String appData = System.getenv().getOrDefault("APPDATA", home + "\\AppData\\Roaming");
			return Path.of(appData, "Cursor", "User", "globalStorage", "state.vscdb");
		}
		return Path.of(home, ".config", "Cursor", "User", "globalStorage", "state.vscdb");
	}

	static Path findSqlite(String override) {
		if (override != null && !override.isBlank()) {
			Path p = Path.of(override);
			return Files.isExecutable(p) ? p : null;
		}
		Path system = Path.of("/usr/bin/sqlite3");
		if (Files.isExecutable(system)) {
			return system;
		}
		return ShellEnv.which("sqlite3");
	}

	boolean available() {
		return sqlite != null && Files.isRegularFile(dbPath);
	}

	Path dbPath() {
		return dbPath;
	}

	/** The newest top-level conversations, one row per chat with its header JSON. */
	List<JsonObject> headers(int limit) throws IOException {
		String sql = "SELECT composerId AS id, checkpointAt AS checkpointAt, CAST(value AS TEXT) AS value "
				+ "FROM composerHeaders WHERE IFNULL(isSubagent, 0) = 0 AND IFNULL(isArchived, 0) = 0 "
				+ "ORDER BY MAX(IFNULL(lastUpdatedAt, 0), IFNULL(checkpointAt, 0)) DESC LIMIT " + Math.max(1, limit) + ";";
		return query(sql);
	}

	/** The last {@code limit} message bubbles of a conversation, oldest first, with only the fields we render. */
	List<JsonObject> bubbles(String composerId, int limit) throws IOException {
		if (!SAFE_ID.matcher(composerId).matches()) {
			throw new IOException("Unexpected chat id: " + composerId);
		}
		String v = "CAST(b.value AS TEXT)";
		String sql = "SELECT * FROM (SELECT CAST(h.key AS INTEGER) AS idx, "
				+ "json_extract(" + v + ", '$.type') AS type, "
				+ "json_extract(" + v + ", '$.text') AS text, "
				+ "json_extract(" + v + ", '$.toolFormerData.name') AS tool, "
				+ "substr(json_extract(" + v + ", '$.toolFormerData.params'), 1, 600) AS params, "
				+ "json_extract(" + v + ", '$.toolFormerData.status') AS toolStatus, "
				+ "json_extract(" + v + ", '$.createdAt') AS createdAt "
				+ "FROM cursorDiskKV c, json_each(json_extract(CAST(c.value AS TEXT), '$.fullConversationHeadersOnly')) h "
				+ "LEFT JOIN cursorDiskKV b ON b.key = ('bubbleId:" + composerId + ":' || json_extract(h.value, '$.bubbleId')) "
				+ "WHERE c.key = 'composerData:" + composerId + "' "
				+ "ORDER BY idx DESC LIMIT " + Math.max(1, limit) + ") ORDER BY idx ASC;";
		return query(sql);
	}

	/** Raw values of Cursor's global key-value settings ({@code ItemTable}), one row per key that exists. */
	List<JsonObject> items(String... keys) throws IOException {
		List<String> quoted = new ArrayList<>();
		for (String key : keys) {
			if (!SAFE_ITEM_KEY.matcher(key).matches()) {
				throw new IOException("Unexpected setting key: " + key);
			}
			quoted.add("'" + key + "'");
		}
		return query("SELECT key, CAST(value AS TEXT) AS value FROM ItemTable WHERE key IN (" + String.join(", ", quoted) + ");");
	}

	private List<JsonObject> query(String sql) throws IOException {
		if (!available()) {
			throw new IOException("Cursor database or sqlite3 not found");
		}
		Process process = new ProcessBuilder(sqlite.toString(), "-readonly", "-json", "-cmd", ".timeout 3000", dbPath.toString(), sql)
				.redirectErrorStream(false)
				.start();
		process.getOutputStream().close();
		byte[] out;
		byte[] err;
		try {
			out = readAll(process.getInputStream());
			err = readAll(process.getErrorStream());
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("sqlite3 timed out");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted", e);
		}
		if (process.exitValue() != 0) {
			throw new IOException("sqlite3 failed: " + new String(err, StandardCharsets.UTF_8).trim());
		}
		String text = new String(out, StandardCharsets.UTF_8).trim();
		List<JsonObject> rows = new ArrayList<>();
		if (text.isEmpty()) {
			return rows;
		}
		JsonElement parsed = JsonParser.parseString(text);
		if (parsed instanceof JsonArray array) {
			for (JsonElement e : array) {
				if (e.isJsonObject()) {
					rows.add(e.getAsJsonObject());
				}
			}
		}
		return rows;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		in.transferTo(buffer);
		return buffer.toByteArray();
	}
}
