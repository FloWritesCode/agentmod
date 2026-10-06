package dev.agentmod.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcpSessionStoreTest {
	@TempDir
	Path root;

	private void session(String id, String meta, long storeModified) throws Exception {
		Path dir = Files.createDirectories(root.resolve(id));
		if (meta != null) {
			Files.writeString(dir.resolve("meta.json"), meta);
		}
		if (storeModified > 0) {
			Path store = Files.writeString(dir.resolve("store.db"), "x");
			Files.setLastModifiedTime(store, FileTime.fromMillis(storeModified));
		}
	}

	@Test
	void listsSavedSessionsNewestFirst() throws Exception {
		session("older", "{\"schemaVersion\":1,\"cwd\":\"/work/a\"}", 1_000_000);
		session("newer", "{\"schemaVersion\":1,\"cwd\":\"/work/b\",\"title\":\"Fix login\"}", 2_000_000);
		session("no-store", "{\"schemaVersion\":1,\"cwd\":\"/work/c\"}", 0);
		session("no-cwd", "{\"schemaVersion\":1}", 3_000_000);
		session("broken", "{not json", 3_000_000);
		session("bad id", "{\"schemaVersion\":1,\"cwd\":\"/work/d\"}", 3_000_000);

		AcpSessionStore store = new AcpSessionStore(root);
		List<AcpSessionStore.Entry> entries = store.list(10);
		assertEquals(List.of("newer", "older"), entries.stream().map(AcpSessionStore.Entry::id).toList());
		assertEquals(new AcpSessionStore.Entry("newer", Path.of("/work/b"), "Fix login", 2_000_000), entries.getFirst());
		assertNull(entries.get(1).title());
		assertEquals(List.of("newer"), store.list(1).stream().map(AcpSessionStore.Entry::id).toList());

		assertEquals(Path.of("/work/a"), store.find("older").cwd());
		assertNull(store.find("../older"));
		assertNull(store.find("missing"));
		assertEquals(1_000_000, store.updatedAt("older"));
		assertEquals(0, store.updatedAt("missing"));
	}

	@Test
	void picksUpRenamedSessions() throws Exception {
		session("s", "{\"schemaVersion\":1,\"cwd\":\"/work/a\"}", 1_000_000);
		AcpSessionStore store = new AcpSessionStore(root);
		assertNull(store.find("s").title());
		Path meta = root.resolve("s").resolve("meta.json");
		Files.writeString(meta, "{\"schemaVersion\":1,\"cwd\":\"/work/a\",\"title\":\"Named\"}");
		Files.setLastModifiedTime(meta, FileTime.fromMillis(System.currentTimeMillis() + 5000));
		assertEquals("Named", store.find("s").title());
	}

	@Test
	void missingRootIsEmpty() throws Exception {
		assertEquals(List.of(), new AcpSessionStore(root.resolve("nothing")).list(5));
	}
}
