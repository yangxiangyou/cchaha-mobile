package com.cchaha.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlUtilsTest {

    @Test
    public void normalize_addsHttpScheme() {
        assertEquals("http://192.168.1.20:8080", UrlUtils.normalize("192.168.1.20:8080"));
        assertEquals("http://my-pc:8080", UrlUtils.normalize("my-pc:8080"));
    }

    @Test
    public void normalize_keepsExistingScheme() {
        assertEquals("https://cc.example.com", UrlUtils.normalize("https://cc.example.com"));
        assertEquals("http://a.b/c?token=xyz", UrlUtils.normalize("http://a.b/c?token=xyz"));
    }

    @Test
    public void normalize_trimsWhitespace() {
        assertEquals("http://192.168.1.5", UrlUtils.normalize("  192.168.1.5  "));
    }

    @Test
    public void normalize_rejectsInvalid() {
        assertNull(UrlUtils.normalize(""));
        assertNull(UrlUtils.normalize("   "));
        assertNull(UrlUtils.normalize(null));
        assertNull(UrlUtils.normalize("not a url at all"));
        assertNull(UrlUtils.normalize("haha://x"));       // 非 http scheme
        assertNull(UrlUtils.normalize("ftp://x"));        // 非 http scheme
    }

    @Test
    public void isUsable_acceptsHttpOnly() {
        assertTrue(UrlUtils.isUsable("http://192.168.1.20:8080"));
        assertTrue(UrlUtils.isUsable("https://cc.example.com/?token=abc"));
        assertFalse(UrlUtils.isUsable("javascript:alert(1)"));
        assertFalse(UrlUtils.isUsable("file:///etc/passwd"));
        assertFalse(UrlUtils.isUsable(null));
        assertFalse(UrlUtils.isUsable(""));
    }

    @Test
    public void isUsable_rejectsBadPorts() {
        assertTrue(UrlUtils.isUsable("http://host:8080"));
        assertTrue(UrlUtils.isUsable("http://host:1"));
        assertTrue(UrlUtils.isUsable("http://host:65535"));
        assertFalse(UrlUtils.isUsable("http://host:abc"));   // 非数字端口
        assertFalse(UrlUtils.isUsable("http://host:99999")); // 超范围
        assertFalse(UrlUtils.isUsable("http://host:0"));     // 0 非法
        assertFalse(UrlUtils.isUsable("http://host:"));      // 空端口
    }

    @Test
    public void extractLabel_handlesIpAndDomain() {
        assertEquals("192.168.1.20", UrlUtils.extractLabel("http://192.168.1.20:8080"));
        assertEquals("my-pc", UrlUtils.extractLabel("http://my-pc.local:8080"));
        assertEquals("cc", UrlUtils.extractLabel("https://cc.example.com"));
    }

    @Test
    public void extractLabel_fallback() {
        assertEquals("未命名", UrlUtils.extractLabel("http://"));
        assertEquals("未命名", UrlUtils.extractLabel(null));
    }

    @Test
    public void authorityOf_normalizesDefaultPorts() {
        assertEquals("example.com:8080", UrlUtils.authorityOf("http://example.com:8080"));
        assertEquals("example.com:80", UrlUtils.authorityOf("http://example.com"));
        assertEquals("example.com:443", UrlUtils.authorityOf("https://example.com"));
        assertEquals("192.168.1.20:8080", UrlUtils.authorityOf("http://192.168.1.20:8080/?token=x"));
        assertEquals("host:8080", UrlUtils.authorityOf("https://host:8080/path"));
    }

    @Test
    public void authorityOf_rejectsInvalidAndCase() {
        assertEquals("", UrlUtils.authorityOf(null));
        assertEquals("", UrlUtils.authorityOf("haha://host"));
        assertEquals("", UrlUtils.authorityOf("http://"));
        assertEquals("host:8080", UrlUtils.authorityOf("http://HOST:8080")); // host 大小写归一为小写
        assertEquals("host:8080", UrlUtils.authorityOf("http://host:8080"));
    }
}
