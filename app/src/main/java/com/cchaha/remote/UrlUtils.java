package com.cchaha.remote;

/**
 * URL 规范化与校验工具（纯 Java 实现，便于单元测试）。
 * cc-haha 的 H5 链接是 http(s)://host:port 形式（可能带 token 参数）。
 */
public final class UrlUtils {

    private UrlUtils() { }

    private static final String HOST_RE = "[A-Za-z0-9.\\-\\[\\]:%]+";

    /** 规范化用户输入：无协议补 http://；非法输入返回 null */
    public static String normalize(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        // 已含协议但非 http(s)：直接拒绝（haha://、ftp:// 等）
        if (s.contains("://") && !s.startsWith("http://") && !s.startsWith("https://")) {
            return null;
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        return isUsable(s) ? s : null;
    }

    /** 是否为可加载的 http(s) 链接（手工解析 authority，不依赖 android.net.Uri） */
    public static boolean isUsable(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;

        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return false;
        String rest = url.substring(schemeEnd + 3);

        // 取 authority（host[:port]），到第一个 / ? # 为止
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String authority = rest.substring(0, end);
        if (authority.isEmpty()) return false;

        String hostPart = authority;
        if (authority.contains(":")) {
            // 去掉端口（IPv6 用 [] 包裹，[] 保留在 hostPart 里做宽松匹配）
            hostPart = authority.substring(0, authority.lastIndexOf(':'));
        }
        if (hostPart.isEmpty()) return false;
        if (!hostPart.matches(HOST_RE)) return false;
        // 拒绝明显异常的 host（连续点/首尾点）
        if (hostPart.contains("..") || hostPart.startsWith(".") || hostPart.endsWith(".")) return false;
        return true;
    }

    /** 从链接提取展示名：IP 保留原样，域名取第一段，可读化 */
    public static String extractLabel(String url) {
        if (!isUsable(url)) return "未命名";
        int schemeEnd = url.indexOf("://");
        String rest = url.substring(schemeEnd + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String host = rest.substring(0, end);
        if (host.contains(":")) host = host.substring(0, host.lastIndexOf(':'));
        if (host.isEmpty()) return "未命名";

        String label = host;
        if (!Character.isDigit(host.charAt(0)) && !host.startsWith("[")) {
            String[] parts = host.split("\\.");
            if (parts.length > 0 && !parts[0].isEmpty()) label = parts[0];
        }
        if (label.length() > 24) label = label.substring(0, 24);
        return label;
    }
}
