package com.nodesb;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class Main {
    private static final Logger log = Logger.getLogger("node-sb");

    // ========== 预留配置，留空则自动识别 ==========
    static final String CONF_UUID = "8295a139-4b22-4610-92db-bdbed2038631";
    static final String CONF_PORT = "";
    static final String CONF_ARGO_PORT = "";
    static final String CONF_NAME = "";
    static final String CONF_SUB = "";
    static final String CONF_ARGO_DOMAIN = "";
    static final String CONF_ARGO_AUTH = "";
    // 填 "true" 禁用 Argo，留空则启用
    static final String CONF_DISABLE_ARGO = "";
    // 可选协议，填写端口则启动对应协议，留空不启动
    static final String CONF_HY2_PORT = "30091";
    static final String CONF_TUIC_PORT = "";
    static final String CONF_REALITY_PORT = "30091";
    static final String CONF_REALITY_DOMAIN = "";
    static final String CONF_SS_PORT = "";
    static final String CONF_S5_PORT = "";
    static final String CONF_ANYTLS_PORT = "";
    // 填 "0"/"false"/"no" 关闭部署完成后的清理动作，留空即默认开启
    static final String CONF_CLEANUP_AFTER_DEPLOY = "";
    // =============================================

    private static final Path HOME = Path.of(firstNonEmpty(System.getenv("HOME"), System.getProperty("java.io.tmpdir")));
    // 运行期生成的所有文件统一放进这一个目录，不再散落在 $HOME 和当前工作目录两处
    private static final Path WORLD_DIR = HOME.resolve("world");
    private static final File UUID_FILE = WORLD_DIR.resolve("uuid.txt").toFile();
    private static final File CONFIG_FILE = WORLD_DIR.resolve("sb-config.json").toFile();
    private static final File SB_DIR = WORLD_DIR.resolve("sing-box").toFile();
    private static final File SB_BIN_PATH = new File(SB_DIR, "sing-box");
    private static final File CLOUDFLARED_BIN = WORLD_DIR.resolve("cloudflared").toFile();
    private static final File SUB_FILE = WORLD_DIR.resolve("sub.txt").toFile();

    private static final String WS_PATH_VMESS = "/fengyue-vm";
    private static final String WS_PATH_VLESS = "/fengyue-vl";
    private static final String WS_PATH_TROJAN = "/fengyue-tr";

    private static final int V_VMESS_PORT = 10000;
    private static final int V_VLESS_PORT = 10001;
    private static final int V_TROJAN_PORT = 10002;

    private static final String CF_PREFER_HOST = "cdns.doon.eu.org";

    public static void main(String[] args) throws Exception {
        WORLD_DIR.toFile().mkdirs();

        boolean disableArgo = "true".equals(firstNonEmpty(CONF_DISABLE_ARGO, System.getenv("DISABLE_ARGO")));

        // ── UUID ──
        String envUuid = firstNonEmpty(CONF_UUID, System.getenv("UUID"));
        String nodeUuid;
        if (!envUuid.isEmpty()) {
            nodeUuid = envUuid;
            Files.writeString(UUID_FILE.toPath(), nodeUuid);
        } else if (UUID_FILE.exists()) {
            nodeUuid = Files.readString(UUID_FILE.toPath()).strip();
        } else {
            nodeUuid = UUID.randomUUID().toString();
            Files.writeString(UUID_FILE.toPath(), nodeUuid);
        }
        CertUtils.securePermissions(UUID_FILE.toPath());

        String trojanPass = nodeUuid;
        String ssPass = Util.deriveSsPassword(nodeUuid);

        int inboundPort = parseIntOrDefault(firstNonEmpty(CONF_PORT, System.getenv("PORT")), () -> Util.getFreePort());

        String subRaw = firstNonEmpty(CONF_SUB, System.getenv("SUB"), "sub");
        String subPath = "/" + stripLeadingSlash(subRaw);

        String argoDomain = firstNonEmpty(CONF_ARGO_DOMAIN, System.getenv("ARGO_DOMAIN"));
        String argoAuth = firstNonEmpty(CONF_ARGO_AUTH, System.getenv("ARGO_AUTH"));

        int argoPort;
        if (!argoDomain.isEmpty() && !argoAuth.isEmpty()) {
            argoPort = Integer.parseInt(firstNonEmpty(CONF_ARGO_PORT, System.getenv("ARGO_PORT"), "8001"));
        } else {
            argoPort = Util.getFreePort();
        }

        int hy2Port = parseIntOrZero(firstNonEmpty(CONF_HY2_PORT, System.getenv("HY2_PORT")));
        int tuicPort = parseIntOrZero(firstNonEmpty(CONF_TUIC_PORT, System.getenv("TUIC_PORT")));
        int realityPort = parseIntOrZero(firstNonEmpty(CONF_REALITY_PORT, System.getenv("REALITY_PORT")));
        int ssPort = parseIntOrZero(firstNonEmpty(CONF_SS_PORT, System.getenv("SS_PORT")));
        int s5Port = parseIntOrZero(firstNonEmpty(CONF_S5_PORT, System.getenv("S5_PORT")));
        int anytlsPort = parseIntOrZero(firstNonEmpty(CONF_ANYTLS_PORT, System.getenv("ANYTLS_PORT")));

        String realityDomain = firstNonEmpty(CONF_REALITY_DOMAIN, System.getenv("REALITY_DOMAIN"), "www.iij.ad.jp");

        String name = Util.detectNodeName(firstNonEmpty(CONF_NAME, System.getenv("NAME")));

        boolean anyOptionalProtocol = hy2Port > 0 || tuicPort > 0 || realityPort > 0
                || ssPort > 0 || s5Port > 0 || anytlsPort > 0;
        String publicIp = anyOptionalProtocol ? HttpUtils.getText("https://ipinfo.io/ip") : "";
        if (publicIp.isEmpty() && anyOptionalProtocol) {
            publicIp = HttpUtils.getText("https://ifconfig.co/ip");
        }

        // ── sing-box 配置：Argo 三协议 ──
        JSONObject config = ConfigBuilder.baseConfig();
        if (!disableArgo) {
            config.getJSONArray("inbounds").put(
                    ConfigBuilder.argoInbound("vmess", "vmess-in", V_VMESS_PORT, WS_PATH_VMESS, nodeUuid, trojanPass));
            config.getJSONArray("inbounds").put(
                    ConfigBuilder.argoInbound("vless", "vless-in", V_VLESS_PORT, WS_PATH_VLESS, nodeUuid, trojanPass));
            config.getJSONArray("inbounds").put(
                    ConfigBuilder.argoInbound("trojan", "trojan-in", V_TROJAN_PORT, WS_PATH_TROJAN, nodeUuid, trojanPass));
        }

        // ── 定位/下载 sing-box（Reality 密钥生成依赖它）──
        String sbBin = SingBoxManager.locateOrDownload(SB_BIN_PATH, SB_DIR);

        // ── 端口唯一性检测：同时把伪装页端口 / Argo 内部端口也算进去，避免交叉冲突 ──
        Set<String> usedPorts = new HashSet<>();
        usedPorts.add("tcp:" + inboundPort);
        if (!disableArgo) {
            usedPorts.add("tcp:" + argoPort);
            usedPorts.add("tcp:" + V_VMESS_PORT);
            usedPorts.add("tcp:" + V_VLESS_PORT);
            usedPorts.add("tcp:" + V_TROJAN_PORT);
        }

        boolean hy2Active = portOk(usedPorts, hy2Port, "udp");
        boolean tuicActive = portOk(usedPorts, tuicPort, "udp");
        boolean realityActive = portOk(usedPorts, realityPort, "tcp");
        boolean ssActive = portOk(usedPorts, ssPort, "tcp");
        boolean s5Active = portOk(usedPorts, s5Port, "tcp");
        boolean anytlsActive = portOk(usedPorts, anytlsPort, "tcp");

        warnIfSkipped(hy2Port, hy2Active, "HY2_PORT", "Hysteria2");
        warnIfSkipped(tuicPort, tuicActive, "TUIC_PORT", "TUIC");
        warnIfSkipped(realityPort, realityActive, "REALITY_PORT", "Reality");
        warnIfSkipped(ssPort, ssActive, "SS_PORT", "Shadowsocks");
        warnIfSkipped(s5Port, s5Active, "S5_PORT", "Socks5");
        warnIfSkipped(anytlsPort, anytlsActive, "ANYTLS_PORT", "AnyTLS");

        // ── 自签证书（Hysteria2 / TUIC / AnyTLS 需要）──
        String certPath = "";
        String keyPath = "";
        boolean certReady = false;
        if (hy2Active || tuicActive || anytlsActive) {
            try {
                CertUtils.CertPaths paths = CertUtils.generateSelfSignedCert(new File(WORLD_DIR.toFile(), "certs"));
                keyPath = paths.keyPath();
                certPath = paths.certPath();
                certReady = true;
            } catch (IOException e) {
                log.severe("证书生成失败，Hysteria2/TUIC/AnyTLS 将被跳过: " + e.getMessage());
            }
        }
        if (!certReady) {
            if (hy2Active) log.warning("因证书不可用，Hysteria2 已跳过");
            if (tuicActive) log.warning("因证书不可用，TUIC 已跳过");
            if (anytlsActive) log.warning("因证书不可用，AnyTLS 已跳过");
        }
        boolean hy2Final = hy2Active && certReady;
        boolean tuicFinal = tuicActive && certReady;
        boolean anytlsFinal = anytlsActive && certReady;

        if (hy2Final) {
            log.info("启用 Hysteria2，端口 " + hy2Port);
            config.getJSONArray("inbounds").put(
                    ConfigBuilder.hysteria2Inbound(hy2Port, nodeUuid, certPath, keyPath));
        }
        if (tuicFinal) {
            log.info("启用 TUIC v5，端口 " + tuicPort);
            config.getJSONArray("inbounds").put(
                    ConfigBuilder.tuicInbound(tuicPort, nodeUuid, certPath, keyPath));
        }

        String realityPubKey = "";
        if (realityActive) {
            log.info("启用 VLESS Reality，端口 " + realityPort);
            Path realityKeyFile = WORLD_DIR.resolve("reality-keys.json");
            String[] keys = SingBoxManager.loadOrGenerateRealityKeys(sbBin, realityKeyFile);
            String realityPrivKey = keys[0];
            realityPubKey = keys[1];
            if (realityPrivKey.isEmpty() || realityPubKey.isEmpty()) {
                log.warning("Reality 密钥生成失败，VLESS Reality 已跳过");
                realityActive = false;
            } else {
                config.getJSONArray("inbounds").put(
                        ConfigBuilder.realityInbound(realityPort, nodeUuid, realityDomain, realityPrivKey));
            }
        }

        if (ssActive) {
            log.info("启用 Shadowsocks 2022，端口 " + ssPort);
            config.getJSONArray("inbounds").put(ConfigBuilder.shadowsocksInbound(ssPort, ssPass));
        }

        if (s5Active) {
            log.info("启用 Socks5，端口 " + s5Port);
            config.getJSONArray("inbounds").put(
                    ConfigBuilder.socksInbound(s5Port, nodeUuid.substring(0, 8), nodeUuid.substring(nodeUuid.length() - 12)));
        }

        if (anytlsFinal) {
            log.info("启用 AnyTLS，端口 " + anytlsPort);
            config.getJSONArray("inbounds").put(
                    ConfigBuilder.anytlsInbound(anytlsPort, nodeUuid, certPath, keyPath));
        }

        Files.writeString(CONFIG_FILE.toPath(), config.toString(2));

        try {
            ProcessUtils.Result ver = ProcessUtils.runCapture(List.of(sbBin, "version"));
            log.info("sing-box 版本信息:\n" + ver.stdout().strip());
        } catch (IOException e) {
            log.warning("无法获取 sing-box 版本信息: " + e.getMessage());
        }

        // 启动前先做一次配置校验，任何一个 inbound 类型不被当前版本识别都会导致
        // sing-box 整体拒绝启动。提前 check 能在真正启动前发现问题并打印出来。
        File sbLogFile = new File(SB_DIR, "run.log");
        boolean sbStartFailed = false;
        try {
            ProcessUtils.Result check = ProcessUtils.runCapture(List.of(sbBin, "check", "-c", CONFIG_FILE.getAbsolutePath()));
            if (check.ok()) {
                log.info("sing-box 配置校验通过");
            } else {
                String detail = check.stdout() + check.stderr();
                log.severe("================ sing-box 配置校验失败 ================");
                log.severe(detail.strip());
                log.severe("========================================================");
                log.severe("常见原因：当前 sing-box 版本过旧，不支持某个已启用的协议类型"
                        + "（例如 AnyTLS 需要 sing-box >= 1.12.0）。"
                        + "请删除本地 sing-box 二进制后重新运行程序以下载最新版本，"
                        + "或关闭对应协议端口变量后重试。");
                Files.writeString(sbLogFile.toPath(), "[CONFIG CHECK FAILED]\n" + detail + "\n");
                log.info("详细日志已写入: " + sbLogFile);
                log.info("配置校验未通过，跳过启动 sing-box（Argo/HTTP订阅服务仍会继续运行）。");
                sbStartFailed = true;
            }
        } catch (IOException e) {
            log.severe("执行 sing-box check 失败: " + e.getMessage());
            sbStartFailed = true;
        }

        ProcessUtils.pkillQuietly(SB_BIN_PATH.getAbsolutePath());
        ProcessUtils.sleepQuietly(800);

        Process sbProc = null;
        if (!sbStartFailed) {
            // 不用 DISCARD 丢弃输出，改为写入日志文件，方便只能看面板日志的环境排查问题
            Map<String, String> removeEnv = new HashMap<>();
            removeEnv.put("PORT", "");
            sbProc = ProcessUtils.spawnBackground(
                    List.of(sbBin, "run", "-c", CONFIG_FILE.getAbsolutePath()), sbLogFile, removeEnv);
            log.info("sing-box 已在后台启动，PID: " + sbProc.pid());
            log.info("运行日志: " + sbLogFile);
        }

        ProcessUtils.sleepQuietly(1500);

        // ── Argo 三协议 WS 转发（仅本地）──
        if (!disableArgo) {
            Map<String, Integer> pathToPort = Map.of(
                    WS_PATH_VMESS, V_VMESS_PORT,
                    WS_PATH_VLESS, V_VLESS_PORT,
                    WS_PATH_TROJAN, V_TROJAN_PORT);
            Thread t = new Thread(new ForwardServer(argoPort, pathToPort));
            t.setDaemon(true);
            t.start();
        }

        // ── HTTP 服务（伪装页 + 订阅）──
        String indexHtml = loadIndexHtml();
        java.util.concurrent.atomic.AtomicReference<String> subHolder = new java.util.concurrent.atomic.AtomicReference<>("");
        Thread httpThread = new Thread(new PublicServer(inboundPort, subPath, indexHtml, subHolder));
        httpThread.setDaemon(true);
        httpThread.start();

        // ── cloudflared / Argo 域名 ──
        String host = "your-domain.com";
        if (!disableArgo) {
            String cfBin = CloudflaredManager.download(CLOUDFLARED_BIN);
            String argoHost = CloudflaredManager.startTunnel(cfBin, argoPort, argoDomain, argoAuth);
            host = argoHost.isEmpty() ? "your-domain.com" : argoHost;
        } else {
            log.info("Argo 隧道已禁用，跳过 cloudflared");
        }

        // ── 生成订阅链接 ──
        List<String> links = LinkBuilder.newList();
        if (!disableArgo) {
            links.add(LinkBuilder.vmessLink(name, CF_PREFER_HOST, nodeUuid, host, WS_PATH_VMESS));
            links.add(LinkBuilder.vlessArgoLink(nodeUuid, CF_PREFER_HOST, host, WS_PATH_VLESS, name));
            links.add(LinkBuilder.trojanArgoLink(trojanPass, CF_PREFER_HOST, host, WS_PATH_TROJAN, name));
        }
        if (hy2Final && !publicIp.isEmpty()) {
            links.add(LinkBuilder.hysteria2Link(nodeUuid, publicIp, hy2Port, name));
        }
        if (tuicFinal && !publicIp.isEmpty()) {
            links.add(LinkBuilder.tuicLink(nodeUuid, publicIp, tuicPort, name));
        }
        if (realityActive && !publicIp.isEmpty() && !realityPubKey.isEmpty()) {
            links.add(LinkBuilder.realityLink(nodeUuid, publicIp, realityPort, realityDomain, realityPubKey, name));
        }
        if (ssActive && !publicIp.isEmpty()) {
            links.add(LinkBuilder.shadowsocksLink(ssPass, publicIp, ssPort, name));
        }
        if (s5Active && !publicIp.isEmpty()) {
            links.add(LinkBuilder.socksLink(nodeUuid, publicIp, s5Port, name));
        }
        if (anytlsFinal && !publicIp.isEmpty()) {
            links.add(LinkBuilder.anytlsLink(nodeUuid, publicIp, anytlsPort, name));
        }

        String subB64 = LinkBuilder.buildSubscription(links);
        subHolder.set(subB64);
        Files.writeString(SUB_FILE.toPath(), subB64, StandardCharsets.UTF_8);

        System.out.println("================= 订阅内容 =================");
        System.out.println(subB64);
        System.out.println("============================================");
        System.out.println("订阅地址: https://" + host + subPath);
        System.out.println("节点文件: " + SUB_FILE.getAbsolutePath());

        System.out.println("============== 已启用协议 ==============");
        if (!disableArgo) {
            System.out.println("✓ VMess  + WS + Argo TLS");
            System.out.println("✓ VLESS  + WS + Argo TLS");
            System.out.println("✓ Trojan + WS + Argo TLS");
        }
        if (hy2Final) System.out.println("✓ Hysteria2     端口 " + hy2Port + " (UDP)");
        if (tuicFinal) System.out.println("✓ TUIC v5       端口 " + tuicPort + " (UDP)");
        if (realityActive) System.out.println("✓ VLESS Reality 端口 " + realityPort + "  PubKey: " + (realityPubKey.isEmpty() ? "生成中" : realityPubKey));
        if (ssActive) System.out.println("✓ Shadowsocks   端口 " + ssPort + " (TCP)  密码: " + ssPass);
        if (s5Active) System.out.println("✓ Socks5        端口 " + s5Port + " (TCP)  账号: " + nodeUuid.substring(0, 8));
        if (anytlsFinal) System.out.println("✓ AnyTLS        端口 " + anytlsPort + " (TCP)");
        if (disableArgo) System.out.println("✗ Argo 隧道已禁用");
        System.out.println("运行环境: linux-" + SingBoxManager.detectArch());
        System.out.println("========================================");

        String cleanupEnv = firstNonEmpty(CONF_CLEANUP_AFTER_DEPLOY, System.getenv("CLEANUP_AFTER_DEPLOY")).toLowerCase().strip();
        boolean cleanupAfterDeploy = !List.of("0", "false", "no").contains(cleanupEnv);
        if (cleanupAfterDeploy) {
            cleanupDeployArtifacts();
        }

        Process finalSbProc = sbProc;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            if (finalSbProc != null) {
                finalSbProc.destroy();
            }
        }));

        // 主线程保活，跟 Python/Node 版一样常驻运行
        Thread.currentThread().join();
    }

    /**
     * 清理部署完成后不再需要的附带文件，减少 world 目录体积。
     * 只清理明确用不到的内容，不会碰持久化文件（uuid.txt/sb-config.json/sub.txt/
     * reality-keys.json/certs/）或运行必需的二进制本身：
     *   - sing-box 官方发行包里附带的 LICENSE/README/CHANGELOG 等说明文件
     *   - 残留的下载临时文件（正常流程已经删过，这里做兜底）
     */
    private static void cleanupDeployArtifacts() {
        List<String> removed = new java.util.ArrayList<>();

        File leftoverTar = new File(WORLD_DIR.toFile(), "sb.tar.gz");
        if (leftoverTar.exists() && leftoverTar.delete()) {
            removed.add(leftoverTar.getName());
        }

        List<String> unusedNames = List.of(
                "LICENSE", "LICENSE.txt", "README.md", "README", "CHANGELOG.md", "CHANGELOG");
        if (SB_DIR.isDirectory()) {
            for (String n : unusedNames) {
                File f = new File(SB_DIR, n);
                if (f.exists() && !f.equals(SB_BIN_PATH) && f.delete()) {
                    removed.add(n);
                }
            }
        }

        if (removed.isEmpty()) {
            log.info("cleanup: nothing to remove");
        } else {
            log.info("cleanup: removed unused file(s): " + String.join(", ", removed));
        }
    }

    private static boolean portOk(Set<String> usedPorts, int port, String proto) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        String key = proto + ":" + port;
        if (usedPorts.contains(key)) {
            return false;
        }
        usedPorts.add(key);
        return true;
    }

    private static void warnIfSkipped(int port, boolean active, String envName, String protoName) {
        if (port > 0 && !active) {
            log.warning(envName + "(" + port + ") 端口冲突或无效，" + protoName + " 已跳过");
        }
    }

    private static String loadIndexHtml() {
        File f = new File(System.getProperty("user.dir"), "index.html");
        if (f.exists()) {
            try {
                return Files.readString(f.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warning("failed to read index.html: " + e.getMessage());
            }
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Welcome</title></head>"
                + "<body><h1>Hello World</h1></body></html>";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private interface IntSupplier {
        int get();
    }

    private static int parseIntOrDefault(String s, IntSupplier fallback) {
        if (s == null || s.isEmpty()) {
            return fallback.get();
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback.get();
        }
    }

    private static String stripLeadingSlash(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return s.substring(i);
    }
}
