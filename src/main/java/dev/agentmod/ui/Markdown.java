package dev.agentmod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Just enough Markdown for agent replies: headings, lists, quotes, code fences, bold, inline code and links.
 */
final class Markdown {
	record Para(Component text, int indent, String prefix, int prefixColor, boolean code, boolean blank) {
		static final Para BLANK = new Para(Component.empty(), 0, null, 0, false, true);
	}

	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
	private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+(.*)$");
	private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
	private static final Pattern RULE = Pattern.compile("^([-*_])(\\s*\\1){2,}$");
	private static final Pattern TASK = Pattern.compile("^\\[([ xX])]\\s+(.*)$");

	private Markdown() {
	}

	static List<Para> parse(String text, int color) {
		List<Para> out = new ArrayList<>();
		boolean inCode = false;
		for (String raw : text.replace("\r", "").split("\n", -1)) {
			String line = raw.replace("\t", "    ").stripTrailing();
			String trimmed = line.strip();
			if (trimmed.startsWith("```")) {
				inCode = !inCode;
				continue;
			}
			if (inCode) {
				out.add(new Para(Component.literal(line.isEmpty() ? " " : line).withColor(Theme.CODE_TEXT), 4, null, 0, true, false));
				continue;
			}
			if (trimmed.isEmpty()) {
				if (!out.isEmpty() && !out.getLast().blank()) {
					out.add(Para.BLANK);
				}
				continue;
			}
			int level = Math.min(4, (line.length() - line.stripLeading().length()) / 2);
			Matcher m;
			if ((m = HEADING.matcher(trimmed)).matches()) {
				out.add(new Para(inline(m.group(2), Theme.TEXT).withStyle(s -> s.withBold(true)), 0, null, 0, false, false));
			} else if (RULE.matcher(trimmed).matches()) {
				out.add(new Para(Component.literal("────────────").withColor(Theme.TEXT_FAINT), 0, null, 0, false, false));
			} else if ((m = BULLET.matcher(trimmed)).matches()) {
				String item = m.group(1);
				String prefix = "•";
				Matcher task = TASK.matcher(item);
				if (task.matches()) {
					prefix = task.group(1).isBlank() ? "☐" : "☑";
					item = task.group(2);
				}
				out.add(new Para(inline(item, color), 9 + level * 8, prefix, Theme.TEXT_DIM, false, false));
			} else if ((m = NUMBERED.matcher(trimmed)).matches()) {
				out.add(new Para(inline(m.group(2), color), 14 + level * 8, m.group(1) + ".", Theme.TEXT_DIM, false, false));
			} else if (trimmed.startsWith(">")) {
				out.add(new Para(inline(trimmed.substring(1).strip(), Theme.TEXT_DIM), 8, "│", Theme.TEXT_FAINT, false, false));
			} else {
				out.add(new Para(inline(trimmed, color), 0, null, 0, false, false));
			}
		}
		while (!out.isEmpty() && out.getLast().blank()) {
			out.removeLast();
		}
		return out;
	}

	static MutableComponent inline(String s, int color) {
		MutableComponent out = Component.empty().withColor(color);
		StringBuilder plain = new StringBuilder();
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '`') {
				int end = s.indexOf('`', i + 1);
				if (end > i + 1) {
					flush(out, plain);
					out.append(Component.literal(s.substring(i + 1, end)).withColor(Theme.CODE_TEXT));
					i = end + 1;
					continue;
				}
			}
			if ((c == '*' || c == '_') && s.startsWith(c == '*' ? "**" : "__", i)) {
				String marker = c == '*' ? "**" : "__";
				int end = s.indexOf(marker, i + 2);
				if (end > i + 2) {
					flush(out, plain);
					out.append(inline(s.substring(i + 2, end), color).withStyle(st -> st.withBold(true)));
					i = end + 2;
					continue;
				}
			}
			if (c == '[') {
				int close = s.indexOf("](", i + 1);
				int paren = close > 0 ? s.indexOf(')', close + 2) : -1;
				if (close > i + 1 && paren > close) {
					flush(out, plain);
					out.append(Component.literal(s.substring(i + 1, close)).withColor(Theme.LINK));
					i = paren + 1;
					continue;
				}
			}
			plain.append(c);
			i++;
		}
		flush(out, plain);
		return out;
	}

	private static void flush(MutableComponent out, StringBuilder plain) {
		if (!plain.isEmpty()) {
			out.append(Component.literal(plain.toString()));
			plain.setLength(0);
		}
	}
}
