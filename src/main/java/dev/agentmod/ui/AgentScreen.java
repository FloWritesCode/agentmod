package dev.agentmod.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentmod.AgentModClient;
import dev.agentmod.core.AgentHub;
import dev.agentmod.core.AgentSource;
import dev.agentmod.core.AgentStatus;
import dev.agentmod.core.AgentSummary;
import dev.agentmod.core.BackendHealth;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.core.Conversation;
import dev.agentmod.core.PendingAction;
import dev.agentmod.core.ReplyResult;
import dev.agentmod.util.Texts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
/** Full agent window: every recent agent on the left, the selected conversation and a reply box on the right. */
public final class AgentScreen extends Screen {
	private static final int LIST_HEADER = 22;
	private static final int HEADER_H = 34;
	private static final int SEND_W = 50;
	private static final int NEW_W = 42;
	private static final long ACTIVE_RELOAD_MS = 1500;
	private static final long IDLE_RELOAD_MS = 6000;
	private static final long ECHO_TTL_MS = 10 * 60_000;

	private static final Map<String, String> DRAFTS = new HashMap<>();
	private static final Map<String, List<ChatMessage>> ECHOES = new HashMap<>();
	private static String lastSelectedKey;

	private record ListHit(String key, int x1, int y1, int x2, int y2) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	private final AgentHub hub;
	private final TranscriptView transcript = new TranscriptView();
	private final List<ListHit> listHits = new ArrayList<>();
	private final List<Button> actionButtons = new ArrayList<>();

	private String selectedKey;
	private AgentSummary selectedSummary;
	private Conversation conversation;
	private String loadError;
	private CompletableFuture<?> inflight;
	private long lastLoadAt;
	private long loadedChangeCounter = -1;

	private MultiLineEditBox input;
	private Button sendButton;
	private Button newButton;
	private PendingAction shownAction;
	private List<FormattedCharSequence> actionLines = List.of();
	private String status;
	private int statusColor = Theme.TEXT_DIM;
	private boolean sending;
	private double listScroll;

	private int listW;
	private int panelX;
	private int panelW;
	private int inputY;
	private int inputH;
	private int actionTop;
	private int transcriptBottom;

	private AgentScreen(AgentHub hub, String initialKey, AgentSummary placeholder) {
		super(Component.literal("Agents"));
		this.hub = hub;
		this.selectedKey = initialKey != null ? initialKey : pickDefault(hub.snapshot());
		this.selectedSummary = placeholder;
	}

	/** Opens the agent window, optionally focused on one agent ({@code null} picks the most relevant one). */
	public static void open(String key) {
		open(key, null);
	}

	/** Opens the agent window on an agent the hub may not list yet (one that was just started). */
	public static void open(String key, AgentSummary placeholder) {
		AgentHub hub = AgentModClient.hub();
		if (hub != null) {
			if (key != null) {
				lastSelectedKey = key;
			}
			Minecraft.getInstance().gui.setScreen(new AgentScreen(hub, key, placeholder));
		}
	}

	public boolean isShowing(String key) {
		return key != null && key.equals(selectedKey);
	}

	private static String pickDefault(AgentHub.Snapshot snapshot) {
		for (AgentHub.Entry entry : snapshot.sidebar()) {
			if (entry.agent().status() == AgentStatus.WAITING || entry.unread()) {
				return entry.key();
			}
		}
		if (lastSelectedKey != null && snapshot.find(lastSelectedKey) != null) {
			return lastSelectedKey;
		}
		if (!snapshot.sidebar().isEmpty()) {
			return snapshot.sidebar().getFirst().key();
		}
		return snapshot.all().isEmpty() ? null : snapshot.all().getFirst().key();
	}

	@Override
	protected void init() {
		listW = width < 420 ? Math.max(110, width / 3) : Math.clamp(width * 3 / 10, 150, 230);
		panelX = listW + 1;
		panelW = width - panelX;
		inputH = Math.clamp(height / 7, 36, 70);
		inputY = height - 25 - inputH;

		input = MultiLineEditBox.builder()
				.setX(panelX + 8)
				.setY(inputY)
				.setPlaceholder(Component.literal("Reply…  Enter to send, Shift+Enter for a new line").withColor(Theme.TEXT_FAINT))
				.setShowBackground(true)
				.build(font, panelW - 16 - SEND_W - 6, inputH, Component.literal("Reply"));
		input.setValue(selectedKey != null ? DRAFTS.getOrDefault(selectedKey, "") : "");
		input.setValueListener(value -> {
			if (selectedKey != null) {
				if (value.isEmpty()) {
					DRAFTS.remove(selectedKey);
				} else {
					DRAFTS.put(selectedKey, value);
				}
			}
		});
		addRenderableWidget(input);

		sendButton = addRenderableWidget(Button.builder(Component.literal("Send"), b -> send())
				.bounds(panelX + panelW - 8 - SEND_W, inputY + inputH - 20, SEND_W, 20)
				.build());

		newButton = addRenderableWidget(Button.builder(Component.literal("+ New"), b -> NewAgentScreen.open(hub, this))
				.bounds(8 + font.width("Agents") + 8, 3, NEW_W, 16)
				.build());
		newButton.active = !hub.launchers().isEmpty();

		actionButtons.clear();
		PendingAction action = shownAction;
		shownAction = null;
		showAction(action);
		setInitialFocus(input);

		if (selectedKey != null) {
			hub.markSeen(selectedKey);
			if (conversation == null) {
				reload();
			}
		}
	}

	private void select(String key) {
		if (key == null || key.equals(selectedKey)) {
			return;
		}
		selectedKey = key;
		lastSelectedKey = key;
		selectedSummary = null;
		conversation = null;
		loadError = null;
		status = null;
		loadedChangeCounter = -1;
		transcript.reset();
		showAction(null);
		input.setValue(DRAFTS.getOrDefault(key, ""));
		hub.markSeen(key);
		reload();
	}

	private void moveSelection(int delta) {
		List<String> keys = orderedKeys(hub.snapshot());
		if (keys.isEmpty()) {
			return;
		}
		int index = keys.indexOf(selectedKey);
		int next = index < 0 ? 0 : Math.clamp(index + delta, 0, keys.size() - 1);
		select(keys.get(next));
	}

	private void reload() {
		if (selectedKey == null || (inflight != null && !inflight.isDone())) {
			return;
		}
		String key = selectedKey;
		long counter = hub.changeCounter();
		lastLoadAt = System.currentTimeMillis();
		inflight = hub.loadConversation(key).whenComplete((loaded, error) -> minecraft.execute(() -> {
			if (!key.equals(selectedKey)) {
				return;
			}
			loadedChangeCounter = counter;
			if (error != null) {
				loadError = AgentHub.rootMessage(error);
				return;
			}
			loadError = null;
			conversation = loaded;
			transcript.setMessages(withEchoes(key, loaded.messages()), sourceOf(key), loaded.truncated());
			showAction(loaded.actions().isEmpty() ? null : loaded.actions().getFirst());
			hub.markSeen(key);
		}));
	}

	private AgentSummary summary() {
		if (selectedKey == null) {
			return null;
		}
		AgentHub.Entry entry = hub.snapshot().find(selectedKey);
		if (entry != null) {
			selectedSummary = entry.agent();
		}
		return selectedSummary;
	}

	private static AgentSource sourceOf(String key) {
		AgentSource source = AgentSource.fromKey(key);
		return source != null ? source : AgentSource.CODEX;
	}

	/** Sent messages show up immediately, until the backend's history contains them. */
	private static List<ChatMessage> withEchoes(String key, List<ChatMessage> messages) {
		List<ChatMessage> echoes = ECHOES.get(key);
		if (echoes == null || echoes.isEmpty()) {
			return messages;
		}
		long now = System.currentTimeMillis();
		echoes.removeIf(echo -> now - echo.timestamp() > ECHO_TTL_MS || messages.stream().anyMatch(m ->
				m.role() == ChatMessage.Role.USER && m.text().strip().equals(echo.text().strip())));
		if (echoes.isEmpty()) {
			ECHOES.remove(key);
			return messages;
		}
		List<ChatMessage> merged = new ArrayList<>(messages);
		merged.addAll(echoes);
		return merged;
	}

	private void send() {
		AgentSummary agent = summary();
		if (sending || agent == null) {
			return;
		}
		String text = input.getValue().strip();
		if (text.isEmpty()) {
			return;
		}
		if (!agent.replyable()) {
			setStatus(agent.replyHint(), Theme.WAITING);
			return;
		}
		String key = selectedKey;
		sending = true;
		setStatus("Sending…", Theme.TEXT_DIM);
		hub.sendReply(key, text).whenComplete((result, error) -> minecraft.execute(() -> {
			sending = false;
			ReplyResult r = result != null ? result : ReplyResult.failed(error != null ? AgentHub.rootMessage(error) : "Failed");
			if (r.ok()) {
				DRAFTS.remove(key);
				ECHOES.computeIfAbsent(key, k -> new ArrayList<>()).add(ChatMessage.user(text, System.currentTimeMillis()));
				if (key.equals(selectedKey)) {
					input.setValue("");
					if (conversation != null) {
						transcript.setMessages(withEchoes(key, conversation.messages()), sourceOf(key), conversation.truncated());
					}
					transcript.scrollToBottom();
					setStatus(r.message(), Theme.OK);
					loadedChangeCounter = -1;
				}
			} else if (key.equals(selectedKey)) {
				setStatus(r.message(), Theme.ERROR);
			}
		}));
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	private void showAction(PendingAction action) {
		if (shownAction != null && action != null && shownAction.id().equals(action.id()) && !actionButtons.isEmpty()) {
			return;
		}
		for (Button button : actionButtons) {
			removeWidget(button);
		}
		actionButtons.clear();
		shownAction = action;
		if (action != null) {
			for (PendingAction.Option option : action.options()) {
				Component label = option.primary() ? Component.literal(option.label()).withStyle(s -> s.withBold(true)) : Component.literal(option.label());
				Button button = Button.builder(label, b -> resolve(action, option))
						.bounds(0, 0, font.width(label) + 16, 18)
						.build();
				actionButtons.add(addRenderableWidget(button));
			}
		}
		layoutAction();
	}

	private void layoutAction() {
		if (shownAction == null) {
			actionLines = List.of();
			actionTop = inputY;
			transcriptBottom = inputY - 8;
			return;
		}
		List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.literal(shownAction.prompt()), panelW - 32));
		if (lines.size() > 6) {
			lines = new ArrayList<>(lines.subList(0, 6));
		}
		actionLines = lines;
		int height = 6 + lines.size() * 10 + 4 + 18 + 6;
		actionTop = inputY - 6 - height;
		transcriptBottom = actionTop - 6;
		int x = panelX + 14;
		int y = actionTop + 6 + lines.size() * 10 + 4;
		for (Button button : actionButtons) {
			button.setX(x);
			button.setY(y);
			x += button.getWidth() + 4;
		}
	}

	private void resolve(PendingAction action, PendingAction.Option option) {
		String key = selectedKey;
		setStatus("Answering…", Theme.TEXT_DIM);
		showAction(null);
		hub.resolveAction(key, action.id(), option.id()).whenComplete((result, error) -> minecraft.execute(() -> {
			if (!Objects.equals(key, selectedKey)) {
				return;
			}
			ReplyResult r = result != null ? result : ReplyResult.failed(AgentHub.rootMessage(error));
			setStatus(r.ok() ? option.label() + ": sent to the agent" : r.message(), r.ok() ? Theme.OK : Theme.ERROR);
			loadedChangeCounter = -1;
		}));
	}

	@Override
	public void tick() {
		if (selectedKey == null) {
			String key = pickDefault(hub.snapshot());
			if (key != null) {
				select(key);
			}
			return;
		}
		AgentSummary agent = summary();
		long now = System.currentTimeMillis();
		long interval = agent != null && agent.status().isActive() ? ACTIVE_RELOAD_MS : IDLE_RELOAD_MS;
		boolean changed = hub.changeCounter() != loadedChangeCounter;
		if (now - lastLoadAt > interval || (changed && now - lastLoadAt > 400)) {
			reload();
		}
		sendButton.active = !sending;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		long now = System.currentTimeMillis();
		AgentHub.Snapshot snapshot = hub.snapshot();
		g.fill(0, 0, listW, height, Theme.PANEL);
		g.fill(listW, 0, panelX, height, Theme.DIVIDER);
		g.fill(panelX, 0, width, height, Theme.PANEL_ALT);

		drawList(g, snapshot, mouseX, mouseY, now);
		drawConversation(g, now);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private List<String> orderedKeys(AgentHub.Snapshot snapshot) {
		List<String> keys = new ArrayList<>();
		for (List<AgentHub.Entry> group : groups(snapshot)) {
			for (AgentHub.Entry entry : group) {
				keys.add(entry.key());
			}
		}
		return keys;
	}

	private static List<List<AgentHub.Entry>> groups(AgentHub.Snapshot snapshot) {
		List<AgentHub.Entry> active = new ArrayList<>();
		List<AgentHub.Entry> unread = new ArrayList<>();
		List<AgentHub.Entry> recent = new ArrayList<>();
		for (AgentHub.Entry entry : snapshot.all()) {
			if (entry.agent().status().isActive()) {
				active.add(entry);
			} else if (entry.unread()) {
				unread.add(entry);
			} else {
				recent.add(entry);
			}
		}
		return List.of(active, unread, recent);
	}

	private void drawList(GuiGraphicsExtractor g, AgentHub.Snapshot snapshot, int mouseX, int mouseY, long now) {
		g.text(font, "Agents", 8, 8, Theme.TEXT, false);
		String hint = AgentModClient.openKeyName() + " / Esc to close";
		int hintLeft = newButton.getX() + newButton.getWidth() + 6;
		if (font.width(hint) > listW - 6 - hintLeft) {
			hint = "Esc";
		}
		if (font.width(hint) <= listW - 6 - hintLeft) {
			g.text(font, hint, listW - 6 - font.width(hint), 8, Theme.TEXT_FAINT, false);
		}

		List<BackendHealth> health = snapshot.health();
		int healthTop = height - 6 - health.size() * 11;
		g.fill(0, healthTop - 5, listW, healthTop - 4, Theme.DIVIDER);
		for (int i = 0; i < health.size(); i++) {
			BackendHealth h = health.get(i);
			int y = healthTop + i * 11;
			g.fill(8, y + 3, 12, y + 7, h.ok() ? Theme.OK : Theme.WAITING);
			String text = h.source().displayName() + ": " + h.detail();
			String fitted = Theme.ellipsize(font, text, listW - 24);
			g.text(font, fitted, 16, y + 1, Theme.TEXT_DIM, false);
			if (!fitted.equals(text) && mouseX < listW && mouseY >= y && mouseY < y + 11) {
				g.setTooltipForNextFrame(font.split(Component.literal(text), 220), mouseX, mouseY);
			}
		}

		int top = LIST_HEADER;
		int bottom = healthTop - 6;
		String[] titles = {"Active", "Unread", "Recent"};
		List<List<AgentHub.Entry>> groups = groups(snapshot);
		int content = 0;
		for (List<AgentHub.Entry> group : groups) {
			if (!group.isEmpty()) {
				content += 14 + group.size() * (AgentCard.HEIGHT + 2);
			}
		}
		listScroll = Math.clamp(listScroll, 0, Math.max(0, content - (bottom - top)));

		listHits.clear();
		g.enableScissor(0, top, listW, bottom);
		int y = top - (int) listScroll;
		for (int gi = 0; gi < groups.size(); gi++) {
			List<AgentHub.Entry> group = groups.get(gi);
			if (group.isEmpty()) {
				continue;
			}
			g.text(font, titles[gi] + " · " + group.size(), 8, y + 3, Theme.TEXT_FAINT, false);
			y += 14;
			for (AgentHub.Entry entry : group) {
				if (y + AgentCard.HEIGHT >= top && y <= bottom) {
					boolean hovered = mouseX >= 4 && mouseX < listW - 4 && mouseY >= Math.max(y, top) && mouseY < Math.min(y + AgentCard.HEIGHT, bottom);
					AgentCard.draw(g, font, entry, 4, y, listW - 8, hovered, entry.key().equals(selectedKey), now);
					listHits.add(new ListHit(entry.key(), 4, Math.max(y, top), listW - 4, Math.min(y + AgentCard.HEIGHT, bottom)));
				}
				y += AgentCard.HEIGHT + 2;
			}
		}
		g.disableScissor();

		if (content == 0) {
			String empty = snapshot.loaded() ? "No agents yet" : "Looking for agents…";
			g.centeredText(font, empty, listW / 2, top + 20, Theme.TEXT_FAINT);
			if (snapshot.loaded() && newButton.active) {
				g.centeredText(font, Theme.ellipsize(font, "Press + New to start one", listW - 12), listW / 2, top + 32, Theme.TEXT_FAINT);
			}
		}
	}

	private void drawConversation(GuiGraphicsExtractor g, long now) {
		AgentSummary agent = summary();
		int left = panelX + 8;
		int right = width - 10;
		if (agent == null) {
			g.centeredText(font, "Select an agent on the left", panelX + panelW / 2, height / 3, Theme.TEXT_DIM);
			drawStatusLine(g, null);
			return;
		}

		String title = Theme.ellipsize(font, agent.title(), right - left);
		g.text(font, Component.literal(title).withStyle(s -> s.withBold(true)), left, 8, Theme.TEXT, false);
		int x = left;
		x = meta(g, agent.source().displayName(), x, agent.source().color());
		if (agent.project() != null && !agent.project().isBlank()) {
			x = meta(g, " · " + agent.project(), x, Theme.TEXT_DIM);
		}
		AgentHub.Entry entry = hub.snapshot().find(agent.key());
		String statusLabel = entry != null ? Theme.statusLabel(entry) : agent.status().label();
		int color = entry != null ? Theme.statusColor(entry) : Theme.TEXT_DIM;
		if (color == Theme.IDLE || color == Theme.UNREAD) {
			color = Theme.TEXT_DIM;
		}
		x = meta(g, " · ", x, Theme.TEXT_FAINT);
		x = meta(g, agent.status() == AgentStatus.IDLE ? "Finished" : statusLabel, x, agent.status() == AgentStatus.RUNNING ? Theme.withAlpha(color, Theme.pulse(now)) : color);
		String age = Texts.ago(entry != null ? entry.activityAt() : agent.updatedAt(), now);
		if (!age.isEmpty()) {
			meta(g, " · " + (age.equals("now") ? "just now" : age + " ago"), x, Theme.TEXT_FAINT);
		}
		g.fill(panelX, HEADER_H - 1, width, HEADER_H, Theme.DIVIDER);

		int transcriptTop = HEADER_H + 4;
		if (conversation == null) {
			String text = loadError != null ? "Couldn't load history: " + loadError : "Loading…";
			int lineY = transcriptTop + 12;
			for (FormattedCharSequence line : font.split(Component.literal(text), right - left)) {
				g.centeredText(font, line, panelX + panelW / 2, lineY, loadError != null ? Theme.ERROR : Theme.TEXT_DIM);
				lineY += 10;
			}
		} else {
			transcript.render(g, font, left, transcriptTop, right - left - 4, transcriptBottom, now);
			if (conversation.messages().isEmpty() && !ECHOES.containsKey(selectedKey)) {
				g.centeredText(font, "No messages yet", panelX + panelW / 2, transcriptTop + 12, Theme.TEXT_FAINT);
			}
		}

		if (shownAction != null) {
			g.fill(panelX + 6, actionTop, width - 6, inputY - 6, 0x30FBBF24);
			g.fill(panelX + 6, actionTop, panelX + 8, inputY - 6, Theme.WAITING);
			int lineY = actionTop + 6;
			for (FormattedCharSequence line : actionLines) {
				g.text(font, line, panelX + 14, lineY, Theme.TEXT, false);
				lineY += 10;
			}
		}
		drawStatusLine(g, agent);
	}

	private int meta(GuiGraphicsExtractor g, String text, int x, int color) {
		int available = width - 10 - x;
		if (available <= 0) {
			return x;
		}
		String fitted = Theme.ellipsize(font, text, available);
		g.text(font, fitted, x, 21, color, false);
		return x + font.width(fitted);
	}

	private void drawStatusLine(GuiGraphicsExtractor g, AgentSummary agent) {
		String text = status != null ? status : agent != null ? agent.replyHint() : "";
		int color = status != null ? statusColor : Theme.TEXT_FAINT;
		if (text == null || text.isBlank()) {
			return;
		}
		List<FormattedCharSequence> lines = font.split(Component.literal(text), panelW - 16);
		int y = inputY + inputH + 3;
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.text(font, lines.get(i), panelX + 8, y + i * 10, color, false);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = input != null && input.isFocused();
		int key = event.key();
		if (typing && event.isConfirmation() && !event.hasShiftDown()) {
			send();
			return true;
		}
		if (key == InputConstants.KEY_N && event.hasControlDownWithQuirk() && newButton.active) {
			NewAgentScreen.open(hub, this);
			return true;
		}
		if (key == InputConstants.KEY_PAGEUP || key == InputConstants.KEY_PAGEDOWN) {
			int page = Math.max(20, transcript.viewHeight() - 20);
			transcript.scrollBy(key == InputConstants.KEY_PAGEUP ? -page : page);
			return true;
		}
		if (!typing) {
			if (AgentModClient.isOpenKey(event)) {
				onClose();
				return true;
			}
			if (event.isUp()) {
				moveSelection(-1);
				return true;
			}
			if (event.isDown()) {
				moveSelection(1);
				return true;
			}
			if (event.isCopy()) {
				copyLastReply();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private void copyLastReply() {
		if (conversation == null) {
			return;
		}
		List<ChatMessage> messages = conversation.messages();
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i).role() == ChatMessage.Role.ASSISTANT) {
				minecraft.keyboardHandler.setClipboard(messages.get(i).text());
				setStatus("Copied the last reply", Theme.OK);
				return;
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() < listW && event.y() >= LIST_HEADER) {
			for (ListHit hit : listHits) {
				if (hit.contains(event.x(), event.y())) {
					select(hit.key());
					return true;
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX < listW) {
			listScroll -= scrollY * 20;
			return true;
		}
		if (mouseY >= HEADER_H && mouseY < transcriptBottom) {
			transcript.scrollBy(-scrollY * 30);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (selectedKey != null) {
			lastSelectedKey = selectedKey;
			hub.markSeen(selectedKey);
		}
		super.onClose();
	}
}
