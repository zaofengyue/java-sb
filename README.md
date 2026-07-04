# node-sb (Java 版)

基于 sing-box 内核的多协议代理工具，Node.js 版 `index.js` 的 Java 翻译版本。支持 Argo 隧道下的
VMess / VLESS / Trojan（WS + TLS），以及可选的 Hysteria2 / TUIC v5 / VLESS Reality /
Shadowsocks 2022 / Socks5 / AnyTLS。仅支持 Linux 部署。

产物是单个 `server.jar`（fat jar，已包含唯一的外部依赖 `org.json`），拿到目标机器上
`java -jar server.jar` 直接运行，不需要额外安装依赖包；但**修改代码后需要重新构建**，不能像
Python/Node 版那样改完源码直接运行——这是 Java 编译型语言本身决定的，不是这份代码的限制。

## 构建

需要 JDK 17+ 和 Maven：

```bash
mvn -B package
```

构建产物在 `target/server.jar`。仓库配了 GitHub Actions（`.github/workflows/release.yml`），
打 `v*` 格式的 tag 会自动构建并把 `server.jar` 发布到对应的 GitHub Release，也可以手动触发
（Actions 页面 "Run workflow"）。

## 运行

```bash
java -jar server.jar
```

如果要自定义落地页，把 `index.html` 放在跟 `server.jar` 同一个工作目录下即可（不放则使用内置的
极简状态页）。

## 环境变量

| 变量名 | 说明 | 默认值 |
|---|---|---|
| `UUID` | VMess/VLESS 统一 ID，同时也是 Trojan 密码、Shadowsocks/Socks5/TUIC 凭据的派生来源 | 自动生成并持久化到 `~/uuid.txt` |
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
| `NAME` | 节点名称前缀 | 自动识别（国家代码-ASN 运营商），识别失败则为 `sb` |

也可以不设环境变量，直接改 `Main.java` 开头的 `CONF_*` 常量（重新构建生效），优先级高于环境变量。

## 数据文件位置

运行时数据默认存放在 `$HOME`（即启动进程的用户主目录）下：

- `uuid.txt`：持久化的 UUID
- `sb-config.json`：生成的 sing-box 配置
- `sing-box/`：下载的 sing-box 二进制 + `run.log` 运行日志
- `cloudflared`：下载的 cloudflared 二进制
- `certs/`：Hysteria2/TUIC/AnyTLS 用的自签证书
- `reality-keys.json`：持久化的 Reality 密钥对

`sub.txt`（订阅内容）写在当前工作目录，不是 `$HOME`。

## 已知限制 / 设计取舍（沿用自 Node 版，未改动）

- Trojan 密码直接复用 `UUID`，Shadowsocks/Socks5/TUIC 的凭据也是从 `UUID` 派生，不是独立生成，
  `UUID` 泄露等于所有协议凭据一起泄露。
- 系统缺少 `openssl` 时会用源码内置的共享测试证书兜底——这个私钥是公开的，仅适用于个人测试，
  不要在生产/对外服务场景依赖这个兜底路径。
- Hysteria2/TUIC/AnyTLS 客户端配置都带 `insecure=1`/`allowInsecure=1`（跳过证书校验），配合自
  签证书使用，本质上放弃了 TLS 对中间人攻击的防护，是自签证书场景的常见取舍。
- 固定 Argo 隧道模式不校验隧道是否真的连接成功，只是等待 3 秒后就把 `ARGO_DOMAIN` 当作可用域
  名去生成分享链接。
- 临时隧道域名获取有 30 秒超时，超时后 Argo 三协议的分享链接会使用占位域名 `your-domain.com`
  （生成的链接连不通，需要检查 cloudflared 是否正常联网）。

## 与 Node.js 版的差异

- 去掉了所有 Windows 兼容分支，仅支持 Linux。
- 端口冲突检测范围更大：Node 版只检查可选协议之间是否冲突；这个版本额外把伪装页端口
  （`PORT`）和 Argo 内部固定端口（10000/10001/10002、`ARGO_PORT`）也纳入冲突检测。
- tar.gz 解压直接调用系统 `tar` 命令（`--strip-components=1`），没有引入额外的解压依赖库。
