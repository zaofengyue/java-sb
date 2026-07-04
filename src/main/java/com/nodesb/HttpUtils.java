package com.nodesb;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

public final class HttpUtils {
    private static final Logger log = Logger.getLogger("HttpUtils");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpUtils() {
    }

    /** GET 一个纯文本接口，失败一律返回空字符串（不抛异常），调用方按空串处理探测失败。 */
    public static String getText(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "curl/8.0")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body().trim();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 跨平台下载：优先 curl，再 wget，最后用 HttpClient 兜底（自动处理重定向）。 */
    public static void download(String url, String dest) throws IOException, InterruptedException {
        for (List<String> cmd : List.of(
                List.of("curl", "-fsSL", url, "-o", dest),
                List.of("wget", "-q", url, "-O", dest))) {
            try {
                if (ProcessUtils.runSilently(cmd)) {
                    return;
                }
            } catch (IOException ignored) {
                // 命令不存在或执行失败，继续尝试下一种方式
            }
        }
        log.info("curl/wget 均不可用，改用内置 HTTP 客户端下载: " + url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<Path> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofFile(Path.of(dest)));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("下载失败，HTTP " + resp.statusCode() + ": " + url);
        }
    }
}
