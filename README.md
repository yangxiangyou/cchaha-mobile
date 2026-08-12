# cchaha Mobile

**用手机遥控 [cc-haha](https://github.com/NanmiCoder/cc-haha) 桌面会话的 Android 应用。**

出门在外也能继续电脑上的 cc-haha 会话：查看任务进度、补发指令、批准权限、上传文件——Codex 手机端式体验。电脑端应用保持运行，本应用只是一个遥控器。

> **上游项目**：[cc-haha](https://github.com/NanmiCoder/cc-haha)（本应用遥控的桌面工作区）。应用图标与品牌形象取自 cc-haha，按其 [MIT 许可证](https://github.com/NanmiCoder/cc-haha/blob/main/LICENSE) 使用。

## 功能

- 📷 **扫码连接** — 自研 CameraX 扫码（不依赖第三方相机页面，兼容性更稳）
- 🔗 **深链** — 手机浏览器点开 H5 链接，直接唤起本应用并连接（未知地址需确认）
- 💾 **设备管理** — 多台电脑（设备）保存与切换；**点击设备直接进入 cc-haha**；长按可**编辑**（地址/token/名称）、重命名、删除；地址与 token 分两行输入
- 🎨 **cc-haha 原版风格** — 首页与会话列表采用原版设计语言（白底、双 C 品牌 Logo、朱红主色、大圆角）
- 💬 **原生会话列表** — 秒开（本地缓存先行），后台刷新；30 秒内不重复拉取；下拉刷新、搜索
- ⚡ **原生消息页** — 所有会话默认原生打开：消息缓存秒开、30 秒自动轮询、锁屏新回复**推送通知**、思考/工具/结果块级渲染（可折叠展开）、智能滚动
- 🔄 **自动更新检查** — 国内网络可用（镜像加速），有新版本弹窗提示
- 🟢 **连接状态灯** — 灰=未连接 黄=连接中 绿=已连接 红=连不上
- 🔄 **自动重连** — 网络闪断自动重试（带防误触保护），Wi-Fi 恢复后自动连回
- 📱 **锁屏不断** — 手机锁屏时电脑任务照跑
- 🖼️ **文件双向** — 手机上传图片/文件，下载附件
- 📱 **窄屏适配** — 自动注入样式，cc-haha 底部操作栏在 ≤380dp 手机上不再重叠
- 💥 **崩溃自愈** — 浏览器内核崩溃自动重建；真崩溃时显示错误详情页，一键复制反馈；错误信息自动脱敏
- 🔐 **Token 加密** — 连接令牌用 Android Keystore AES-GCM 加密存储
- 🌐 **中英双语** — 全量文案双语，跟随系统语言

## 工作原理

[cc-haha](https://github.com/NanmiCoder/cc-haha) 桌面应用内置本地 H5 服务（`设置 → H5 Access`）。本应用优先走**原生通道**（会话列表/消息直连 API，秒开且不依赖网页加载）；完整 H5 界面（权限按钮、附件、设置等）保留为"完整版"兜底。数据不经过任何云端存储。

## 三种连接方式（按场景选）

### 1. 同一网络（局域网）— 最简单

手机和电脑在同一 Wi-Fi（或电脑局域网可达）：App 里直接输入电脑局域网地址，如 `http://192.168.1.20:端口`。**不需要隧道、不需要服务器。**

### 2. 云电脑 / 远程 — 固定域名（长期使用推荐）

云桌面（或任何没有公网 IP 的电脑）+ 自己的 VPS + 自己的域名 = **永久不变的地址**，重启无忧：

```
 手机 ──https://你的域名──► 你的 VPS（nginx + HTTPS 证书）
                                   │  frp 隧道（frps）
                                   ▼
                        云电脑（frpc 客户端）──► cc-haha H5
```

- VPS 跑 `frps`（frp 服务端）+ nginx 反代 + Let's Encrypt 证书
- 云电脑跑 `frpc`（frp 客户端）后台常驻，开机自启
- 手机永远用 `https://你的域名/?token=...`——重启、换 IP 都不用管

完整图文教程（服务端 + 客户端 + nginx + 证书 + 自启）：[docs/self-hosted-frp.zh-CN.md](docs/self-hosted-frp.zh-CN.md)

### 3. 快速隧道 — 无需任何服务器（cloudflared）

没有 VPS、没有域名也能用：`cloudflared tunnel --url http://localhost:端口` 几秒钟拿到免费 `https://xxx.trycloudflare.com` 地址。**缺点：每次隧道重启地址会变。** 适合临时测试或偶尔使用：[docs/remote-access.zh-CN.md](docs/remote-access.zh-CN.md)

## 安全须知

- ⚠️ H5 链接含 **token（等于你电脑的钥匙）**：按密码对待，绝不发群里/朋友圈
- 怀疑泄露：cc-haha 设置 → H5 Access → **Regenerate token** 立即作废旧 token
- 公网使用**必须 HTTPS**（自己的域名 + 证书），不要用明文 HTTP 传 token
- 走反向代理（域名/隧道）时，必须把域名加进 cc-haha H5 设置的 **Allowed origins**，否则 token 校验会拒绝连接
- 连接令牌在手机端用 Android Keystore AES-GCM 加密存储，不落明文

## 安装

从 [Releases](https://github.com/yangxiangyou/cchaha-mobile/releases) 下载 APK 安装到手机（提示"允许安装未知应用"时允许即可）。要求 **Android 8.0+**。

## 从源码构建

需要：JDK 17+、Android SDK（platform 34 / build-tools 34.0.0）、Gradle 8.9。

```bash
git clone https://github.com/yangxiangyou/cchaha-mobile.git
cd cchaha-mobile
echo "sdk.dir=/path/to/android-sdk" > local.properties   # 指向你的 SDK
gradle test assembleDebug       # debug 包（可与正式版共存安装）
gradle assembleRelease          # 正式包（需要 keystore.properties，见下）
```

### 正式签名

创建 `keystore.properties`（已被 gitignore，绝不提交）：

```properties
storeFile=keystore/release.keystore
storePassword=你的密码
keyAlias=haha
keyPassword=你的密码
```

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -alias haha \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Haha Remote, OU=Personal, O=Haha Remote, L=Unknown, ST=Unknown, C=CN"
```

> ⚠️ **务必备份密钥库和密码**：丢了就无法用同一签名更新已安装的 App。

### CI

GitHub Actions 每次推送自动构建 + 测试；推送 `v*` 标签自动发布 APK 到 Releases：

```bash
git tag v1.0.6 && git push origin v1.0.6
```

CI 里要出正式签名包，添加 secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。未配置时 CI 回退出 debug 签名包。

## 隐私

- 无账号、无统计、无任何网络请求（除了你配置的地址）
- **本仓库不含任何私钥、令牌或个人基础设施信息**——域名/VPS 都是你自己的
- Token 用 Android Keystore 加密存储
- 桌面应用必须运行才能遥控——本应用不保存你的代码和会话数据

## 许可证

[MIT](LICENSE)
