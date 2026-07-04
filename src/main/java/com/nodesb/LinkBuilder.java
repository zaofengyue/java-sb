package com.nodesb;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class LinkBuilder {

    private LinkBuilder() {
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String vmessLink(String name, String cfPreferHost, String uuid,
                                    String host, String wsPath) {
        JSONObject obj = new JSONObject();
        obj.put("v", "2");
        obj.put("ps", name);
        obj.put("add", cfPreferHost);
        obj.put("port", "443");
        obj.put("id", uuid);
        obj.put("aid", "0");
        obj.put("scy", "auto");
        obj.put("net", "ws");
        obj.put("type", "none");
        obj.put("host", host);
        obj.put("path", wsPath);
        obj.put("tls", "tls");
        obj.put("sni", host);
        String b64 = Base64.getEncoder().encodeToString(obj.toString().getBytes(StandardCharsets.UTF_8));
        return "vmess://" + b64;
    }

    public static String vlessArgoLink(String uuid, String cfPreferHost, String host,
                                        String wsPath, String name) {
        return "vless://" + uuid + "@" + cfPreferHost + ":443"
                + "?encryption=none&security=tls&sni=" + host + "&type=ws&host=" + host
                + "&path=" + enc(wsPath) + "#" + enc(name);
    }

    public static String trojanArgoLink(String trojanPass, String cfPreferHost, String host,
                                         String wsPath, String name) {
        return "trojan://" + trojanPass + "@" + cfPreferHost + ":443"
                + "?security=tls&sni=" + host + "&type=ws&host=" + host
                + "&path=" + enc(wsPath) + "#" + enc(name);
    }

    public static String hysteria2Link(String uuid, String publicIp, int port, String name) {
        return "hysteria2://" + uuid + "@" + publicIp + ":" + port
                + "?sni=www.bing.com&insecure=1&alpn=h3&obfs=none#" + enc(name);
    }

    public static String tuicLink(String uuid, String publicIp, int port, String name) {
        return "tuic://" + uuid + ":" + uuid + "@" + publicIp + ":" + port
                + "?sni=www.bing.com&congestion_control=bbr&udp_relay_mode=native&alpn=h3&allow_insecure=1"
                + "#" + enc(name);
    }

    public static String realityLink(String uuid, String publicIp, int port,
                                      String realityDomain, String pubKey, String name) {
        return "vless://" + uuid + "@" + publicIp + ":" + port
                + "?encryption=none&flow=xtls-rprx-vision&security=reality"
                + "&sni=" + realityDomain + "&fp=firefox&pbk=" + pubKey
                + "&type=tcp&headerType=none#" + enc(name);
    }

    public static String shadowsocksLink(String ssPass, String publicIp, int port, String name) {
        String userInfo = Base64.getEncoder().encodeToString(
                ("2022-blake3-aes-128-gcm:" + ssPass).getBytes(StandardCharsets.UTF_8));
        return "ss://" + userInfo + "@" + publicIp + ":" + port + "#" + enc(name);
    }

    public static String socksLink(String uuid, String publicIp, int port, String name) {
        String username = uuid.substring(0, 8);
        String password = uuid.substring(uuid.length() - 12);
        String userInfo = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "socks://" + userInfo + "@" + publicIp + ":" + port + "#" + enc(name);
    }

    public static String anytlsLink(String uuid, String publicIp, int port, String name) {
        return "anytls://" + uuid + "@" + publicIp + ":" + port
                + "?security=tls&sni=www.bing.com&fp=chrome&insecure=1&allowInsecure=1#" + enc(name);
    }

    public static String buildSubscription(List<String> links) {
        String joined = String.join("\n", links);
        return Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> newList() {
        return new ArrayList<>();
    }
}
