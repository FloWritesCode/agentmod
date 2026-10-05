package dev.agentmod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TextsTest {
	@Test
	void extractsUserQuery() {
		String wrapped = "<user_info>OS: mac</user_info>\n<user_query>\nfix the build\n</user_query>";
		assertEquals("fix the build", Texts.cleanUserText(wrapped));
	}

	@Test
	void stripsContextBlocksWithoutQuery() {
		assertEquals("hello", Texts.cleanUserText("<timestamp>Monday</timestamp>\nhello"));
	}

	@Test
	void formatsAge() {
		long now = 1_800_000_000_000L;
		assertEquals("now", Texts.ago(now - 2_000, now));
		assertEquals("42s", Texts.ago(now - 42_000, now));
		assertEquals("5m", Texts.ago(now - 5 * 60_000, now));
		assertEquals("3h", Texts.ago(now - 3 * 3_600_000, now));
		assertEquals("", Texts.ago(0, now));
	}

	@Test
	void truncatesWithEllipsis() {
		assertEquals("abc…", Texts.truncate("abcdefgh", 4));
		assertEquals("abc", Texts.truncate("abc", 4));
	}

	@Test
	void parsesChunkedHttpResponse() throws Exception {
		String raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
				+ "5\r\n{\"a\":\r\n2\r\n1}\r\n0\r\n\r\n";
		UnixSocketHttp.Response response = UnixSocketHttp.parse(raw.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, response.status());
		assertEquals("{\"a\":1}", response.body());
	}

	@Test
	void parsesContentLengthResponse() throws Exception {
		String raw = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\n\r\nno";
		UnixSocketHttp.Response response = UnixSocketHttp.parse(raw.getBytes(StandardCharsets.UTF_8));
		assertEquals(401, response.status());
		assertEquals("no", response.body());
	}
}
