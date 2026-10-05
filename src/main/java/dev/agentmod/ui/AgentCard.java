package dev.agentmod.ui;

import dev.agentmod.core.AgentHub;
import dev.agentmod.core.AgentStatus;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.util.Texts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Two-line summary of one agent: source, title and age on top, status and latest activity below. */
final class AgentCard {
	static final int HEIGHT = 24;

	private AgentCard() {
	}

	static void draw(GuiGraphicsExtractor g, Font font, AgentHub.Entry entry, int x, int y, int width, boolean hovered, boolean selected, long now) {
		AgentSummary agent = entry.agent();
		int background = selected ? Theme.CARD_SELECTED : hovered ? Theme.CARD_HOVER : Theme.CARD;
		g.fill(x, y, x + width, y + HEIGHT, background);

		int accent = Theme.statusColor(entry);
		boolean running = agent.status() == AgentStatus.RUNNING;
		if (running || agent.status() == AgentStatus.WAITING || agent.status() == AgentStatus.ERROR || entry.unread()) {
			g.fill(x, y, x + 2, y + HEIGHT, running ? Theme.withAlpha(accent, Theme.pulse(now)) : accent);
		}

		int dotX = x + 6;
		int dotY = y + 5;
		int dotColor = running ? Theme.withAlpha(accent, Theme.pulse(now)) : accent;
		g.fill(dotX, dotY, dotX + 4, dotY + 4, dotColor);

		int textX = x + 14;
		int right = x + width - 4;
		String age = Texts.ago(entry.activityAt(), now);
		int ageWidth = font.width(age);
		g.text(font, age, right - ageWidth, y + 3, Theme.TEXT_FAINT, false);
		String title = Theme.ellipsize(font, agent.title(), right - ageWidth - 4 - textX);
		g.text(font, title, textX, y + 3, entry.unread() || agent.status().isActive() ? Theme.TEXT : Theme.TEXT_DIM, false);

		String detail = agent.subtitle() == null || agent.subtitle().isBlank() ? agent.project() : agent.subtitle();
		int lineY = y + 13;
		int cursor = textX;
		if (agent.status() == AgentStatus.WAITING || agent.status() == AgentStatus.ERROR) {
			String label = Theme.statusLabel(entry);
			g.text(font, label, cursor, lineY, accent, false);
			cursor += font.width(label) + 4;
		}
		String source = agent.source().displayName();
		g.text(font, source, cursor, lineY, Theme.withAlpha(agent.source().color(), 0.85f), false);
		cursor += font.width(source) + 4;
		g.text(font, Theme.ellipsize(font, Texts.oneLine(detail), right - cursor), cursor, lineY, Theme.TEXT_DIM, false);
	}
}
