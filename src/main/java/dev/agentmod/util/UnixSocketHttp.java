package dev.agentmod.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough HTTP/1.1 to POST JSON to a server listening on a Unix domain socket.
 * The JDK's HttpClient can't connect to Unix sockets.
 */
public final class UnixSocketHttp {
	public record Response(int status, String body) {
	}

	private UnixSocketHttp() {
	}

	public static Response postJson(Path socket, String path, Map<String, String> headers, String body, Duration timeout) throws IOException {
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		StringBuilder request = new StringBuilder()
				.append("POST ").append(path).append(" HTTP/1.1\r\n")
				.append("Host: localhost\r\n")
				.append("Content-Type: application/json\r\n")
				.append("Accept: application/json\r\n")
				.append("Connection: close\r\n")
				.append("Content-Length: ").append(payload.length).append("\r\n");
		headers.forEach((k, v) -> request.append(k).append(": ").append(v).append("\r\n"));
		request.append("\r\n");

		long deadline = System.nanoTime() + timeout.toNanos();
		try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
			channel.connect(UnixDomainSocketAddress.of(socket));
			channel.configureBlocking(false);
			try (Selector selector = Selector.open()) {
				writeFully(channel, selector, ByteBuffer.wrap(request.toString().getBytes(StandardCharsets.US_ASCII)), deadline);
				writeFully(channel, selector, ByteBuffer.wrap(payload), deadline);
				byte[] raw = readUntilEof(channel, selector, deadline);
				return parse(raw);
			}
		}
	}

	private static void writeFully(SocketChannel channel, Selector selector, ByteBuffer buffer, long deadline) throws IOException {
		SelectionKey key = channel.register(selector, SelectionKey.OP_WRITE);
		try {
			while (buffer.hasRemaining()) {
				if (channel.write(buffer) == 0) {
					waitFor(selector, deadline);
				}
			}
		} finally {
			key.interestOps(0);
		}
	}

	private static byte[] readUntilEof(SocketChannel channel, Selector selector, long deadline) throws IOException {
		SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
		try {
			while (true) {
				int n = channel.read(buffer);
				if (n < 0) {
					break;
				}
				if (n == 0) {
					waitFor(selector, deadline);
					continue;
				}
				buffer.flip();
				out.write(buffer.array(), 0, buffer.limit());
				buffer.clear();
				if (out.size() > 32 * 1024 * 1024) {
					throw new IOException("Response too large");
				}
			}
		} finally {
			key.interestOps(0);
		}
		return out.toByteArray();
	}

	private static void waitFor(Selector selector, long deadline) throws IOException {
		long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
		if (remainingMs <= 0) {
			throw new IOException("Timed out");
		}
		selector.select(remainingMs);
		selector.selectedKeys().clear();
	}

	static Response parse(byte[] raw) throws IOException {
		int headerEnd = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		if (headerEnd < 0) {
			throw new IOException("Malformed HTTP response");
		}
		String head = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1);
		String[] lines = head.split("\r\n");
		String[] statusParts = lines[0].split(" ");
		if (statusParts.length < 2) {
			throw new IOException("Malformed status line: " + lines[0]);
		}
		int status = Integer.parseInt(statusParts[1]);
		boolean chunked = false;
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i].toLowerCase(Locale.ROOT);
			if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
				chunked = true;
			}
		}
		int bodyStart = headerEnd + 4;
		byte[] body = chunked ? dechunk(raw, bodyStart) : java.util.Arrays.copyOfRange(raw, bodyStart, raw.length);
		return new Response(status, new String(body, StandardCharsets.UTF_8));
	}

	private static byte[] dechunk(byte[] raw, int pos) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		while (pos < raw.length) {
			int lineEnd = indexOf(raw, "\r\n".getBytes(StandardCharsets.US_ASCII), pos);
			if (lineEnd < 0) {
				break;
			}
			String sizeText = new String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
			int semicolon = sizeText.indexOf(';');
			if (semicolon >= 0) {
				sizeText = sizeText.substring(0, semicolon);
			}
			int size = Integer.parseInt(sizeText, 16);
			pos = lineEnd + 2;
			if (size == 0) {
				break;
			}
			if (pos + size > raw.length) {
				throw new IOException("Truncated chunked body");
			}
			out.write(raw, pos, size);
			pos += size + 2;
		}
		return out.toByteArray();
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		return indexOf(haystack, needle, 0);
	}

	private static int indexOf(byte[] haystack, byte[] needle, int from) {
		outer:
		for (int i = from; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
