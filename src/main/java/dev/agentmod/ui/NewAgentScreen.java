package dev.agentmod.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentmod.core.AgentHub;
import dev.agentmod.core.AgentMode;
import dev.agentmod.core.AgentSource;
import dev.agentmod.core.NewAgentRequest;
import dev.agentmod.core.ProjectChoice;
import dev.agentmod.core.ReplyResult;
import dev.agentmod.core.StartResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Starts a new agent: pick Cursor or Codex, a project folder and (for Cursor) a mode, then write the first message. */
public final class NewAgentScreen extends Screen {
	private static final int COLUMN_MAX = 460;
	private static final int ROW_H = 18;
	private static final int CHIP_H = 16;

	private static AgentSource lastSource;
	private static final Map<AgentSource, String> LAST_MODE = new EnumMap<>(AgentSource.class);
	private static String lastFolder = "";
	private static String draft = "";

	private record Hit(String id, int x1, int y1, int x2, int y2) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	private final AgentHub hub;
	private final Screen parent;
	private final List<Hit> sourceHits = new ArrayList<>();
	private final List<Hit> modeHits = new ArrayList<>();
	private final List<Hit> projectHits = new ArrayList<>();

	private List<AgentHub.Launcher> launchers;
	private AgentSource source;
	private List<ProjectChoice> projects = List.of();
	private boolean projectsRequested;
	private boolean projectsLoaded;
	private double projectScroll;
	private String status;
	private int statusColor = Theme.TEXT_DIM;
	private boolean busy;

	private EditBox folderBox;
	private MultiLineEditBox promptBox;
	private Button browseButton;
	private Button startButton;
	private Button signInButton;

	private int x0;
	private int colW;
	private int chipsY;
	private int hintY;
	private int folderY;
	private int listTop;
	private int listBottom;
	private int labelRowY;
	private int promptY;
	private int promptH;
	private int bottomY;

	private NewAgentScreen(AgentHub hub, Screen parent) {
		super(Component.literal("New agent"));
		this.hub = hub;
		this.parent = parent;
		this.launchers = hub.launchers();
		this.source = lastSource;
	}

	public static void open(AgentHub hub, Screen parent) {
		Minecraft.getInstance().gui.setScreen(new NewAgentScreen(hub, parent));
	}

	@Override
	protected void init() {
		colW = Math.min(COLUMN_MAX, width - 24);
		x0 = (width - colW) / 2;
		chipsY = 24;
		hintY = chipsY + CHIP_H + 5;
		folderY = hintY + 24;
		listTop = folderY + 22;
		bottomY = height - 28;
		promptH = Math.clamp(height / 6, 36, 80);
		int minList = ROW_H * 3;
		int spare = bottomY - 6 - promptH - 4 - CHIP_H - 6 - listTop;
		if (spare < minList) {
			promptH = Math.max(24, promptH - (minList - spare));
		}
		promptY = bottomY - 6 - promptH;
		labelRowY = promptY - 4 - CHIP_H;
		listBottom = Math.max(listTop + ROW_H, labelRowY - 6);

		int labelW = font.width("Project") + 8;
		folderBox = new EditBox(font, x0 + labelW, folderY, colW - labelW - 66, 18, Component.literal("Project folder"));
		folderBox.setMaxLength(1024);
		folderBox.setHint(Component.literal("Type to filter projects, or paste a folder path").withColor(Theme.TEXT_FAINT));
		folderBox.setValue(lastFolder);
		folderBox.setResponder(value -> {
			lastFolder = value;
			projectScroll = 0;
		});
		addRenderableWidget(folderBox);

		browseButton = addRenderableWidget(Button.builder(Component.literal("Browse…"), b -> browse())
				.bounds(x0 + colW - 62, folderY - 1, 62, 20)
				.build());

		promptBox = MultiLineEditBox.builder()
				.setX(x0)
				.setY(promptY)
				.setPlaceholder(Component.literal("What should the agent do?  Enter to start, Shift+Enter for a new line").withColor(Theme.TEXT_FAINT))
				.setShowBackground(true)
				.build(font, colW, promptH, Component.literal("First message"));
		promptBox.setValue(draft);
		promptBox.setValueListener(value -> draft = value);
		addRenderableWidget(promptBox);

		startButton = addRenderableWidget(Button.builder(Component.literal("Start agent"), b -> start())
				.bounds(x0 + colW - 80, bottomY, 80, 20)
				.build());
		signInButton = addRenderableWidget(Button.builder(Component.literal("Sign in"), b -> signIn())
				.bounds(x0 + colW - 60, chipsY, 60, CHIP_H)
				.build());
		updateButtons();
		setInitialFocus(folderBox.getValue().isBlank() ? folderBox : promptBox);

		if (!projectsRequested) {
			projectsRequested = true;
			hub.recentProjects().whenComplete((list, error) -> Minecraft.getInstance().execute(() -> {
				projectsLoaded = true;
				projects = list != null ? list : List.of();
				if (folderBox != null && folderBox.getValue().isBlank() && !projects.isEmpty()) {
					folderBox.setValue(abbreviate(projects.getFirst().path()));
				}
			}));
		}
	}

	private AgentHub.Launcher launcher() {
		for (AgentHub.Launcher launcher : launchers) {
			if (launcher.source() == source) {
				return launcher;
			}
		}
		if (launchers.isEmpty()) {
			return null;
		}
		source = launchers.getFirst().source();
		return launchers.getFirst();
	}

	private String mode(AgentHub.Launcher launcher) {
		String chosen = LAST_MODE.get(launcher.source());
		for (AgentMode mode : launcher.modes()) {
			if (mode.id().equals(chosen)) {
				return chosen;
			}
		}
		return launcher.modes().isEmpty() ? null : launcher.modes().getFirst().id();
	}

	@Override
	public void tick() {
		launchers = hub.launchers();
		updateButtons();
	}

	private void updateButtons() {
		AgentHub.Launcher launcher = launcher();
		boolean needsSignIn = launcher != null && launcher.needsSignIn();
		signInButton.visible = needsSignIn;
		signInButton.active = !busy;
		startButton.active = !busy && launcher != null && !needsSignIn;
		browseButton.active = !busy;
	}

	/** The typed folder as an absolute path, if it exists. */
	private String resolvedFolder() {
		String text = folderBox.getValue().strip();
		if (text.isEmpty()) {
			return null;
		}
		String home = System.getProperty("user.home");
		if (text.equals("~")) {
			text = home;
		} else if (text.startsWith("~/")) {
			text = home + text.substring(1);
		}
		try {
			Path path = Path.of(text);
			if (!path.isAbsolute()) {
				return null;
			}
			path = path.normalize();
			return Files.isDirectory(path) ? path.toString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private List<ProjectChoice> filtered() {
		String text = folderBox.getValue().strip().toLowerCase();
		if (text.isEmpty() || text.startsWith("/") || text.startsWith("~")) {
			return projects;
		}
		List<ProjectChoice> matches = new ArrayList<>();
		for (ProjectChoice project : projects) {
			if (project.name().toLowerCase().contains(text) || project.path().toLowerCase().contains(text)) {
				matches.add(project);
			}
		}
		return matches;
	}

	private static String abbreviate(String path) {
		String home = System.getProperty("user.home");
		if (path.equals(home)) {
			return "~";
		}
		return path.startsWith(home + "/") ? "~" + path.substring(home.length()) : path;
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	private void choose(String path) {
		folderBox.setValue(abbreviate(path));
		setFocused(promptBox);
	}

	private void browse() {
		String start = resolvedFolder();
		if (start == null && !projects.isEmpty()) {
			Path parentDir = Path.of(projects.getFirst().path()).getParent();
			start = parentDir != null ? parentDir.toString() : null;
		}
		FolderPicker.pick(minecraft, start, this::choose);
	}

	private void start() {
		AgentHub.Launcher launcher = launcher();
		if (busy || launcher == null) {
			return;
		}
		if (launcher.needsSignIn()) {
			setStatus("Sign in to Cursor first", Theme.WAITING);
			return;
		}
		String folder = resolvedFolder();
		List<ProjectChoice> matches = filtered();
		if (folder == null && matches.size() == 1) {
			choose(matches.getFirst().path());
			folder = resolvedFolder();
		}
		if (folder == null) {
			setStatus(folderBox.getValue().isBlank() ? "Choose a project folder first" : "That folder doesn't exist. Pick a project or Browse…", Theme.ERROR);
			setFocused(folderBox);
			return;
		}
		String prompt = promptBox.getValue().strip();
		if (prompt.isEmpty()) {
			setStatus("Write the first message for the agent", Theme.ERROR);
			setFocused(promptBox);
			return;
		}
		busy = true;
		updateButtons();
		setStatus("Starting " + launcher.label() + " in " + abbreviate(folder) + "…", Theme.TEXT_DIM);
		String chosenFolder = folder;
		hub.startAgent(launcher.source(), new NewAgentRequest(folder, prompt, mode(launcher))).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			busy = false;
			StartResult r = result != null ? result : StartResult.failed(error != null ? AgentHub.rootMessage(error) : "Failed");
			if (r.ok() && r.agent() != null) {
				draft = "";
				lastFolder = abbreviate(chosenFolder);
				if (Minecraft.getInstance().gui.screen() == this) {
					AgentScreen.open(r.agent().key(), r.agent());
				}
			} else {
				setStatus(r.message(), Theme.ERROR);
				updateButtons();
			}
		}));
	}

	private void signIn() {
		AgentHub.Launcher launcher = launcher();
		if (busy || launcher == null) {
			return;
		}
		busy = true;
		updateButtons();
		setStatus("Finish signing in to " + launcher.label() + " in your browser…", Theme.WAITING);
		hub.signIn(launcher.source()).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			busy = false;
			ReplyResult r = result != null ? result : ReplyResult.failed(error != null ? AgentHub.rootMessage(error) : "Failed");
			setStatus(r.ok() ? "Signed in. You can start " + launcher.label() + " agents now." : r.message(), r.ok() ? Theme.OK : Theme.ERROR);
			launchers = hub.launchers();
			updateButtons();
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, Theme.PANEL);
		g.text(font, Component.literal("New agent").withStyle(s -> s.withBold(true)), x0, 10, Theme.TEXT, false);
		String back = "Esc to go back";
		g.text(font, back, x0 + colW - font.width(back), 10, Theme.TEXT_FAINT, false);

		AgentHub.Launcher launcher = launcher();
		drawSources(g, launcher, mouseX, mouseY);
		drawHint(g, launcher);
		g.text(font, "Project", x0, folderY + 5, Theme.TEXT_DIM, false);
		drawProjects(g, launcher, mouseX, mouseY);
		g.text(font, "First message", x0, labelRowY + 4, Theme.TEXT_DIM, false);
		drawStatus(g);
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawModes(g, launcher, mouseX, mouseY);
	}

	private void drawSources(GuiGraphicsExtractor g, AgentHub.Launcher selected, int mouseX, int mouseY) {
		sourceHits.clear();
		if (launchers.isEmpty()) {
			g.text(font, "Neither Cursor's CLI agent nor Codex was found on this computer.", x0, chipsY + 4, Theme.ERROR, false);
			return;
		}
		int x = x0;
		for (AgentHub.Launcher launcher : launchers) {
			x = chip(g, sourceHits, launcher.source().name(), launcher.label(), x, chipsY, launcher == selected, launcher.source().color(), mouseX, mouseY);
		}
	}

	private void drawHint(GuiGraphicsExtractor g, AgentHub.Launcher launcher) {
		if (launcher == null) {
			return;
		}
		boolean signIn = launcher.needsSignIn();
		String text = signIn
				? launcher.label() + "'s CLI agent isn't signed in on this computer. Sign in once in your browser; after that, agents start right from here."
				: launcher.hint();
		List<FormattedCharSequence> lines = font.split(Component.literal(text), colW);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.text(font, lines.get(i), x0, hintY + i * 10, signIn ? Theme.WAITING : Theme.TEXT_FAINT, false);
		}
	}

	private void drawProjects(GuiGraphicsExtractor g, AgentHub.Launcher launcher, int mouseX, int mouseY) {
		List<ProjectChoice> shown = filtered();
		int accent = launcher != null ? launcher.source().color() : Theme.TEXT_DIM;
		g.fill(x0, listTop, x0 + colW, listBottom, Theme.CARD);
		int content = shown.size() * ROW_H;
		projectScroll = Math.clamp(projectScroll, 0, Math.max(0, content - (listBottom - listTop)));
		String selectedPath = resolvedFolder();
		projectHits.clear();
		g.enableScissor(x0, listTop, x0 + colW, listBottom);
		int y = listTop - (int) projectScroll;
		for (ProjectChoice project : shown) {
			if (y + ROW_H > listTop && y < listBottom) {
				boolean selected = project.path().equals(selectedPath);
				boolean hovered = mouseX >= x0 && mouseX < x0 + colW && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + ROW_H, listBottom);
				if (selected || hovered) {
					g.fill(x0, y, x0 + colW, y + ROW_H, selected ? Theme.CARD_SELECTED : Theme.CARD_HOVER);
				}
				if (selected) {
					g.fill(x0, y, x0 + 2, y + ROW_H, accent);
				}
				String name = Theme.ellipsize(font, project.name(), colW / 2 - 12);
				g.text(font, name, x0 + 8, y + 5, Theme.TEXT, false);
				int pathX = x0 + 8 + font.width(name) + 8;
				g.text(font, Theme.ellipsize(font, abbreviate(project.path()), x0 + colW - 8 - pathX), pathX, y + 5, Theme.TEXT_FAINT, false);
				projectHits.add(new Hit(project.path(), x0, Math.max(y, listTop), x0 + colW, Math.min(y + ROW_H, listBottom)));
			}
			y += ROW_H;
		}
		g.disableScissor();
		int viewH = listBottom - listTop;
		if (content > viewH) {
			int thumbH = Math.max(8, viewH * viewH / content);
			int thumbY = listTop + (int) ((viewH - thumbH) * projectScroll / (content - viewH));
			g.fill(x0 + colW - 3, thumbY, x0 + colW - 1, thumbY + thumbH, 0x70FFFFFF);
		}
		if (shown.isEmpty()) {
			String text = !projectsLoaded ? "Looking for projects…"
					: projects.isEmpty() ? "No recent projects yet. Use Browse… or paste a folder path."
					: "No project matches. Paste a full folder path or use Browse…";
			g.centeredText(font, Theme.ellipsize(font, text, colW - 16), x0 + colW / 2, listTop + 6, Theme.TEXT_FAINT);
		}
	}

	private void drawModes(GuiGraphicsExtractor g, AgentHub.Launcher launcher, int mouseX, int mouseY) {
		modeHits.clear();
		if (launcher == null || launcher.modes().isEmpty()) {
			return;
		}
		int total = 0;
		for (AgentMode mode : launcher.modes()) {
			total += chipWidth(mode.label()) + 4;
		}
		int x = x0 + colW - total + 4;
		g.text(font, "Mode", x - font.width("Mode") - 6, labelRowY + 4, Theme.TEXT_DIM, false);
		String selected = mode(launcher);
		for (AgentMode mode : launcher.modes()) {
			x = chip(g, modeHits, mode.id(), mode.label(), x, labelRowY, mode.id().equals(selected), launcher.source().color(), mouseX, mouseY);
			if (modeHits.getLast().contains(mouseX, mouseY)) {
				g.setTooltipForNextFrame(font.split(Component.literal(mode.description()), 200), mouseX, mouseY);
			}
		}
	}

	private int chipWidth(String label) {
		return font.width(label) + 14;
	}

	private void drawStatus(GuiGraphicsExtractor g) {
		String text = status;
		int color = statusColor;
		if (text == null) {
			String folder = resolvedFolder();
			if (folder == null) {
				return;
			}
			text = "Works in " + abbreviate(folder);
			color = Theme.TEXT_FAINT;
		}
		List<FormattedCharSequence> lines = font.split(Component.literal(text), colW - 90);
		int y = lines.size() > 1 ? bottomY + 1 : bottomY + 6;
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.text(font, lines.get(i), x0, y + i * 10, color, false);
		}
	}

	private int chip(GuiGraphicsExtractor g, List<Hit> hits, String id, String label, int x, int y, boolean selected, int accent, int mouseX, int mouseY) {
		int w = chipWidth(label);
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CHIP_H;
		g.fill(x, y, x + w, y + CHIP_H, selected ? Theme.CARD_SELECTED : hovered ? Theme.CARD_HOVER : Theme.CARD);
		if (selected) {
			g.fill(x, y, x + w, y + 1, accent);
			g.fill(x, y + CHIP_H - 1, x + w, y + CHIP_H, accent);
			g.fill(x, y, x + 1, y + CHIP_H, accent);
			g.fill(x + w - 1, y, x + w, y + CHIP_H, accent);
		}
		g.text(font, label, x + 7, y + 4, selected ? Theme.TEXT : Theme.TEXT_DIM, false);
		hits.add(new Hit(id, x, y, x + w, y + CHIP_H));
		return x + w + 4;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && !busy) {
			for (Hit hit : sourceHits) {
				if (hit.contains(event.x(), event.y())) {
					source = AgentSource.valueOf(hit.id());
					lastSource = source;
					status = null;
					updateButtons();
					return true;
				}
			}
			for (Hit hit : modeHits) {
				if (hit.contains(event.x(), event.y())) {
					LAST_MODE.put(source, hit.id());
					return true;
				}
			}
			for (Hit hit : projectHits) {
				if (hit.contains(event.x(), event.y())) {
					choose(hit.id());
					if (doubleClick) {
						start();
					}
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= x0 && mouseX < x0 + colW && mouseY >= listTop && mouseY < listBottom) {
			projectScroll -= scrollY * ROW_H;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (promptBox.isFocused() && event.isConfirmation() && !event.hasShiftDown()) {
			start();
			return true;
		}
		if (folderBox.isFocused() && event.isConfirmation()) {
			List<ProjectChoice> matches = filtered();
			if (resolvedFolder() == null && !matches.isEmpty()) {
				choose(matches.getFirst().path());
			} else {
				setFocused(promptBox);
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (parent != null) {
			minecraft.gui.setScreen(parent);
		} else {
			super.onClose();
		}
	}
}
