package com.nodesb;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ConfigBuilder {

    private ConfigBuilder() {
    }

    public static JSONObject baseConfig() {
        JSONObject config = new JSONObject();
        JSONObject logCfg = new JSONObject();
        logCfg.put("level", "warn");
        logCfg.put("timestamp", false);
        config.put("log", logCfg);
        config.put("inbounds", new JSONArray());
        JSONArray outbounds = new JSONArray();
        JSONObject direct = new JSONObject();
        direct.put("type", "direct");
        direct.put("tag", "direct");
        outbounds.put(direct);
        config.put("outbounds", outbounds);
        return config;
    }

    public static JSONObject argoInbound(String type, String tag, int port, String wsPath,
                                          String uuid, String trojanPass) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", type);
        inbound.put("tag", tag);
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", port);

        JSONArray users = new JSONArray();
        JSONObject user = new JSONObject();
        switch (type) {
            case "vmess" -> {
                user.put("uuid", uuid);
                user.put("alterId", 0);
            }
            case "vless" -> {
                user.put("uuid", uuid);
                user.put("flow", "");
            }
            case "trojan" -> user.put("password", trojanPass);
            default -> throw new IllegalArgumentException("未知协议: " + type);
        }
        users.put(user);
        inbound.put("users", users);

        JSONObject transport = new JSONObject();
        transport.put("type", "ws");
        transport.put("path", wsPath);
        inbound.put("transport", transport);
        return inbound;
    }

    public static JSONObject hysteria2Inbound(int port, String uuid, String certPath, String keyPath) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "hysteria2");
        inbound.put("tag", "hy2-in");
        inbound.put("listen", "::");
        inbound.put("listen_port", port);
        JSONArray users = new JSONArray().put(new JSONObject().put("password", uuid));
        inbound.put("users", users);
        inbound.put("masquerade", "https://bing.com");
        inbound.put("tls", tlsBlock(certPath, keyPath, "h3"));
        return inbound;
    }

    public static JSONObject tuicInbound(int port, String uuid, String certPath, String keyPath) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "tuic");
        inbound.put("tag", "tuic-in");
        inbound.put("listen", "::");
        inbound.put("listen_port", port);
        JSONObject user = new JSONObject();
        user.put("uuid", uuid);
        user.put("password", uuid);
        inbound.put("users", new JSONArray().put(user));
        inbound.put("congestion_control", "bbr");
        inbound.put("tls", tlsBlock(certPath, keyPath, "h3"));
        return inbound;
    }

    public static JSONObject realityInbound(int port, String uuid, String realityDomain, String privateKey) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "vless");
        inbound.put("tag", "reality-in");
        inbound.put("listen", "::");
        inbound.put("listen_port", port);
        JSONObject user = new JSONObject();
        user.put("uuid", uuid);
        user.put("flow", "xtls-rprx-vision");
        inbound.put("users", new JSONArray().put(user));

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", realityDomain);
        JSONObject reality = new JSONObject();
        reality.put("enabled", true);
        JSONObject handshake = new JSONObject();
        handshake.put("server", realityDomain);
        handshake.put("server_port", 443);
        reality.put("handshake", handshake);
        reality.put("private_key", privateKey);
        reality.put("short_id", new JSONArray().put(""));
        tls.put("reality", reality);
        inbound.put("tls", tls);
        return inbound;
    }

    public static JSONObject shadowsocksInbound(int port, String password) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "shadowsocks");
        inbound.put("tag", "ss-in");
        inbound.put("listen", "::");
        inbound.put("listen_port", port);
        inbound.put("network", "tcp");
        inbound.put("method", "2022-blake3-aes-128-gcm");
        inbound.put("password", password);
        return inbound;
    }

    public static JSONObject socksInbound(int port, String username, String password) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "socks");
        inbound.put("tag", "s5-in");
        inbound.put("listen", "::");
        inbound.put("listen_port", port);
        JSONObject user = new JSONObject();
        user.put("username", username);
        user.put("password", password);
        inbound.put("users", new JSONArray().put(user));
        return inbound;
    }

    public static JSONObject anytlsInbound(int port, String uuid, String certPath, String keyPath) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "anytls");
        inbound.put("tag", "anytls-in");
        inbound.put("listen", "::");
        inbound.put("listen_port", port);
        inbound.put("users", new JSONArray().put(new JSONObject().put("password", uuid)));
        inbound.put("tls", tlsBlock(certPath, keyPath, null));
        return inbound;
    }

    private static JSONObject tlsBlock(String certPath, String keyPath, String alpn) {
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        if (alpn != null) {
            tls.put("alpn", new JSONArray().put(alpn));
        }
        tls.put("certificate_path", certPath);
        tls.put("key_path", keyPath);
        return tls;
    }
}
