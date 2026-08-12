package com.cchaha.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * H5StartupPatcher 补丁逻辑验证：
 * 1. patch 把看门狗渲染函数短路（永不显示错误页）
 * 2. patch 幂等
 * 3. fetchPatched 对不可达地址返回 null（走原逻辑）
 * 4. 对本地 HTTP 服务端到端：真实主文档 HTML 拉取 + 补丁生效
 */
public class H5StartupPatcherTest {

    /** 上游 index.html 看门狗脚本的关键片段（真实上游代码结构） */
    private static final String WATCHDOG_SNIPPET =
            "        function renderStartupError(reason) {\n" +
            "          if (window.__CC_HAHA_BOOTSTRAPPED__) return\n" +
            "          var root = document.getElementById('root')\n" +
            "          root.innerHTML = ''\n" +
            "        }\n" +
            "        window.__CC_HAHA_SHOW_STARTUP_ERROR__ = renderStartupError\n" +
            "        window.setTimeout(function () {\n" +
            "          renderStartupError('Desktop app did not finish bootstrapping within 8000ms')\n" +
            "        }, 8000)\n";

    @Test
    public void patchShortCircuitsStartupErrorRenderer() {
        String patched = H5StartupPatcher.patch(WATCHDOG_SNIPPET);
        // 函数体首行被替换为直接 return
        assertTrue(patched.contains("function renderStartupError(reason) { return;"));
        // 原函数体仍在（语法合法），但被短路
        assertTrue(patched.contains("root.innerHTML = ''"));
        // 看门狗定时器仍在（行为不变，只是渲染函数变 no-op）
        assertTrue(patched.contains("setTimeout"));
    }

    @Test
    public void patchIsIdempotent() {
        String once = H5StartupPatcher.patch(WATCHDOG_SNIPPET);
        String twice = H5StartupPatcher.patch(once);
        assertEquals(once, twice);
    }

    @Test
    public void patchLeavesUnknownHtmlUntouched() {
        String html = "<html><body>hello</body></html>";
        assertEquals(html, H5StartupPatcher.patch(html));
    }

    @Test
    public void fetchPatchedReturnsNullWhenUnreachable() {
        // 拒绝连接端口 → 抛异常 → null
        assertNull(H5StartupPatcher.fetchPatched("http://127.0.0.1:1/index.html"));
    }

    @Test
    public void fetchPatchedEndToEnd() throws Exception {
        final byte[] body = WATCHDOG_SNIPPET.getBytes(StandardCharsets.UTF_8);
        ServerSocket server = new ServerSocket(0, 1);
        Thread t = new Thread(() -> {
            try {
                Socket s = server.accept();
                InputStream in = s.getInputStream();
                byte[] buf = new byte[2048];
                in.read(buf); // 读请求头即可
                OutputStream out = s.getOutputStream();
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                        "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                s.close();
            } catch (Exception ignored) { }
        });
        t.start();
        try {
            int port = server.getLocalPort();
            String patched = H5StartupPatcher.fetchPatched("http://127.0.0.1:" + port + "/?token=test");
            assertNotNull(patched);
            assertFalse(patched.contains("function renderStartupError(reason) {\n          if (window.__CC_HAHA_BOOTSTRAPPED__)"));
            assertTrue(patched.contains("function renderStartupError(reason) { return;"));
        } finally {
            server.close();
        }
    }
}
