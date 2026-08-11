# Haha Remote

**Remote control for [cc-haha](https://github.com/NanmiCoder/cc-haha) desktop sessions — from your Android phone.**

Continue your cc-haha session on the road: check task progress, send follow-up instructions, approve permissions, upload files — a Codex-style mobile experience. The desktop app stays on your computer; this app is just a remote control.

## Features

- 📷 **Scan to connect** — in-house CameraX scanner (no third-party camera activity)
- 🔗 **Deep link** — tap an H5 link on your phone to open this app and connect
- 💾 **Multiple hosts** — save addresses for several computers, tap to switch, long-press to rename/delete
- 🟢 **Live connection indicator** — grey = idle, yellow = connecting, green = connected, red = unreachable
- 🔄 **Auto-reconnect** — transient network drops retry automatically; recovers when Wi-Fi returns
- 📱 **Lock-screen safe** — tasks keep running on the desktop while your phone is locked
- 🖼️ **Files both ways** — upload images/files from your phone, download attachments
- 📱 **Narrow-screen fix** — injects CSS so cc-haha's bottom toolbar doesn't overlap on phones ≤380dp
- 💥 **Crash self-healing** — WebView renderer rebuilds itself; crashes show a report screen with one-tap copy
- 🔐 **Encrypted tokens** — connection tokens AES-GCM encrypted with the Android Keystore
- 🌐 **English & 中文**

## How it works

The [cc-haha](https://github.com/NanmiCoder/cc-haha) desktop app exposes a local H5 service (`Settings → H5 Access`). This app loads that page in a phone-optimized WebView — sessions, messages, permission buttons and attachments all work. Nothing is stored in the cloud.

## Connecting — pick your scenario

### 1. Same network (LAN) — simplest

Phone and computer on the same Wi-Fi (or the computer's LAN is reachable): enter the computer's LAN address directly, e.g. `http://192.168.1.20:PORT`. **No tunnel, no server needed.**

### 2. Cloud computer / remote access — fixed domain (recommended for always-on)

Cloud desktop (or any computer without a public IP) + your own VPS + your own domain = a **permanent address that never changes**, survives reboots:

```
 Phone ──https://your-domain.com──► your VPS (nginx + HTTPS cert)
                                         │  frp tunnel (frps)
                                         ▼
                              cloud computer (frpc) ──► cc-haha H5
```

- VPS runs `frps` (frp server) + nginx reverse proxy with a Let's Encrypt certificate
- Cloud computer runs `frpc` (frp client) as a background service, auto-start on boot
- Phone always uses `https://your-domain.com/?token=...` — reboots, IP changes, nothing to update

Full step-by-step guide (server + client + nginx + certbot + auto-start): [docs/self-hosted-frp.zh-CN.md](docs/self-hosted-frp.zh-CN.md) (Chinese)

### 3. Quick tunnel — no server at all (cloudflared)

No VPS, no domain: `cloudflared tunnel --url http://localhost:PORT` gives you a free `https://xxx.trycloudflare.com` URL in seconds. **Downside: the URL changes every time the tunnel restarts.** Good for a quick test or occasional use: [docs/remote-access.zh-CN.md](docs/remote-access.zh-CN.md)

## Security

- ⚠️ The H5 link contains a **token that unlocks your computer**. Treat it like a password: never share it, never post it in a group chat.
- If you suspect a leak, regenerate the token in cc-haha settings (`Settings → H5 Access → Regenerate token`).
- When exposing over the internet, always use **HTTPS** (own domain + certificate) — never plain HTTP with a token.
- If you run a reverse proxy, add its origin to **Allowed origins** in cc-haha H5 settings, otherwise the token check rejects the connection.
- Connection tokens are stored AES-GCM-encrypted in the Android Keystore, never in plaintext.

## Install

Download the latest APK from the [Releases](https://github.com/yangxiangyou/haha-remote/releases) page and install it on your phone (allow "install unknown apps"). Requires **Android 8.0+**.

## Build from source

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34.0.0), Gradle 8.9.

```bash
git clone https://github.com/yangxiangyou/haha-remote.git
cd haha-remote
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle test assembleDebug       # debug APK (installs alongside release)
gradle assembleRelease          # release APK (needs keystore.properties, see below)
```

### Release signing

Create `keystore.properties` (gitignored, never commit):

```properties
storeFile=keystore/release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=haha
keyPassword=YOUR_KEY_PASSWORD
```

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -alias haha \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Haha Remote, OU=Personal, O=Haha Remote, L=Unknown, ST=Unknown, C=CN"
```

> ⚠️ **Back up your keystore and passwords.** Without them you cannot update an installed app with the same signature.

### CI

GitHub Actions builds and tests every push; pushing a `v*` tag publishes an APK to Releases:

```bash
git tag v1.0.6 && git push origin v1.0.6
```

For signed release builds in CI, add these secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them CI falls back to a debug-signed APK.

## Privacy

- No accounts, no analytics, no network calls except to the addresses you configure.
- This repository contains **no private keys, tokens, or personal infrastructure details** — bring your own domain/VPS.
- Tokens are encrypted at rest with the Android Keystore.
- The desktop app must be running for remote control to work.

## License

[MIT](LICENSE)
