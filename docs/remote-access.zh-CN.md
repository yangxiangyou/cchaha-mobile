# 远程连接（云电脑 / 跨网络）傻瓜教程

> 场景：手机和电脑**不在同一个 Wi-Fi**（比如电脑是云电脑、你在外面），只要两边都有网就能连。
> 原理：用 Cloudflare 的免费隧道，给电脑上的 cc-haha 服务生成一个 **https 公网地址**，手机连这个地址。

## 一、电脑上拿公网地址（约 5 分钟）

1. 下载 cloudflared（Windows 版）：
   打开 https://github.com/cloudflare/cloudflared/releases/latest
   下载 `cloudflared-windows-amd64.exe`，放到桌面，**重命名为 `cloudflared.exe`**

2. 打开 PowerShell（开始菜单搜 PowerShell，回车）

3. 输入下面命令回车（进入桌面目录）：
   ```
   cd Desktop
   ```

4. 启动隧道（**50287 换成你 cc-haha 显示的实际端口**）：
   ```
   .\cloudflared.exe tunnel --url http://localhost:50287
   ```

5. 等 3~10 秒，屏幕上会出现类似这样的一行：
   ```
   https://random-words-123.trycloudflare.com
   ```
   这行 `https://...trycloudflare.com` 就是你电脑的公网地址，**复制它**

> ⚠️ 这个窗口要一直开着（别关）。隧道地址每次重启都会变，变了就用新地址。

## 二、手机上连接

1. 电脑上打开 cc-haha → 设置 → H5 Access → 点 **Copy launch URL**，会复制一个类似
   `http://172.16.x.x:50287/?token=xxxxx` 的链接
2. 把这个链接里 **`http://172.16.x.x:50287`** 这部分，替换成刚才复制的 **`https://xxx.trycloudflare.com`**，得到：
   `https://xxx.trycloudflare.com/?token=xxxxx`
3. 手机打开 Haha Remote → 粘贴这个替换好的地址 → 连接

> 提示：token 部分（`?token=xxxxx`）很重要，不能丢。

## 三、手机连不上？按顺序查

1. 电脑上隧道窗口还开着吗？（关了地址就失效）
2. 手机浏览器直接打开 `https://xxx.trycloudflare.com/?token=xxxxx` 试试——浏览器能开，App 就能开
3. 电脑上 cc-haha 的 H5 Access 开关还开着吗？
4. 端口对不对？电脑上 cc-haha 显示的链接里的端口是多少就填多少

## 四、进阶：不想每次换地址？

免费隧道地址每次重启都变。要固定地址，用 Cloudflare 账号 + 命名隧道（`cloudflared tunnel create`），或改用 frp + 自己的 VPS 方案。

## 五、安全提醒

- 隧道地址 + token = 你电脑的钥匙，别发群里/朋友圈
- 用完可关掉隧道窗口（同时建议把 H5 Access 开关关掉）
- 怀疑泄露：cc-haha 设置里点 Regenerate token 重新生成
