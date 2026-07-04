package com.nodesb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 原始 socket 层面的 HTTP 请求头解析工具，供 ForwardServer / PublicServer 共用。
 * 对应 Python 版的 _recv_headers / _parse_headers / _is_websocket_upgrade / _pipe。
 */
public final class RawHttpUtils {
    private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private RawHttpUtils() {
    }

    /** 循环读取直到拿到完整请求头（含结尾的空行），避免分片/长请求头解析失败。 */
    public static byte[] recvHeaders(InputStream in, int maxSize) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (true) {
            int n = in.read(chunk);
            if (n == -1) {
                break;
            }
            buf.write(chunk, 0, n);
            byte[] data = buf.toByteArray();
            if (indexOf(data, HEADER_END) >= 0) {
                break;
            }
            if (data.length > maxSize) {
                break;
            }
        }
        return buf.toByteArray();
    }

    public static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static Map<String, String> parseHeaders(String headerPart) {
        Map<String, String> headers = new HashMap<>();
        String[] lines = headerPart.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx < 0) {
                continue;
            }
            String k = lines[i].substring(0, idx).trim().toLowerCase();
            String v = lines[i].substring(idx + 1).trim();
            headers.put(k, v);
        }
        return headers;
    }

    /** 等价于 Node http 模块只在真实 upgrade 请求时才触发 'upgrade' 事件的行为。 */
    public static boolean isWebSocketUpgrade(Map<String, String> headers) {
        String connection = headers.getOrDefault("connection", "");
        Set<String> tokens = new HashSet<>();
        for (String t : connection.split(",")) {
            tokens.add(t.trim().toLowerCase());
        }
        String upgrade = headers.getOrDefault("upgrade", "").trim().toLowerCase();
        return tokens.contains("upgrade") && upgrade.equals("websocket");
    }

    /** 单向转发 src -> dst，读到 EOF 后半关闭 dst 的写方向（对应 Python 的 shutdown(SHUT_WR)）。 */
    public static void pipe(Socket src, Socket dst) {
        try {
            InputStream in = src.getInputStream();
            var out = dst.getOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // 对端断开是正常情况，不需要打印堆栈
        } finally {
            try {
                dst.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    public static String requestPath(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        return parts[1].split("\\?")[0];
    }

    public static byte[] slice(byte[] data, int from, int to) {
        return Arrays.copyOfRange(data, from, to);
    }
}
