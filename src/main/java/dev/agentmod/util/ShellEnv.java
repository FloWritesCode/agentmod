package dev.agentmod.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The environment child processes should run with.
 *
 * <p>Minecraft started from the launcher inherits launchd's minimal PATH, so tools that agents run
 * (node, git, brew...) would be missing from turns we start. We ask the user's login shell for its PATH once.
 */
public final class ShellEnv {
	private static final Logger LOG = LoggerFactory.getLogger("AgentMod");
	private static final String MARKER = "__AGENTMOD_PATH__";
	private static volatile Map<String, String> cached;

	private ShellEnv() {
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public static Map<String, String> get() {
		Map<String, String> env = cached;
		if (env == null) {
			synchronized (ShellEnv.class) {
				env = cached;
				if (env == null) {
					env = resolve();
					cached = env;
				}
			}
		}
		return env;
	}

	public static List<String> pathEntries() {
		String path = get().getOrDefault("PATH", "");
		List<String> out = new ArrayList<>();
		for (String part : path.split(File.pathSeparator)) {
			if (!part.isBlank()) {
				out.add(part);
			}
		}
		return out;
	}

	/** Finds an executable on the resolved PATH, or null. */
	public static Path which(String name) {
		for (String dir : pathEntries()) {
			Path candidate = Path.of(dir, isWindows() ? name + ".exe" : name);
			if (Files.isExecutable(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private static Map<String, String> resolve() {
		Map<String, String> env = new HashMap<>(System.getenv());
		if (isWindows()) {
			return env;
		}
		String home = System.getProperty("user.home");
		Set<String> entries = new LinkedHashSet<>();
		String shellPath = loginShellPath(env.getOrDefault("SHELL", "/bin/zsh"));
		if (shellPath != null) {
			entries.addAll(List.of(shellPath.split(":")));
		}
		entries.addAll(List.of(env.getOrDefault("PATH", "").split(":")));
		entries.addAll(List.of(home + "/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"));
		entries.removeIf(String::isBlank);
		env.put("PATH", String.join(":", entries));
		return env;
	}

	private static String loginShellPath(String shell) {
		if (!Files.isExecutable(Path.of(shell))) {
			shell = "/bin/zsh";
		}
		try {
			Process process = new ProcessBuilder(shell, "-ilc", "printf '\\n" + MARKER + "%s\\n' \"$PATH\"")
					.redirectErrorStream(true)
					.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
					.start();
			String found = null;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
				String line;
				while ((line = reader.readLine()) != null) {
					int idx = line.indexOf(MARKER);
					if (idx >= 0) {
						found = line.substring(idx + MARKER.length()).trim();
						break;
					}
					if (System.nanoTime() > deadline) {
						break;
					}
				}
			}
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
			return found;
		} catch (Exception e) {
			LOG.warn("[AgentMod] Could not read PATH from login shell {}: {}", shell, e.toString());
			return null;
		}
	}
}
