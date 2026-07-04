package com.nodesb;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CloudflaredManager {
    private static final Logger log = Logger.getLogger("CloudflaredManager");
    private static final Pattern TRYCLOUDFLARE_PATTERN =
            Pattern.compile("https://([a-z0-9-]+\\.trycloudflare\\.com)");

    private CloudflaredManager() {
    }

    public static String download(File cfBin) throws IOException, InterruptedException {
        if (cfBin.exists()) {
            ProcessUtils.chmodExecutable(cfBin);
            return cfBin.getAbsolutePath();
        }
        String suffix = switch (SingBoxManager.detectArch()) {
            case "arm64" -> "linux-arm64";
            case "armv7" -> "linux-arm";
            default -> "linux-amd64";
        };
        log.info("正在下载 cloudflared (" + suffix + ")...");
        String url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-" + suffix;
        HttpUtils.download(url, cfBin.getAbsolutePath());
        ProcessUtils.chmodExecutable(cfBin);
        log.info("cloudflared 下载完成");
        return cfBin.getAbsolutePath();
    }

    /**
     * 启动 Argo 隧道，返回可用的域名。
     * 固定隧道模式（domain+auth 都给了）：跟 Node/Python 版一样，不会真正校验隧道是否连接成功，
     * 只是无条件等待 3 秒后返回配置的域名。
     */
    public static String startTunnel(String cfBin, int argoPort, String argoDomain, String argoAuth) {
        if (!argoDomain.isEmpty() && !argoAuth.isEmpty()) {
            log.info("启动固定 Argo 隧道...");
            try {
                new ProcessBuilder(cfBin, "tunnel", "--edge-ip-version", "auto",
                        "--no-autoupdate", "run", "--token", argoAuth)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException e) {
                log.severe("启动固定 Argo 隧道失败: " + e.getMessage());
            }
            ProcessUtils.sleepQuietly(3000);
            return argoDomain;
        }

        log.info("启动临时 Argo 隧道...");
        AtomicReference<String> host = new AtomicReference<>("");
        CountDownLatch done = new CountDownLatch(1);
        try {
            Process proc = new ProcessBuilder(cfBin, "tunnel", "--edge-ip-version", "auto",
                    "--no-autoupdate", "--url", "http://127.0.0.1:" + argoPort)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher m = TRYCLOUDFLARE_PATTERN.matcher(line);
                        if (m.find() && host.get().isEmpty()) {
                            host.set(m.group(1));
                            log.info("临时隧道域名: " + host.get());
                            done.countDown();
                            break;
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            if (!done.await(30, TimeUnit.SECONDS)) {
                log.info("临时隧道域名获取超时");
            }
        } catch (IOException | InterruptedException e) {
            log.severe("启动临时 Argo 隧道失败: " + e.getMessage());
        }
        return host.get();
    }
}
