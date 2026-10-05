package dev.agentmod.core;

/**
 * One agent conversation as reported by a backend. Timestamps are epoch milliseconds.
 *
 * @param sourceUnread the source app itself flags this conversation as unread (Cursor does this)
 * @param replyHint    shown under the reply box; explains where a reply goes or why replying is unavailable
 */
public record AgentSummary(
		AgentSource source,
		String id,
		String title,
		String subtitle,
		String project,
		AgentStatus status,
		long updatedAt,
		boolean sourceUnread,
		boolean replyable,
		String replyHint
) {
	public String key() {
		return key(source, id);
	}

	public static String key(AgentSource source, String id) {
		return source.keyPrefix() + ":" + id;
	}
}
