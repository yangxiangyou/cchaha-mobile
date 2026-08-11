# Haha Remote

**Remote control for [cc-haha](https://github.com/NanmiCoder/cc-haha) desktop sessions — from your Android phone.**

Continue your cc-haha session on the road: check task progress, send follow-up instructions, approve permissions, upload files — all from a phone, with a Codex-style mobile experience. The desktop app stays on your computer; this app is just a remote control.

## Features

- 📷 **Scan to connect** — point the camera at the QR code on your computer screen, done
- 🔗 **Deep link** — tap an H5 link on your phone to open this app and connect directly
- 💾 **Multiple hosts** — save addresses for several computers, tap to switch, long-press to rename/delete
- 🟢 **Live connection indicator** — grey = idle, yellow = connecting, green = connected, red = unreachable
- 🔄 **Auto-reconnect** — transient network drops retry automatically; recovers when Wi-Fi returns
- 📱 **Lock-screen safe** — tasks keep running on the desktop while your phone is locked (wake-lock + the desktop's disconnect grace)
- 🖼️ **Files both ways** — upload images/files from your phone, download attachments
- 💥 **Crash self-healing** — the WebView renderer rebuilds itself if the OS kills it
- 🔐 **Encrypted tokens** — connection tokens are AES-GCM encrypted with the Android Keystore, never stored in plaintext
- 🌐 **English & 中文**

## How it works

The [cc-haha](https://github.com/NanmiCoder/cc-haha) desktop app exposes a local H5 service (`Settings → H5 Access`). This app loads that page in a phone-optimized WebView — sessions, messages, permission buttons and attachments all work. Nothing goes through a cloud; everything stays on your LAN.

> ⚠️ **Security**: the H5 link contains a token that unlocks your computer. Only enable H5 Access on networks you trust, and treat the link like a password. If you suspect a leak, regenerate the token in cc-haha settings.

## Install

Download the latest APK from the [Releases](https://github.com/yangxiangyou/haha-remote/releases) page, then install it on your phone (allow "install unknown apps" when prompted). Requires **Android 8.0+**.

## Usage

1. On your computer: open the cc-haha desktop app → **Settings → H5 Access** → enable **Enable H5 access** → tap **Generate token**. A QR code appears.
2. On your phone: open Haha Remote → tap **Scan QR** → point at the screen.
3. Done. Tap addresses in the list to switch computers.

Alternative: tap **Copy launch URL** in cc-haha and paste it into the app.

## Build from source

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34.0.0), Gradle 8.9.

```bash
git clone https://github.com/yangxiangyou/haha-remote.git
cd haha-remote
# point Gradle at your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle test assembleDebug       # debug APK (installs alongside release)
gradle assembleRelease          # release APK (needs keystore.properties, see below)
```

### Release signing

Create `keystore.properties` (never commit it — it's gitignored):

```properties
storeFile=keystore/release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=haha
keyPassword=YOUR_KEY_PASSWORD
```

Generate the keystore with:

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -alias haha \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Haha Remote, OU=Personal, O=Haha Remote, L=Unknown, ST=Unknown, C=CN"
```

> ⚠️ **Back up your keystore and passwords.** Without them you cannot update an installed app with the same signature.

### CI

GitHub Actions builds and tests every push, and publishes an APK to Releases when you push a `v*` tag:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

For signed release builds in CI, add these secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` (base64 of release.keystore), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them, CI falls back to a debug-signed APK.

## Privacy

- No accounts, no analytics, no network calls except to the addresses you configure.
- Tokens are encrypted at rest with the Android Keystore.
- The desktop app must be running for remote control to work — this app never stores your code or session data.

## License

[MIT](LICENSE)
