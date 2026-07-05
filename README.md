# java-sb 

基于 sing-box 内核的多协议代理工具，支持 Argo 隧道下的 VMess / VLESS / Trojan（WS + TLS），以及可选的 Hysteria2 / TUIC v5 / VLESS Reality /
Shadowsocks 2022 / Socks5 / AnyTLS。仅支持 Linux 部署。


## 部署方式

需要 JDK 17+ 和 Maven：

```bash
mvn -B package
```

构建产物在 `target/server.jar`。仓库配了 GitHub Actions（`.github/workflows/release.yml`），
打 `v*` 格式的 tag 会自动构建并把 `server.jar` 发布到对应的 GitHub Release，也可以手动触发
（Actions 页面 "Run workflow"）。

运行：

```bash
java -jar server.jar
```

如果要自定义落地页，把 `index.html` 放在跟 `server.jar` 同一个工作目录下即可（不放则使用内置的
极简状态页）。

## 环境变量

也可以不设环境变量，直接改源码里的配置区，重新构建后生效，优先级高于环境变量：
👉 [`Main.java` 的 `CONF_*` 配置区](src/main/java/com/nodesb/Main.java#L21-L41)

| 变量名 | 说明 | 默认值 |
|---|---|---|
| `UUID` | VMess/VLESS 统一 ID，同时也是 Trojan 密码、Shadowsocks/Socks5/TUIC 凭据的派生来源 | 自动生成并持久化到 `world/uuid.txt` |
| `PORT` | 容器内实际监听端口（伪装页 + 订阅），部署平台通常会自动注入 | 自动分配空闲端口 |
| `SUB` | 订阅路径 | `sub` |
| `DISABLE_ARGO` | 填 `true` 禁用 Argo 隧道（VMess/VLESS/Trojan 三协议），只保留下面的可选协议 | 留空即启用 |
| `ARGO_DOMAIN` / `ARGO_AUTH` | 固定 Argo 隧道用的域名和 Token（都填才生效）；只填一个或都不填则用临时隧道（`trycloudflare.com` 域名，重启会变） | 留空 |
| `ARGO_PORT` | 固定隧道模式下，容器内 Argo 转发服务监听的端口 | `8001` |
| `HY2_PORT` | 填端口号启用 Hysteria2（UDP），需要证书 | 留空不启用 |
| `TUIC_PORT` | 填端口号启用 TUIC v5（UDP），需要证书 | 留空不启用 |
| `REALITY_PORT` | 填端口号启用 VLESS Reality（TCP） | 留空不启用 |
| `REALITY_DOMAIN` | Reality 用来做握手伪装的目标域名 | `www.iij.ad.jp` |
| `SS_PORT` | 填端口号启用 Shadowsocks 2022（TCP） | 留空不启用 |
| `S5_PORT` | 填端口号启用 Socks5（TCP） | 留空不启用 |
| `ANYTLS_PORT` | 填端口号启用 AnyTLS（TCP），需要证书，且要求 sing-box ≥ 1.12.0 | 留空不启用 |
| `NAME` | 节点名称前缀 | 自动识别 |
| `CLEANUP_AFTER_DEPLOY` | 部署成功、生成订阅后是否自动清理 sing-box 发行包里用不到的附带文件（`LICENSE`/`README.md` 等），设为 `0`/`false`/`no` 可关闭 | `true` |
