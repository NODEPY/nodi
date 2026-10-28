# Nodi

Alpha. Not independently security-audited. LAN only.
Do not use for critical files.

[Українська](README.md)

## Overview

Direct encrypted file transfer between devices on the same local network.
No cloud or accounts.

Components: Go core and CLI, native Swift/AppKit macOS app, Kotlin Android app.
The Go and Swift components use no third-party libraries.

## Features

- QR pairing and LAN device discovery.
- File selection, sequential multi-file sending, cancellation and receiving into a chosen folder.
- TLS 1.3, certificate fingerprint checks and SHA-256 file verification.
- Existing files are not overwritten.
- Up to two incoming transfers, 2 GiB per file and a 30-minute transfer limit.

## Build

macOS: requires macOS 12+, Go and Xcode Command Line Tools.
Run `bash scripts/run-macos.sh`.

Android: minSdk 29, app version 1.0.
Open `android/` in Android Studio, or run `./gradlew assembleDebug` there.

## Pairing

On Mac, choose Add phone. On Android, choose Add device and scan the QR.
Devices must be on the same LAN; discovery uses mDNS.

## Limitations

No cross-network connection, folder transfer, resume or remote file browsing.
Receiving storage must support hard links: APFS does; FAT/exFAT do not.
The macOS build is ad-hoc signed, not notarized.
The initial Android alpha APK is a debug build.

## Checks

Run `go vet ./...` and `go test ./...`.
Additional checks: `go test -race ./...` and `python3 scripts/check-core.py`.
See [TESTING.md](TESTING.md) and [CLI documentation](docs/CLI.md).

## Roadmap — not implemented

Windows, Linux, cross-network connections, folder transfer and resume.

## License

Apache-2.0. See [LICENSE](LICENSE).
