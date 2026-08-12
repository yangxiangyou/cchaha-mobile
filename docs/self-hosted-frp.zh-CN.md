# 自建固定地址隧道（frp + VPS + 域名）完整教程

> 目标：让手机通过 **永久不变的 https 域名** 遥控任何电脑上的 cc-haha（云电脑、家里电脑都行）。
> 电脑重启、IP 变化都不用改手机设置。

## 架构

```
 手机 ──https://your-domain.com──► VPS（nginx + HTTPS 证书）
                                       │  frps（frp 服务端，端口 7000）
                                       ▼
                          目标电脑（frpc 客户端）──► 本机 cc-haha H5（localhost:端口）
```

- **VPS**：一台有公网 IP 的 Linux 服务器（腾讯云/阿里云/海外均可）
- **域名**：一个自己的域名，DNS 解析到 VPS IP
- **目标电脑**：跑 cc-haha 的电脑（云电脑/家里电脑），装 frpc 客户端

## 准备

| 需要 | 说明 |
|---|---|
| VPS（公网 IP） | 示例 `YOUR_VPS_IP`，本文用 Ubuntu |
| 域名 | 示例 `your-domain.com`，DNS 托管处加 A 记录 |
| 目标电脑 | Windows 或 Linux，cc-haha 运行中，H5 Access 已开启 |

## 步骤 1：VPS 装 frps（frp 服务端）

```bash
# 下载 frp（到 GitHub releases 找最新版：https://github.com/fatedier/frp/releases）
wget https://github.com/fatedier/frp/releases/download/v0.61.0/frp_0.61.0_linux_amd64.tar.gz
tar -xzf frp_0.61.0_linux_amd64.tar.gz && cd frp_0.61.0_linux_amd64
sudo mkdir -p /srv/frp && sudo cp frps /srv/frp/
```

配置 `/srv/frp/frps.toml`（token 用随机长字符串）：

```toml
bindPort = 7000
auth.method = "token"
auth.token = "换成随机长字符串"
```

systemd 常驻（开机自启 + 崩溃自动重启）：

```bash
sudo tee /etc/systemd/system/frps.service > /dev/null << 'EOF'
[Unit]
Description=frp server
After=network.target

[Service]
ExecStart=/srv/frp/frps -c /srv/frp/frps.toml
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now frps
sudo systemctl is-active frps   # 输出 active 即成功
```

**记得在 VPS 安全组/防火墙放行 `7000` 端口（TCP）**，否则客户端连不上。

## 步骤 2：VPS 装 nginx 反代 + HTTPS 证书

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

写反代配置 `/etc/nginx/conf.d/remote.conf`（**50887 换成你下面步骤 3 选的 remotePort**）：

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://127.0.0.1:50887;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # WebSocket 支持（H5 实时通信用）
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

**先让域名解析生效再签证书**（DNS A 记录：`your-domain.com → YOUR_VPS_IP`，走你的 DNS 托管商）。

签发 HTTPS 证书（certbot 自动配置 443 + HTTP 跳 HTTPS）：

```bash
sudo certbot --nginx -d your-domain.com --agree-tos --email you@example.com --redirect
```

证书自动续期（certbot 默认装好 systemd timer，无需手动）。

**记得在 VPS 安全组放行 `443` 端口。**

## 步骤 3：目标电脑装 frpc（客户端）

下载 Windows 版 frp：https://github.com/fatedier/frp/releases （`frp_0.61.0_windows_amd64.zip`），解压出 `frpc.exe`。

写 `frpc.toml`（与 frps.toml 的 token 一致；`localPort` 填 cc-haha H5 的实际端口——在 cc-haha 设置 → H5 Access 里看；`remotePort` 与步骤 2 nginx 里的端口一致）：

```toml
serverAddr = "YOUR_VPS_IP"
serverPort = 7000
auth.method = "token"
auth.token = "与 frps.toml 相同的 token"

[[proxies]]
name = "cc-haha-h5"
type = "tcp"
localIP = "127.0.0.1"
localPort = 38678        # cc-haha H5 端口
remotePort = 50887       # 与 nginx 配置一致
```

启动（PowerShell）：

```powershell
frpc.exe -c frpc.toml
```

看到 `login to server success` + `proxy added` 即连接成功。

### 开机自启（Windows）

把以下内容存成 `start-frpc.bat`，复制到启动文件夹（按 `Win+R` 输入 `shell:startup` 回车）：

```bat
@echo off
cd /d D:\你的目录
start "" /min frpc.exe -c frpc.toml
```

> 提示：cc-haha 重启后 H5 端口可能变化，改 `frpc.toml` 的 `localPort` 再重启 frpc 即可。

## 步骤 4：DNS 解析

在你的 DNS 托管商（域名服务商）加一条记录：

| 类型 | 名称 | 值 |
|---|---|---|
| A | your-domain.com | YOUR_VPS_IP |

等几分钟生效（可 `nslookup your-domain.com 8.8.8.8` 检查）。

## 步骤 5：cc-haha 允许来源

1. 电脑上打开 cc-haha → **设置 → H5 Access**
2. **Allowed origins** 填入 `https://your-domain.com`（和 `http://your-domain.com`）→ 保存
3. 如果保存后仍报 token 错：重启 cc-haha 桌面应用

## 验证

手机浏览器直接打开 `https://your-domain.com/?token=xxx`（token 从电脑 cc-haha 的 Copy launch URL 复制），能打开页面即全部就绪。然后在 cchaha Mobile App 里输入同样地址。

## 排错

| 现象 | 原因/解决 |
|---|---|
| 手机打不开 | ① VPS 安全组 7000/443 放行了吗 ② frpc 还在跑吗 ③ DNS 生效了吗 |
| 报 "Unable to verify the H5 access token" | Allowed origins 没加域名，或改了没生效（重启 cc-haha） |
| frpc 报 login 失败 | frps token 不一致 / 7000 端口没放行 |
| 页面能开但连接就断 | nginx 缺 WebSocket 头（Upgrade/Connection），对照步骤 2 补上 |

## 安全提醒

- frp token、域名、H5 token 都是你的钥匙，不要公开
- 手机地址始终用 HTTPS，不要用明文 HTTP
- 公网暴露建议关闭 H5 的"允许局域网"类选项（如有），只信任 Allowed origins
