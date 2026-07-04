package com.nodesb;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public final class CertUtils {
    private static final Logger log = Logger.getLogger("CertUtils");

    // ⚠️ 安全警示：以下为共享兜底证书，仅适用于个人测试/学习场景。
    // 该私钥已写入源码、随项目公开传播，任何使用此兜底路径的部署实例
    // 用的都是同一套私钥。生产环境或对外提供服务，请务必安装 openssl
    // 让上面的分支生成你自己独有的证书，不要依赖这段兜底。
    private static final String FALLBACK_PRIVATE_KEY = """
            -----BEGIN EC PARAMETERS-----
            BggqhkjOPQMBBw==
            -----END EC PARAMETERS-----
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEIM4792SEtPqIt1ywqTd/0bYidBqpYV/++siNnfBYsdUYoAoGCCqGSM49
            AwEHoUQDQgAE1kHafPj07rJG+HboH2ekAI4r+e6TL38GWASANnngZreoQDF16ARa
            /TsyLyFoPkhLxSbehH/NBEjHtSZGaDhMqQ==
            -----END EC PRIVATE KEY-----""";

    private static final String FALLBACK_CERT = """
            -----BEGIN CERTIFICATE-----
            MIIBejCCASGgAwIBAgIUfWeQL3556PNJLp/veCFxGNj9crkwCgYIKoZIzj0EAwIw
            EzERMA8GA1UEAwwIYmluZy5jb20wHhcNMjUwOTE4MTgyMDIyWhcNMzUwOTE2MTgy
            MDIyWjATMREwDwYDVQQDDAhiaW5nLmNvbTBZMBMGByqGSM49AgEGCCqGSM49AwEH
            A0IABNZB2nz49O6yRvh26B9npACOK/nuky9/BlgEgDZ54Ga3qEAxdegEWv07Mi8h
            aD5IS8Um3oR/zQRIx7UmRmg4TKmjUzBRMB0GA1UdDgQWBBTV1cFID7UISE7PLTBR
            BfGbgkrMNzAfBgNVHSMEGDAWgBTV1cFID7UISE7PLTBRBfGbgkrMNzAPBgNVHRMB
            Af8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIAIDAJvg0vd/ytrQVvEcSm6XTlB+
            eQ6OFb9LbLYL9f+sAiAffoMbi4y/0YUSlTtz7as9S8/lciBF5VCUoVIKS+vX2g==
            -----END CERTIFICATE-----""";

    private CertUtils() {
    }

    public record CertPaths(String keyPath, String certPath) {
    }

    public static void securePermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception e) {
            log.warning("设置文件权限失败 " + path + ": " + e.getMessage());
        }
    }

    public static CertPaths generateSelfSignedCert(File certDir) throws IOException {
        certDir.mkdirs();
        Path keyPath = certDir.toPath().resolve("key.pem");
        Path certPath = certDir.toPath().resolve("cert.pem");

        if (Files.exists(keyPath) && Files.exists(certPath)) {
            return new CertPaths(keyPath.toString(), certPath.toString());
        }

        try {
            ProcessUtils.Result r = ProcessUtils.runCapture(List.of(
                    "openssl", "req", "-x509", "-newkey", "ec",
                    "-pkeyopt", "ec_paramgen_curve:P-256", "-days", "3650", "-nodes",
                    "-keyout", keyPath.toString(), "-out", certPath.toString(),
                    "-subj", "/CN=bing.com/O=Microsoft/C=US"
            ));
            if (r.ok() && Files.exists(keyPath) && Files.exists(certPath)) {
                securePermissions(keyPath);
                return new CertPaths(keyPath.toString(), certPath.toString());
            }
        } catch (IOException e) {
            // openssl 不存在或执行失败，走下面的兜底分支
        }

        log.warning("[警告] 系统缺少 openssl，将使用源码内置的共享测试证书"
                + "（私钥已公开，仅供个人测试，请勿用于生产/对外服务）");
        Files.writeString(keyPath, FALLBACK_PRIVATE_KEY, StandardCharsets.UTF_8);
        Files.writeString(certPath, FALLBACK_CERT, StandardCharsets.UTF_8);
        securePermissions(keyPath);
        return new CertPaths(keyPath.toString(), certPath.toString());
    }
}
