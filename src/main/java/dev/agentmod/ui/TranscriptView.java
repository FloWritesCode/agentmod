package dev.agentmod.ui;

import java.util.ArrayList;
import java.util.List;

import dev.agentmod.core.AgentSource;
import dev.agentmod.core.ChatMessage;
import dev.agentmod.util.Texts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Scrollable chat transcript. Text is wrapped once per content or width change, not every frame. */
final class TranscriptView {
	private static final int LINE = 10;
	private static final int TOOL_RUN_LIMIT = 6;

	private record Row(int y, int height, FormattedCharSequence text, int textX, int color, int bgX1, int bgX2, int bgColor) {
	}

	private List<ChatMessage> messages = List.of();
	private AgentSource source = AgentSource.CODEX;
	private boolean truncated;
	private List<Row> rows = List.of();
	private int layoutWidth = -1;
	private boolean dirty = true;
	private int contentHeight;
	private int viewHeight;
	private double scroll;
	private boolean stickToBottom = true;

	void setMessages(List<ChatMessage> newMessages, AgentSource newSource, boolean newTruncated) {
		if (newMessages.equals(messages) && newSource == source && newTruncated == truncated) {
			return;
		}
		stickToBottom = stickToBottom || isAtBottom();
		messages = List.copyOf(newMessages);
		source = newSource;
		truncated = newTruncated;
		dirty = true;
	}

	void reset() {
		messages = List.of();
		rows = List.of();
		dirty = true;
		scroll = 0;
		stickToBottom = true;
	}

	void scrollBy(double pixels) {
		scroll = Math.clamp(scroll + pixels, 0, maxScroll());
		stickToBottom = isAtBottom();
	}

	void scrollToBottom() {
		stickToBottom = true;
	}

	int viewHeight() {
		return viewHeight;
	}

	private double maxScroll() {
		return Math.max(0, contentHeight - viewHeight);
	}

	private boolean isAtBottom() {
		return scroll >= maxScroll() - 2;
	}

	void render(GuiGraphicsExtractor g, Font font, int x, int top, int width, int bottom, long now) {
		viewHeight = Math.max(0, bottom - top);
		if (dirty || width != layoutWidth) {
			layout(font, width, now);
		}
		if (stickToBottom) {
			scroll = maxScroll();
		}
		scroll = Math.clamp(scroll, 0, maxScroll());

		g.enableScissor(x, top, x + width, bottom);
		int offset = top - (int) Math.round(scroll);
		for (Row row : rows) {
			int y = offset + row.y();
			if (y + row.height() < top) {
				continue;
			}
			if (y > bottom) {
				break;
			}
			if (row.bgColor() != 0) {
				g.fill(x + row.bgX1(), y, x + row.bgX2(), y + row.height(), row.bgColor());
			}
			if (row.text() != null) {
				g.text(font, row.text(), x + row.textX(), y + 1, row.color(), false);
			}
		}
		g.disableScissor();

		if (contentHeight > viewHeight && viewHeight > 0) {
			int barHeight = Math.max(12, viewHeight * viewHeight / contentHeight);
			int barY = top + (int) ((viewHeight - barHeight) * (scroll / Math.max(1, maxScroll())));
			g.fill(x + width + 3, barY, x + width + 5, barY + barHeight, 0x60FFFFFF);
		}
	}

	private void layout(Font font, int width, long now) {
		layoutWidth = width;
		dirty = false;
		List<Row> out = new ArrayList<>();
		int y = 2;
		if (truncated) {
			out.add(new Row(y, LINE, seq("Older messages aren't shown here", Theme.TEXT_FAINT), 0, Theme.TEXT_FAINT, 0, 0, 0));
			y += LINE + 6;
		}
		ChatMessage.Role previous = null;
		for (ChatMessage message : collapseToolRuns(messages)) {
			switch (message.role()) {
				case USER -> {
					if (previous != null) {
						y += 8;
					}
					String label = withAge("You", message.timestamp(), now);
					int labelWidth = font.width(label);
					out.add(new Row(y, LINE, seq(label, Theme.TEXT_FAINT), width - labelWidth, Theme.TEXT_FAINT, 0, 0, 0));
					y += LINE + 1;
					int maxWidth = Math.max(40, (int) (width * 0.82f) - 12);
					List<FormattedCharSequence> lines = new ArrayList<>();
					for (String paragraph : message.text().split("\n", -1)) {
						if (paragraph.isBlank()) {
							lines.add(FormattedCharSequence.EMPTY);
						} else {
							lines.addAll(font.split(Component.literal(paragraph.stripTrailing()), maxWidth));
						}
					}
					int bubbleWidth = 0;
					for (FormattedCharSequence line : lines) {
						bubbleWidth = Math.max(bubbleWidth, font.width(line));
					}
					bubbleWidth += 12;
					int bubbleX = width - bubbleWidth;
					out.add(new Row(y, 3, null, 0, 0, bubbleX, width, Theme.USER_BUBBLE));
					y += 3;
					for (FormattedCharSequence line : lines) {
						out.add(new Row(y, LINE, line, bubbleX + 6, Theme.TEXT, bubbleX, width, Theme.USER_BUBBLE));
						y += LINE;
					}
					out.add(new Row(y, 3, null, 0, 0, bubbleX, width, Theme.USER_BUBBLE));
					y += 3;
				}
				case ASSISTANT, TOOL -> {
					boolean continuation = previous == ChatMessage.Role.ASSISTANT || previous == ChatMessage.Role.TOOL;
					if (!continuation) {
						if (previous != null) {
							y += 8;
						}
						out.add(new Row(y, LINE, labelFor(message.timestamp(), now), 0, 0xFFFFFFFF, 0, 0, 0));
						y += LINE + 1;
					}
					if (message.role() == ChatMessage.Role.TOOL) {
						if (previous == ChatMessage.Role.ASSISTANT) {
							y += 3;
						}
						String text = Theme.ellipsize(font, "› " + Texts.oneLine(message.text()), width - 2);
						out.add(new Row(y, LINE, seq(text, Theme.TEXT_FAINT), 2, Theme.TEXT_FAINT, 0, 0, 0));
						y += LINE;
					} else {
						if (continuation) {
							y += 5;
						}
						y = layoutMarkdown(font, message.text(), width, y, out);
					}
				}
				case NOTICE -> {
					y += previous != null ? 6 : 0;
					int color = message.text().startsWith("Turn failed") ? Theme.ERROR : Theme.WAITING;
					for (FormattedCharSequence line : font.split(Component.literal(message.text()), width)) {
						out.add(new Row(y, LINE, line, 0, color, 0, 0, 0));
						y += LINE;
					}
				}
			}
			previous = message.role();
		}
		contentHeight = y + 6;
		rows = out;
	}

	private int layoutMarkdown(Font font, String text, int width, int y, List<Row> out) {
		for (Markdown.Para para : Markdown.parse(text, Theme.TEXT)) {
			if (para.blank()) {
				y += 5;
				continue;
			}
			int indent = para.indent();
			List<FormattedCharSequence> lines = font.split(para.text(), Math.max(20, width - indent - (para.code() ? 4 : 0)));
			boolean first = true;
			for (FormattedCharSequence line : lines) {
				if (para.code()) {
					out.add(new Row(y, LINE, line, indent, Theme.CODE_TEXT, 0, width, Theme.CODE_BG));
				} else {
					if (first && para.prefix() != null) {
						int prefixWidth = font.width(para.prefix());
						out.add(new Row(y, 0, seq(para.prefix(), para.prefixColor()), Math.max(0, indent - prefixWidth - 3), para.prefixColor(), 0, 0, 0));
					}
					out.add(new Row(y, LINE, line, indent, Theme.TEXT, 0, 0, 0));
				}
				y += LINE;
				first = false;
			}
		}
		return y;
	}

	private FormattedCharSequence labelFor(long timestamp, long now) {
		Component label = Component.literal(source.displayName()).withColor(source.color());
		String age = Texts.ago(timestamp, now);
		if (!age.isEmpty()) {
			label = Component.empty().append(label).append(Component.literal(" · " + age).withColor(Theme.TEXT_FAINT));
		}
		return label.getVisualOrderText();
	}

	private static String withAge(String label, long timestamp, long now) {
		String age = Texts.ago(timestamp, now);
		return age.isEmpty() ? label : label + " · " + age;
	}

	private static FormattedCharSequence seq(String text, int color) {
		return Component.literal(text).withColor(color).getVisualOrderText();
	}

	/** Long runs of tool calls are shortened to the first and last few so the conversation stays readable. */
	private static List<ChatMessage> collapseToolRuns(List<ChatMessage> source) {
		List<ChatMessage> out = new ArrayList<>(source.size());
		int i = 0;
		while (i < source.size()) {
			if (source.get(i).role() != ChatMessage.Role.TOOL) {
				out.add(source.get(i++));
				continue;
			}
			int end = i;
			while (end < source.size() && source.get(end).role() == ChatMessage.Role.TOOL) {
				end++;
			}
			int run = end - i;
			if (run <= TOOL_RUN_LIMIT) {
				out.addAll(source.subList(i, end));
			} else {
				out.addAll(source.subList(i, i + 2));
				out.add(ChatMessage.tool("… " + (run - 5) + " more steps", source.get(i + 2).timestamp()));
				out.addAll(source.subList(end - 3, end));
			}
			i = end;
		}
		return out;
	}
}
