package com.cchaha.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** MessageCache 文件缓存行为测试（原子写/上限清理/非法字符文件名） */
public class MessageCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MessageCache cache() throws Exception {
        // 直接用临时根目录作为缓存目录，便于测试观察文件
        return new MessageCache(tmp.getRoot());
    }

    @Test
    public void saveThenLoadRoundTrip() throws Exception {
        MessageCache c = cache();
        c.save("sess-1", "{\"messages\":[]}");
        assertEquals("{\"messages\":[]}", c.load("sess-1"));
    }

    @Test
    public void loadMissingReturnsNull() throws Exception {
        MessageCache c = cache();
        assertNull(c.load("nonexistent"));
    }

    @Test
    public void saveOverwritesPrevious() throws Exception {
        MessageCache c = cache();
        c.save("s1", "old");
        c.save("s1", "new");
        assertEquals("new", c.load("s1"));
    }

    @Test
    public void unsafeSessionIdBecomesUnderscore() throws Exception {
        MessageCache c = cache();
        c.save("a/b:c?d", "data");
        File[] files = tmp.getRoot().listFiles();
        // 文件名不含原始非法字符
        boolean ok = false;
        for (File f : files) {
            if (f.getName().endsWith(".json") && !f.getName().contains("/") && !f.getName().contains("?")) {
                ok = true;
            }
        }
        assertTrue("缓存文件应使用净化后的文件名", ok);
    }

    @Test
    public void pruneKeepsOnlyTenFiles() throws Exception {
        MessageCache c = cache();
        // 交错保存 12 个会话
        for (int i = 0; i < 12; i++) {
            c.save("session-" + (i % 6), "data-" + i); // 覆盖同一批 key 也会累积文件
        }
        for (int i = 0; i < 12; i++) {
            c.save("extra-session-" + i, "x");
        }
        File[] files = tmp.getRoot().listFiles((d, n) -> n.endsWith(".json"));
        assertTrue("缓存文件数应 ≤ 10，实际 " + files.length, files.length <= 10);
    }

    @Test
    public void atomicWriteLeavesNoTmpFiles() throws Exception {
        MessageCache c = cache();
        c.save("s1", "data");
        File[] tmpFiles = tmp.getRoot().listFiles((d, n) -> n.endsWith(".tmp"));
        assertEquals(0, tmpFiles.length);
    }

    @Test
    public void largePayloadSurvives() throws Exception {
        MessageCache c = cache();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 10000; i++) big.append("line").append(i).append('\n');
        c.save("big", big.toString());
        assertEquals(big.toString(), c.load("big"));
    }
}
