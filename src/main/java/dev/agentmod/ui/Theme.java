package dev.agentmod.ui;

import dev.agentmod.core.AgentHub;
import dev.agentmod.core.AgentStatus;
import net.minecraft.client.gui.Font;

final class Theme {
	static final int TEXT = 0xFFE8EAF0;
	static final int TEXT_DIM = 0xFF9AA3B2;
	static final int TEXT_FAINT = 0xFF697282;
	static final int PANEL = 0xE00D1016;
	static final int PANEL_ALT = 0xE0141821;
	static final int CARD = 0xC0111419;
	static final int CARD_HOVER = 0xD8222834;
	static final int CARD_SELECTED = 0xE82A3242;
	static final int DIVIDER = 0x40FFFFFF;
	static final int USER_BUBBLE = 0xFF233047;
	static final int CODE_BG = 0x50000000;
	static final int CODE_TEXT = 0xFFB8D4FF;
	static final int LINK = 0xFF7AB4FF;

	static final int RUNNING = 0xFF4ADE80;
	static final int WAITING = 0xFFFBBF24;
	static final int ERROR = 0xFFF87171;
	static final int UNREAD = 0xFF60A5FA;
	static final int IDLE = 0xFF4B5563;
	static final int OK = 0xFF4ADE80;

	private Theme() {
	}

	static int statusColor(AgentHub.Entry entry) {
		return switch (entry.agent().status()) {
			case RUNNING -> RUNNING;
			case WAITING -> WAITING;
			case ERROR -> ERROR;
			case IDLE -> entry.unread() ? UNREAD : IDLE;
		};
	}

	static String statusLabel(AgentHub.Entry entry) {
		AgentStatus status = entry.agent().status();
		if (status == AgentStatus.IDLE) {
			return entry.unread() ? "Unread" : "Finished";
		}
		return status.label();
	}

	static int withAlpha(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.clamp(alpha, 0f, 1f));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	/** Pulses between 35% and 100% opacity about once a second. */
	static float pulse(long now) {
		return 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(now / 160.0));
	}

	static String ellipsize(Font font, String text, int maxWidth) {
		if (text == null || text.isEmpty() || maxWidth <= 0) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		int ellipsis = font.width("…");
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - ellipsis)).stripTrailing() + "…";
	}
}
