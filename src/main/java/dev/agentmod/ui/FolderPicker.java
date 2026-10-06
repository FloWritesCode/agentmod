package dev.agentmod.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The system's "choose folder" dialog, through SDL (which Minecraft uses for its window) or a shell fallback. */
final class FolderPicker {
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");
	private static SDL_DialogFileCallback lastCallback;
	private static volatile boolean open;

	private FolderPicker() {
	}

	/**
	 * Shows the dialog. Call on the render thread; {@code onPicked} runs on the render thread and only if the user
	 * chose a folder.
	 */
	static void pick(Minecraft minecraft, String startIn, Consumer<String> onPicked) {
		if (open) {
			return;
		}
		String start = startIn != null && Files.isDirectory(Path.of(startIn)) ? startIn : System.getProperty("user.home");
		try {
			if (lastCallback != null) {
				// Freed here rather than inside the callback, which may still be on the stack when it fires.
				lastCallback.free();
				lastCallback = null;
			}
			SDL_DialogFileCallback callback = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
				String path = null;
				if (fileList != 0L) {
					long first = MemoryUtil.memGetAddress(fileList);
					if (first != 0L) {
						path = MemoryUtil.memUTF8(first);
					}
				}
				open = false;
				String picked = path;
				if (picked != null) {
					minecraft.execute(() -> onPicked.accept(picked));
				}
			});
			lastCallback = callback;
			open = true;
			SDLDialog.SDL_ShowOpenFolderDialog(callback, 0L, minecraft.getWindow().handle(), start, false);
		} catch (Throwable t) {
			open = false;
			LOG.info("[AgentMod] SDL folder dialog unavailable ({}), trying the shell", t.toString());
			pickWithShell(minecraft, start, onPicked);
		}
	}

	private static void pickWithShell(Minecraft minecraft, String start, Consumer<String> onPicked) {
		String os = System.getProperty("os.name", "").toLowerCase();
		List<String> command;
		if (os.contains("mac")) {
			String escaped = start.replace("\\", "\\\\").replace("\"", "\\\"");
			command = List.of("osascript", "-e", "POSIX path of (choose folder with prompt \"Choose a project folder\" default location (POSIX file \"" + escaped + "\"))");
		} else if (os.contains("linux")) {
			command = List.of("zenity", "--file-selection", "--directory", "--filename=" + start + "/");
		} else {
			return;
		}
		open = true;
		Thread.ofPlatform().daemon().name("AgentMod-folder-picker").start(() -> {
			try {
				Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
				process.getOutputStream().close();
				String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
				if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 && !out.isEmpty()) {
					String path = out.length() > 1 && out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
					minecraft.execute(() -> onPicked.accept(path));
				}
			} catch (Exception e) {
				LOG.warn("[AgentMod] Folder dialog failed: {}", e.toString());
			} finally {
				open = false;
			}
		});
	}
}
