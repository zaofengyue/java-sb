package com.nodesb;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 对应 Python 版的 run_argo_forward_server：只监听 127.0.0.1，
 * 只处理三个协议路径上的合法 WebSocket 握手请求，其余一律 400。
 */
public class ForwardServer implements Runnable {
    private static final Logger log = Logger.getLogger("ForwardServer");

    private final int port;
    private final Map<String, Integer> pathToPort;
    private final CountDownLatch bound = new CountDownLatch(1);

    public ForwardServer(int port, Map<String, Integer> pathToPort) {
        this.port = port;
        this.pathToPort = pathToPort;
    }

    /**
     * 阻塞等待端口真正 bind 成功（或超时）。调用方（Main）应该在起 cloudflared 隧道之前
     * 等这个方法返回，避免隧道一起来就把流量转发到一个还没监听的端口上。
     */
    public boolean awaitBound(long timeoutMillis) throws InterruptedException {
        return bound.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
        try (ServerSocket srv = new ServerSocket()) {
            srv.setReuseAddress(true);
            srv.bind(new InetSocketAddress("127.0.0.1", port));
            log.info("Argo 转发服务启动，端口 " + port);
            bound.countDown();
            ExecutorService pool = Executors.newCachedThreadPool();
            while (true) {
                Socket client = srv.accept();
                pool.submit(() -> handle(client));
            }
        } catch (IOException e) {
            log.severe("Argo 转发服务启动失败: " + e.getMessage());
            bound.countDown(); // 即使失败也要放行等待方，不然 Main 会一直卡住
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
            byte[] rest = RawHttpUtils.slice(raw, idx + 4, raw.length);
            String headerPart = new String(headerBytes, StandardCharsets.UTF_8);
            String requestLine = headerPart.split("\r\n", 2)[0];
            String path = RawHttpUtils.requestPath(requestLine);
            if (path == null) {
                client.close();
                return;
            }

            Integer targetPort = pathToPort.get(path);
            Map<String, String> headers = RawHttpUtils.parseHeaders(headerPart);
            if (targetPort == null || !RawHttpUtils.isWebSocketUpgrade(headers)) {
                sendBadRequest(client);
                return;
            }
            forward(client, headerBytes, rest, targetPort);
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    private void forward(Socket client, byte[] headerBytes, byte[] rest, int targetPort) {
        Socket upstream = null;
        try {
            upstream = new Socket();
            upstream.connect(new InetSocketAddress("127.0.0.1", targetPort), 5000);
            OutputStream out = upstream.getOutputStream();
            out.write(headerBytes);
            out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(rest);
            out.flush();

            Socket finalUpstream = upstream;
            Thread t1 = new Thread(() -> RawHttpUtils.pipe(client, finalUpstream));
            Thread t2 = new Thread(() -> RawHttpUtils.pipe(finalUpstream, client));
            t1.start();
            t2.start();
            t1.join();
            t2.join();
        } catch (IOException e) {
            log.fine("连接 sing-box 内部端口 " + targetPort + " 失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private void sendBadRequest(Socket client) {
        try {
            byte[] body = "Bad Request".getBytes(StandardCharsets.UTF_8);
            String resp = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(resp.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
