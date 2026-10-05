package dev.agentmod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import dev.agentmod.AgentModConfig;
import dev.agentmod.core.AgentHub;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * The agent list pinned to the left edge of the HUD. While the chat is open the cursor is free,
 * so cards become clickable and open the agent window.
 */
public final class SidebarHud implements HudElement {
	private static final int MARGIN = 4;
	private static final int GAP = 2;

	private record Hit(String key, float x1, float y1, float x2, float y2) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	private final AgentHub hub;
	private final AgentModConfig config;
	private final Supplier<String> openKeyName;
	private volatile List<Hit> hits = List.of();
	private volatile Hit headerHit;

	public SidebarHud(AgentHub hub, AgentModConfig config, Supplier<String> openKeyName) {
		this.hub = hub;
		this.config = config;
		this.openKeyName = openKeyName;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		hits = List.of();
		headerHit = null;
		Minecraft mc = Minecraft.getInstance();
		if (!config.sidebarEnabled || mc.gui.hud.isHidden() || mc.gui.hud.getDebugOverlay().showDebugScreen()) {
			return;
		}
		if (mc.gui.screen() instanceof AgentScreen) {
			return;
		}
		AgentHub.Snapshot snapshot = hub.snapshot();
		List<AgentHub.Entry> entries = snapshot.sidebar();
		if (entries.isEmpty()) {
			return;
		}

		Font font = mc.font;
		long now = System.currentTimeMillis();
		float scale = Math.clamp(config.sidebarScale, 0.5f, 2.0f);
		boolean interactive = mc.gui.screen() instanceof ChatScreen;
		double mouseX = -1;
		double mouseY = -1;
		if (interactive) {
			mouseX = mc.mouseHandler.getScaledXPos(mc.getWindow()) / scale;
			mouseY = mc.mouseHandler.getScaledYPos(mc.getWindow()) / scale;
		}

		int width = Math.max(110, config.sidebarWidth);
		int x = MARGIN;
		int y = MARGIN;
		int maxBottom = (int) (g.guiHeight() / scale * (interactive ? 0.5f : 0.62f));

		g.pose().pushMatrix();
		g.pose().scale(scale, scale);

		String header = "Agents";
		StringBuilder counts = new StringBuilder();
		if (snapshot.active() > 0) {
			counts.append(snapshot.active()).append(" active");
		}
		if (snapshot.unread() > 0) {
			if (!counts.isEmpty()) {
				counts.append(" · ");
			}
			counts.append(snapshot.unread()).append(" unread");
		}
		g.fill(x, y, x + width, y + 12, Theme.PANEL);
		g.text(font, header, x + 4, y + 2, Theme.TEXT, false);
		String countText = Theme.ellipsize(font, counts.toString(), width - font.width(header) - 14);
		g.text(font, countText, x + width - 4 - font.width(countText), y + 2, Theme.TEXT_DIM, false);
		headerHit = new Hit(null, x * scale, y * scale, (x + width) * scale, (y + 12) * scale);
		y += 12 + GAP;

		List<Hit> newHits = new ArrayList<>();
		int shown = 0;
		int limit = Math.max(1, config.sidebarMaxEntries);
		for (AgentHub.Entry entry : entries) {
			if (shown >= limit || y + AgentCard.HEIGHT > maxBottom) {
				break;
			}
			boolean hovered = interactive && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + AgentCard.HEIGHT;
			AgentCard.draw(g, font, entry, x, y, width, hovered, false, now);
			newHits.add(new Hit(entry.key(), x * scale, y * scale, (x + width) * scale, (y + AgentCard.HEIGHT) * scale));
			y += AgentCard.HEIGHT + GAP;
			shown++;
		}

		int hidden = entries.size() - shown;
		String hint;
		if (hidden > 0) {
			hint = "+" + hidden + " more · " + openKeyName.get() + " to open";
		} else if (interactive) {
			hint = "Click an agent to open it";
		} else {
			hint = openKeyName.get() + " to open · " + mc.options.keyChat.getTranslatedKeyMessage().getString() + " then click";
		}
		String fitted = Theme.ellipsize(font, hint, width - 8);
		g.fill(x, y, x + font.width(fitted) + 8, y + 11, Theme.withAlpha(Theme.PANEL, 0.7f));
		g.text(font, fitted, x + 4, y + 2, Theme.TEXT_DIM, false);

		g.pose().popMatrix();
		hits = List.copyOf(newHits);
	}

	/** Handles a click while the chat screen is open. Returns true if the click hit the sidebar. */
	public boolean handleClick(double guiX, double guiY, int button) {
		if (button != 0) {
			return false;
		}
		for (Hit hit : hits) {
			if (hit.contains(guiX, guiY)) {
				AgentScreen.open(hit.key());
				return true;
			}
		}
		Hit header = headerHit;
		if (header != null && header.contains(guiX, guiY)) {
			AgentScreen.open(null);
			return true;
		}
		return false;
	}
}
