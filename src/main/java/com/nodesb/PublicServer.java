package com.nodesb;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * 对应 Python 版的 run_public_server：伪装页 + 订阅内容，
 * 监听 0.0.0.0，独立于 Argo 转发服务。
 */
public class PublicServer implements Runnable {
    private static final Logger log = Logger.getLogger("PublicServer");

    private final int port;
    private final String subPath;
    private final String indexHtml;
    private final AtomicReference<String> subContent;

    public PublicServer(int port, String subPath, String indexHtml, AtomicReference<String> subContent) {
        this.port = port;
        this.subPath = subPath;
        this.indexHtml = indexHtml;
        this.subContent = subContent;
    }

    @Override
    public void run() {
        try (ServerSocket srv = new ServerSocket()) {
            srv.setReuseAddress(true);
            srv.bind(new InetSocketAddress("0.0.0.0", port));
            log.info("HTTP 服务启动，端口 " + port);
            ExecutorService pool = Executors.newCachedThreadPool();
            while (true) {
                Socket client = srv.accept();
                pool.submit(() -> handle(client));
            }
        } catch (IOException e) {
            log.severe("HTTP 服务启动失败: " + e.getMessage());
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10000);
            byte[] raw = RawHttpUtils.recvHeaders(client.getInputStream(), 65536);
            int idx = RawHttpUtils.indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            if (idx < 0) {
                client.close();
                return;
            }
            byte[] headerBytes = RawHttpUtils.slice(raw, 0, idx);
            String headerPart = new String(headerBytes, StandardCharsets.UTF_8);
            String requestLine = headerPart.split("\r\n", 2)[0];
            String path = RawHttpUtils.requestPath(requestLine);
            if (path == null) {
                client.close();
                return;
            }

            byte[] body;
            String contentType;
            if (path.equals(subPath)) {
                body = subContent.get().getBytes(StandardCharsets.UTF_8);
                contentType = "text/plain; charset=utf-8";
            } else {
                body = indexHtml.getBytes(StandardCharsets.UTF_8);
                contentType = "text/html; charset=utf-8";
            }

            String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            client.close();
        } catch (IOException e) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }
}
