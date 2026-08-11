# Haha Remote

**用手机遥控 [cc-haha](https://github.com/NanmiCoder/cc-haha) 桌面会话的 Android 应用。**

出门在外也能继续电脑上的 cc-haha 会话：查看任务进度、补发指令、批准权限、上传文件——Codex 手机端式体验。电脑端应用保持运行，本应用只是一个遥控器。

## 功能

- 📷 **扫码连接** — 相机对准电脑屏幕上的二维码，完成
- 🔗 **深链** — 手机浏览器点开 H5 链接，直接唤起本应用并连接
- 💾 **多台电脑** — 保存多个地址，点选切换，长按重命名/删除
- 🟢 **连接状态灯** — 灰=未连接 黄=连接中 绿=已连接 红=连不上
- 🔄 **自动重连** — 网络闪断自动重试，Wi-Fi 恢复后自动连回
- 📱 **锁屏不断** — 手机锁屏时电脑任务照跑（WakeLock + 桌面端断连宽限）
- 🖼️ **文件双向** — 手机上传图片/文件，下载附件
- 💥 **崩溃自愈** — 浏览器内核被系统杀掉后自动重建，不白屏
- 🔐 **Token 加密** — 连接令牌用 Android Keystore AES-GCM 加密存储，不落明文
- 🌐 **中英双语**

## 工作原理

[cc-haha](https://github.com/NanmiCoder/cc-haha) 桌面应用内置本地 H5 服务（`设置 → H5 Access`）。本应用在手机浏览器内核中加载该页面——会话、消息、权限按钮、附件全部可用。数据只走局域网，不经过任何云端。

> ⚠️ **安全**：H5 链接含 token（等于你电脑的钥匙）。只在信任的网络开启 H5 Access，链接按密码对待。怀疑泄露就在 cc-haha 设置里重新生成 token。

## 安装

从 [Releases](https://github.com/yangxiangyou/haha-remote/releases) 下载 APK，安装到手机（提示"允许安装未知应用"时允许即可）。要求 **Android 8.0+**。

## 使用

1. 电脑上：打开 cc-haha 桌面应用 → **设置 → H5 Access** → 开启 **Enable H5 access** → 点 **Generate token**，屏幕出现二维码
2. 手机上：打开 Haha Remote → 点 **📷 扫码连接** → 对准屏幕
3. 完成。列表里点地址可切换电脑

另一种方式：在 cc-haha 里点 **Copy launch URL**，粘贴到应用里。

## 从源码构建

需要：JDK 17+、Android SDK（platform 34 / build-tools 34.0.0）、Gradle 8.9。

```bash
git clone https://github.com/yangxiangyou/haha-remote.git
cd haha-remote
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

生成密钥库：

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -alias haha \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Haha Remote, OU=Personal, O=Haha Remote, L=Unknown, ST=Unknown, C=CN"
```

> ⚠️ **务必备份密钥库和密码**：丢了就无法用同一签名更新已安装的 App。

### CI

GitHub Actions 每次推送自动构建 + 测试；推送 `v*` tag 自动发布 APK 到 Releases：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

CI 里要出正式签名包，添加 secrets（Settings → Secrets and variables → Actions）：`KEYSTORE_BASE64`（release.keystore 的 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。未配置时 CI 回退出 debug 签名包。

## 隐私

- 无账号、无统计、无任何网络请求（除了你配置的地址）
- Token 用 Android Keystore 加密存储
- 桌面应用必须运行才能遥控——本应用不保存你的代码和会话数据

## 许可证

[MIT](LICENSE)
