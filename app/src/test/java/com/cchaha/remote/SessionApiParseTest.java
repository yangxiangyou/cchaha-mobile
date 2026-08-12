package com.cchaha.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** SessionApi 消息解析（API 契约核心路径）回归测试 */
public class SessionApiParseTest {

    private static final String TS = "2026-08-12T10:00:00Z";

    @Test
    public void parseMessages_textAndThinkingBlocks() throws Exception {
        String json = "{\"messages\":["
                + "{\"id\":\"m1\",\"type\":\"assistant\",\"timestamp\":\"" + TS + "\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"hello\"},"
                + "{\"type\":\"thinking\",\"thinking\":\"let me think\"}]},"
                + "{\"id\":\"m2\",\"type\":\"user\",\"timestamp\":\"" + TS + "\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"hi\"}]}"
                + "]}";
        List<SessionApi.Message> msgs = SessionApi.parseMessages(json);
        assertEquals(2, msgs.size());
        SessionApi.Message m1 = msgs.get(0);
        assertEquals("m1", m1.id);
        assertEquals(2, m1.blocks.size());
        assertEquals("text", m1.blocks.get(0).type);
        assertEquals("hello", m1.blocks.get(0).text);
        assertEquals("thinking", m1.blocks.get(1).type);
        assertEquals("let me think", m1.blocks.get(1).text);
        assertTrue(m1.timestampMs > 0);
    }

    @Test
    public void parseMessages_toolUseWithNameAndNestedResult() throws Exception {
        String json = "{\"messages\":[{\"id\":\"t1\",\"type\":\"assistant\",\"timestamp\":\""
                + TS + "\",\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}},"
                + "{\"type\":\"tool_result\",\"content\":[{\"type\":\"text\",\"text\":\"out1\"},"
                + "{\"type\":\"text\",\"text\":\"out2\"}]}"
                + "]}]}";
        List<SessionApi.Message> msgs = SessionApi.parseMessages(json);
        SessionApi.Message m = msgs.get(0);
        assertEquals(2, m.blocks.size());
        assertEquals("tool_use", m.blocks.get(0).type);
        assertEquals("Bash", m.blocks.get(0).toolName);
        assertEquals("tool_result", m.blocks.get(1).type);
        assertEquals("out1\nout2", m.blocks.get(1).text); // 嵌套 content 递归拼接
    }

    @Test
    public void parseMessages_imageAndFilePlaceholders() throws Exception {
        String json = "{\"messages\":[{\"id\":\"i1\",\"type\":\"assistant\",\"timestamp\":\""
                + TS + "\",\"content\":["
                + "{\"type\":\"image\",\"url\":\"x\"},"
                + "{\"type\":\"file\",\"name\":\"a.txt\"}"
                + "]}]}";
        List<SessionApi.Message> msgs = SessionApi.parseMessages(json);
        SessionApi.Message m = msgs.get(0);
        assertEquals(2, m.blocks.size());
        assertEquals("image", m.blocks.get(0).type);
        assertEquals("file", m.blocks.get(1).type);
    }

    @Test
    public void parseMessages_emptyContentFallsBackToLegacyText() throws Exception {
        String json = "{\"messages\":[{\"id\":\"l1\",\"type\":\"user\",\"timestamp\":\""
                + TS + "\",\"text\":\"legacy content\"}]}";
        List<SessionApi.Message> msgs = SessionApi.parseMessages(json);
        SessionApi.Message m = msgs.get(0);
        assertTrue(m.blocks.isEmpty());
        assertEquals("legacy content", m.text);
    }

    @Test
    public void parseMessages_emptyTextGetsPlaceholder() throws Exception {
        String json = "{\"messages\":[{\"id\":\"e1\",\"type\":\"user\",\"timestamp\":\""
                + TS + "\",\"content\":[]}]}";
        List<SessionApi.Message> msgs = SessionApi.parseMessages(json);
        assertEquals("(无文本内容)", msgs.get(0).text);
    }

    @Test
    public void parseMessages_skipsEmptyIds() throws Exception {
        String json = "{\"messages\":[{\"id\":\"\",\"type\":\"user\",\"timestamp\":\""
                + TS + "\"},{\"id\":\"ok\",\"type\":\"user\",\"timestamp\":\"" + TS + "\"}]}";
        List<SessionApi.Message> msgs = SessionApi.parseMessages(json);
        assertEquals(1, msgs.size());
        assertEquals("ok", msgs.get(0).id);
    }

    @Test
    public void parseMessages_invalidJsonThrows() {
        try {
            SessionApi.parseMessages("{not json");
            org.junit.Assert.fail("should throw");
        } catch (Exception e) {
            assertNotNull(e);
        }
    }
}
