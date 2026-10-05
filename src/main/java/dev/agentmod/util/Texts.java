package dev.agentmod.util;

import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Pattern;

public final class Texts {
	private static final Pattern TAG_BLOCK = Pattern.compile("(?s)<(timestamp|system_reminder|attached_files|system_notification|image_files|git_status|open_and_recently_viewed_files|user_info|agent_skills|agent_transcripts|environment_context)[^>]*>.*?</\\1>");
	private static final Pattern USER_QUERY = Pattern.compile("(?s)<user_query>\\s*(.*?)\\s*</user_query>");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private Texts() {
	}

	/** Removes the context blocks Cursor and Codex wrap around user prompts, keeping what the user typed. */
	public static String cleanUserText(String text) {
		if (text == null) {
			return "";
		}
		var query = USER_QUERY.matcher(text);
		if (query.find()) {
			return query.group(1).trim();
		}
		return TAG_BLOCK.matcher(text).replaceAll("").trim();
	}

	public static String oneLine(String text) {
		if (text == null) {
			return "";
		}
		return WHITESPACE.matcher(text).replaceAll(" ").trim();
	}

	public static String firstLine(String text) {
		if (text == null) {
			return "";
		}
		String trimmed = text.strip();
		int nl = trimmed.indexOf('\n');
		return (nl >= 0 ? trimmed.substring(0, nl) : trimmed).strip();
	}

	public static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
	}

	public static String baseName(String path) {
		if (path == null || path.isBlank()) {
			return "";
		}
		Path p = Path.of(path).getFileName();
		return p == null ? path : p.toString();
	}

	public static long parseIsoMillis(String iso) {
		if (iso == null || iso.isBlank()) {
			return 0L;
		}
		try {
			return Instant.parse(iso).toEpochMilli();
		} catch (Exception e) {
			return 0L;
		}
	}

	/** "now", "42s", "5m", "3h", "2d". */
	public static String ago(long epochMillis, long now) {
		if (epochMillis <= 0) {
			return "";
		}
		long seconds = Math.max(0, (now - epochMillis) / 1000);
		if (seconds < 10) {
			return "now";
		}
		if (seconds < 60) {
			return seconds + "s";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + "m";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + "h";
		}
		return (hours / 24) + "d";
	}
}
