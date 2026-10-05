package dev.agentmod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentmod.codex.CodexBackend;
import dev.agentmod.core.AgentBackend;
import dev.agentmod.core.AgentHub;
import dev.agentmod.core.SeenStore;
import dev.agentmod.cursor.CursorBackend;
import dev.agentmod.ui.AgentScreen;
import dev.agentmod.ui.SidebarHud;
import dev.agentmod.util.Texts;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentModClient implements ClientModInitializer {
	public static final String MOD_ID = "agentmod";
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

	private static AgentModConfig config;
	private static Path configFile;
	private static AgentHub hub;
	private static KeyMapping openKey;
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		configFile = configDir.resolve("agentmod.json");
		config = AgentModConfig.load(configFile);
		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");

		List<AgentBackend> backends = new ArrayList<>();
		if (config.cursor.enabled) {
			backends.add(new CursorBackend(config.cursor));
		}
		if (config.codex.enabled) {
			backends.add(new CodexBackend(config.codex, version));
		}		hub = new AgentHub(config, backends, new SeenStore(configDir.resolve("agentmod-seen.json")));
		hub.start();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "agents"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentmod.open", InputConstants.KEY_J, category));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentmod.toggle_sidebar", InputConstants.KEY_H, category));

		SidebarHud sidebar = new SidebarHud(hub, config, AgentModClient::openKeyName);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "sidebar"), sidebar);

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof ChatScreen) {
				ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !sidebar.handleClick(event.x(), event.y(), event.button()));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(AgentModClient::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> hub.close());
		LOG.info("[AgentMod] Ready with {} backend(s)", backends.size());
	}

	public static AgentHub hub() {
		return hub;
	}

	public static String openKeyName() {
		return openKey != null ? openKey.getTranslatedKeyMessage().getString() : "J";
	}

	public static boolean isOpenKey(KeyEvent event) {
		return openKey != null && openKey.matches(event);
	}

	private static void onEndTick(Minecraft mc) {
		while (openKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				AgentScreen.open(null);
			}
		}
		while (toggleKey.consumeClick()) {
			config.sidebarEnabled = !config.sidebarEnabled;
			config.save(configFile);
		}
		for (AgentHub.Notification notification : hub.drainNotifications()) {
			notify(mc, notification);
		}
	}

	private static void notify(Minecraft mc, AgentHub.Notification notification) {
		if (mc.gui.screen() instanceof AgentScreen screen && screen.isShowing(notification.agent().key())) {
			return;
		}
		String source = notification.agent().source().displayName();
		String title;
		float pitch;
		switch (notification.kind()) {
			case FINISHED -> {
				title = source + " agent finished";
				pitch = 1.2f;
			}
			case FAILED -> {
				title = source + " agent failed";
				pitch = 0.6f;
			}
			default -> {
				title = source + " agent needs you";
				pitch = 1.6f;
			}
		}
		if (config.toastOnFinish) {
			SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, Component.literal(title), Component.literal(Texts.truncate(notification.agent().title(), 48)));
		}
		if (config.soundOnFinish) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), pitch, 0.7f));
		}
	}
}
