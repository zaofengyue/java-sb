package com.nodesb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

public final class SingBoxManager {
    private static final Logger log = Logger.getLogger("SingBoxManager");

    // 兜底版本必须 >= 1.12.0，否则 AnyTLS 协议类型无法被识别，
    // sing-box 会在配置校验阶段整体拒绝启动（影响全部协议，不仅是 AnyTLS）
    private static final String FALLBACK_VERSION = "v1.12.0";

    private SingBoxManager() {
    }

    public static String detectArch() {
        String arch = System.getProperty("os.arch", "amd64").toLowerCase();
        return switch (arch) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            case "arm" -> "armv7";
            case "x86", "i386", "i686" -> "386";
            default -> "amd64";
        };
    }

    /** 定位本机已有的 sing-box，找不到就下载。 */
    public static String locateOrDownload(File sbBinPath, File sbDir) throws IOException, InterruptedException {
        if (sbBinPath.exists()) {
            ProcessUtils.chmodExecutable(sbBinPath);
            return sbBinPath.getAbsolutePath();
        }
        for (String candidate : List.of("/usr/local/bin/sing-box", "/usr/bin/sing-box")) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }
        return download(sbBinPath, sbDir);
    }

    private static String download(File sbBinPath, File sbDir) throws IOException, InterruptedException {
        String arch = detectArch();
        log.info("正在获取 sing-box 最新版本 (linux-" + arch + ")...");

        String version = FALLBACK_VERSION;
        try {
            String data = HttpUtils.getText("https://api.github.com/repos/SagerNet/sing-box/releases");
            if (!data.isEmpty()) {
                JSONArray releases = new JSONArray(data);
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject r = releases.getJSONObject(i);
                    if (!r.optBoolean("prerelease", false) && !r.optBoolean("draft", false)
                            && r.has("tag_name")) {
                        version = r.getString("tag_name");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warning("获取 sing-box release 列表失败，使用兜底版本 " + FALLBACK_VERSION + ": " + e.getMessage());
        }

        log.info("sing-box 版本: " + version);
        String verNum = version.startsWith("v") ? version.substring(1) : version;
        String tarName = "sing-box-" + verNum + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/" + version + "/" + tarName;

        sbDir.mkdirs();
        File tarFile = new File(sbBinPath.getParentFile().getParentFile(), "sb.tar.gz");
        log.info("正在下载 sing-box...");
        HttpUtils.download(url, tarFile.getAbsolutePath());

        ProcessUtils.extractTarGzStripped(tarFile.getAbsolutePath(), sbDir.getAbsolutePath());
        ProcessUtils.chmodExecutable(sbBinPath);
        tarFile.delete();
        log.info("sing-box 下载完成");
        return sbBinPath.getAbsolutePath();
    }

    /** 生成 Reality 密钥对，解析 `sing-box generate reality-keypair` 的文本输出。 */
    public static String[] generateRealityKeypair(String sbBin) {
        try {
            ProcessUtils.Result r = ProcessUtils.runCapture(List.of(sbBin, "generate", "reality-keypair"));
            String out = r.stdout();
            Matcher priv = Pattern.compile("PrivateKey:\\s*(\\S+)").matcher(out);
            Matcher pub = Pattern.compile("PublicKey:\\s*(\\S+)").matcher(out);
            if (priv.find() && pub.find()) {
                return new String[]{priv.group(1), pub.group(1)};
            }
        } catch (IOException e) {
            log.severe("Reality 密钥生成失败: " + e.getMessage());
        }
        return new String[]{"", ""};
    }

    public static String[] loadOrGenerateRealityKeys(String sbBin, Path keyFile) {
        if (Files.exists(keyFile)) {
            try {
                JSONObject saved = new JSONObject(Files.readString(keyFile));
                String priv = saved.optString("privKey", "");
                String pub = saved.optString("pubKey", "");
                if (!priv.isEmpty() && !pub.isEmpty()) {
                    log.info("已从文件读取 Reality 密钥对");
                    return new String[]{priv, pub};
                }
                throw new IllegalStateException("密钥文件字段不完整");
            } catch (Exception e) {
                log.warning("reality-keys.json 读取失败（" + e.getMessage() + "），重新生成...");
                try {
                    Files.deleteIfExists(keyFile);
                } catch (IOException ignored) {
                }
            }
        }

        String[] keys = generateRealityKeypair(sbBin);
        if (!keys[0].isEmpty() && !keys[1].isEmpty()) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("privKey", keys[0]);
                obj.put("pubKey", keys[1]);
                Files.writeString(keyFile, obj.toString());
                CertUtils.securePermissions(keyFile);
                log.info("Reality 密钥对生成并保存成功");
            } catch (IOException e) {
                log.warning("Reality 密钥对保存失败: " + e.getMessage());
            }
        }
        return keys;
    }
}
