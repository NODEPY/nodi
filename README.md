<div align="center">
  <img src="android/app/src/main/assets/NodiMark.png" width="96" alt="Nodi">
  <h1>Nodi</h1>
  <p><strong>Your files. Your devices. One local network.</strong></p>
  <p>Encrypted file, text and link transfer between macOS and Android — without cloud storage or accounts.</p>

[![Go checks](https://github.com/NODEPY/nodi/actions/workflows/go.yml/badge.svg)](https://github.com/NODEPY/nodi/actions/workflows/go.yml)
![Status](https://img.shields.io/badge/status-alpha-f59e0b)
![Platforms](https://img.shields.io/badge/platforms-macOS%20%7C%20Android-6366f1)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

[Downloads](https://github.com/NODEPY/nodi/releases) · [What's new](CHANGELOG.md) · [Testing](TESTING.md) · [CLI](docs/CLI.md) · [Security](SECURITY.md)
</div>

---

## Meet Alpha 6 — Nodi over the Internet

Your Mac and phone no longer need to share a network. Create an encrypted **room**
on one device, enter its 16-character code on the other and approve the connection.
After that, paired devices send files and text over Wi-Fi when they can and through
the encrypted Internet relay when they cannot — no IP addresses, ports or VPN.
The relay only forwards end-to-end TLS traffic: it never sees file contents or text.
Manual IPv4/VPN routes remain available for advanced setups.

| Connect | Transfer | Stay in control |
| --- | --- | --- |
| Pair across networks with a room code | Automatic LAN or Internet route | Approve every room join |
| Discover devices on your LAN | Send multiple files in sequence | Confirm a new connection |
| Start pairing from either device | Send text and links from the clipboard | Cancel selection or transfer |
| Keep QR as an alternative | Choose your destination folder | Remove saved devices |
| Confirm on either screen | Preview received images on Android | Copy or open received text |

**Experimental alpha:** LAN discovery, encrypted Internet relay with rooms and manual IPv4/VPN routes.
No independent security audit.
See the known limitations and test status below before relying on a build.

## Quick start

1. Install Nodi on your Mac and Android phone.
2. Open Nodi on both devices. On the same Wi-Fi, find the other device and start connecting;
   on different networks, create a room in **Інтернет** on one device and join with its code on the other.
3. Compare the displayed codes and confirm, or scan the Mac QR on Android.
4. On the receiving device, choose a folder and enable receiving. Select files on the sender.
   Text and links do not need a destination folder; use the Text tab on Android or the
   text card on Mac.

Android discovery and pairing work before selecting a receiving folder.
Install the Android APK over your existing installation to retain app data when signatures match.
The macOS archive contains `Nodi.app`; current builds are ad-hoc signed, not notarized.

## Versioning

This source version is **1.0.0-alpha.6** (tag `v1.0.0-alpha.6`).
See Downloads for published builds.
Use `MAJOR.MINOR.PATCH-alpha.N` for prereleases and `MAJOR.MINOR.PATCH` for stable releases.
Android `versionName` matches the release version; `versionCode` increases for each distributed build (this build: 6).
macOS uses numeric `CFBundleShortVersionString = 1.0.0` and `CFBundleVersion = 8`;
its release tag identifies the alpha. Increase the build number for each distributed build.
Go **1.22** is the minimum language/toolchain requirement in `go.mod`, not the app version.
Go 1.23.2 in earlier testing notes described a test environment, not a separate requirement.
Use a maintained Go toolchain for release builds; minimum-version compatibility still needs testing.

## Features

- QR pairing and Wi-Fi pairing with comparison-code confirmation; mDNS discovery.
- Internet rooms for first pairing across networks; automatic relay fallback for paired devices.
- Optional manual IPv4:port route per paired device for VPN setups.
- A connection request can start on either device; the other shows a confirmation prompt.
- Android tabs: Connections, Receive, Send and Text.
- Discovery and pairing do not require an Android destination folder; receiving files does.
- Sequential multi-file sending, cancellation and receiving into a chosen folder.
- Text and link transfer up to 64 KiB, with automatic clipboard copy and a session history of the last 10 items.
- Android received-file preview, Open and Share actions.
- TLS 1.3, certificate fingerprint checks and SHA-256 verification.
- Existing files are not overwritten; up to two incoming transfers and 2 GiB per file.

## Build

macOS: macOS 12+, Go 1.22+ and Xcode Command Line Tools.
Run `bash scripts/run-macos.sh`.
The script prefers `MacOSX26.sdk` from Command Line Tools when installed (newer SDKs can be
incompatible with the installed Swift), otherwise uses the `xcrun` default, and uses a fresh
compiler cache. Set `SDKROOT` to override the SDK.

Android: minSdk 29, compileSdk/targetSdk 37. Open `android/` in Android Studio,
or run `bash ./gradlew assembleDebug` inside that directory with Android SDK and compatible JDK installed.
The APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Pairing and receiving

Open Nodi on both devices on the same LAN. Use Find in Wi-Fi, select the discovered
peer and start connecting. Compare the displayed codes and approve on both screens.
QR remains an alternative: show the Mac pairing QR and scan it on Android.
Discovery alone does not establish trust.
On Android select a destination folder and enable receiving only when files are needed.
On Mac the destination can be changed while the receiver is running; the receiver restarts.

## Limitations

Over the Internet: up to 16 MiB per file and 10 minutes per session; receiving must stay enabled
and polling can add up to 10 seconds of delay. The private-alpha relay accepts up to 64 device
identities and 16 simultaneous transfers. No NAT traversal or offline delivery.
No folder transfer, resume or remote file browsing.
Text history is kept in memory only and is lost when the app closes.
The Go receiver requires a destination filesystem supporting hard links (for example APFS;
FAT/exFAT do not). Android uses a document-provider folder and has separate permission semantics.
macOS builds are ad-hoc signed, not notarized; Android alpha builds use debug signing.
For known test gaps and the device checklist, see [TESTING.md](TESTING.md).

## Manual address / VPN

Pair devices first, connect your VPN, then select a saved device → **Address / VPN**.
Enter the other receiver's IPv4 address and current port. This route is saved and
used for both files and text; **Automatic** restores discovery-based routing.
The dialog also shows this device's receiver addresses. No new trust is granted
by entering an address. See [setup and limits](docs/VPN-ADDRESS.md).

## Internet rooms

When a direct TCP connection fails, paired devices automatically use the
Cloudflare relay. The existing pinned TLS runs inside it, so the relay never
sees file contents or text. To pair across networks, create a room in the
**Інтернет** section on one device, share the 16-character code privately,
join with it on the other device and approve the request on the host. Codes
expire after five minutes. See [Internet alpha](docs/INTERNET-ALPHA.md) and the
[relay server](server/cloudflare/README.md).

Privacy: the relay operator (Cloudflare and the Nodi author) can see device IDs, IP
addresses, timing and traffic size, but not contents. Usage statistics are off by
default and only daily pseudonymous counters are sent if you opt in. The Feedback
button sends your text to the author.

## Sharing from other apps

On Android, select one or more files in another app, choose **Share → Nodi**,
then choose the paired device and tap **Send**. Text and links open the Text tab.
Up to 100 shared files are accepted; the source app must grant read access.
On Mac, drop up to 100 regular files onto the Nodi window, then click **Send**.
Folders are not supported. New shares/drops do not replace an active transfer.

## Checks

```bash
go vet ./...
go test -race ./...
python3 scripts/check-core.py
# Kotlin ↔ Go transport integration (JDK 25, no Android SDK required):
bash android/gradlew -p tests/interop test --no-daemon
```

See [CLI documentation](docs/CLI.md). Windows, Linux desktop apps, NAT traversal,
offline delivery, folder transfer and resume remain roadmap items.

## License

Apache-2.0. See [LICENSE](LICENSE).
