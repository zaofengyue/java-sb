package com.nodesb;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final Pattern TUNNEL_CONNECTED_PATTERN =
            Pattern.compile("(?i)registered tunnel connection");
    private static final Pattern TUNNEL_ERROR_PATTERN =
            Pattern.compile("(?i)(failed to |unable to |unauthorized|context canceled|connection refused)");

    /**
     * 启动 Argo 隧道，返回可用的域名；连接不上/无法确认则返回空字符串，
     * 调用方按空字符串处理，退化为占位域名。
     */
    public static String startTunnel(String cfBin, int argoPort, String argoDomain, String argoAuth) {
        if (!argoDomain.isEmpty() && !argoAuth.isEmpty()) {
            return startFixedTunnel(cfBin, argoDomain, argoAuth);
        }
        return startTempTunnel(cfBin, argoPort);
    }

    /**
     * 固定隧道模式：监听 cloudflared 的日志输出判断连接是否成功，
     * 而不是像 Node 版那样无条件等 3 秒就把 ARGO_DOMAIN 当可用域名。
     * cloudflared 连接成功时会打印类似 "Registered tunnel connection" 的日志；
     * token 无效/网络异常时通常会打印明确的错误信息或者进程直接退出。
     */
    private static String startFixedTunnel(String cfBin, String argoDomain, String argoAuth) {
        log.info("启动固定 Argo 隧道...");
        Process proc;
        try {
            proc = new ProcessBuilder(cfBin, "tunnel", "--edge-ip-version", "auto",
                    "--no-autoupdate", "run", "--token", argoAuth)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            log.severe("启动固定 Argo 隧道失败: " + e.getMessage());
            return "";
        }

        AtomicBoolean connected = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        CountDownLatch settled = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (TUNNEL_CONNECTED_PATTERN.matcher(line).find()) {
                        connected.set(true);
                        settled.countDown();
                        break;
                    }
                    if (TUNNEL_ERROR_PATTERN.matcher(line).find()) {
                        failed.set(true);
                        settled.countDown();
                        break;
                    }
                }
            } catch (IOException ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean settledInTime;
        try {
            settledInTime = settled.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            settledInTime = false;
        }

        if (!proc.isAlive() && !connected.get()) {
            failed.set(true);
        }

        if (connected.get()) {
            log.info("固定 Argo 隧道连接成功");
            return argoDomain;
        }
        if (failed.get()) {
            log.warning("固定 Argo 隧道未能连接（token 可能无效或网络异常），跳过该域名，生成的节点将使用占位域名");
            return "";
        }
        // 10 秒内既没看到明确成功日志、进程也还活着——给出保守提示但仍然使用配置的域名，
        // 避免因为日志格式跟预期不一致（不同 cloudflared 版本用词可能有差异）而误判为失败
        log.warning("未在 10 秒内确认固定 Argo 隧道的连接状态，继续使用配置的 ARGO_DOMAIN 生成链接，请自行确认隧道是否正常连通");
        return argoDomain;
    }

    private static String startTempTunnel(String cfBin, int argoPort) {
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
