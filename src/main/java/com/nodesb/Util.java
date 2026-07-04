package com.nodesb;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Base64;
import java.util.HexFormat;

public final class Util {

    private Util() {
    }

    public static int getFreePort() {
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("无法分配空闲端口", e);
        }
    }

    /** SS2022 密码：2022-blake3-aes-128-gcm 需要 16 字节 key，取 UUID 去横线后前 32 个十六进制字符 base64。 */
    public static String deriveSsPassword(String uuidStr) {
        String hex = uuidStr.replace("-", "");
        hex = hex.substring(0, Math.min(32, hex.length()));
        byte[] bytes = HexFormat.of().parseHex(hex);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 节点名称自动识别：国家代码 + ASN 运营商，识别失败退化为 "sb"。 */
    public static String detectNodeName(String presetName) {
        if (presetName != null && !presetName.isEmpty()) {
            return presetName;
        }
        String country = firstNonEmpty(
                HttpUtils.getText("https://ipinfo.io/country"),
                HttpUtils.getText("https://ifconfig.co/country-iso"));

        String asnOrg = firstNonEmpty(
                HttpUtils.getText("https://ipinfo.io/org"),
                HttpUtils.getText("https://ifconfig.co/org"));
        if (!asnOrg.isEmpty()) {
            asnOrg = asnOrg.replaceAll("^AS\\d+\\s+", "");
            asnOrg = asnOrg.replaceAll(",?\\s*Inc\\.?$", "");
            asnOrg = asnOrg.replaceAll(",?\\s*LLC\\.?", "");
            asnOrg = asnOrg.replaceAll(",?\\s*Ltd\\.?", "");
            asnOrg = asnOrg.replaceAll(",?\\s*Corp\\.?", "");
            asnOrg = asnOrg.strip();
            if (asnOrg.length() > 20) {
                asnOrg = asnOrg.substring(0, 20);
            }
        }

        if (!country.isEmpty() && !asnOrg.isEmpty()) {
            return country + "-" + asnOrg;
        }
        if (!country.isEmpty()) {
            return country + "-sb";
        }
        return "sb";
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b != null ? b : "");
    }
}
