package com.cchaha.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** UrlUtils 公共方法（token 提取/尾斜杠）回归测试 */
public class UrlUtilsTokenTest {

    @Test
    public void extractToken_plain() {
        assertEquals("abc123", UrlUtils.extractToken("https://host/?token=abc123"));
    }

    @Test
    public void extractToken_withOtherParams() {
        assertEquals("abc", UrlUtils.extractToken("https://host/?a=1&token=abc&b=2"));
    }

    @Test
    public void extractToken_decodesEncoded() {
        // URL 内为编码形式：%2B → +、%26 → &
        assertEquals("a+b&c", UrlUtils.extractToken("https://host/?token=a%2Bb%26c"));
    }

    @Test
    public void extractToken_missingReturnsEmpty() {
        assertEquals("", UrlUtils.extractToken("https://host/"));
        assertEquals("", UrlUtils.extractToken(null));
    }

    @Test
    public void trimTrailingSlash() {
        assertEquals("https://host", UrlUtils.trimTrailingSlash("https://host/"));
        assertEquals("https://host", UrlUtils.trimTrailingSlash("https://host///"));
        assertEquals("https://host", UrlUtils.trimTrailingSlash("https://host"));
        assertEquals("", UrlUtils.trimTrailingSlash(null));
    }
}
