# Nodi

Direct encrypted file transfer between devices on the same local network.
No cloud or accounts. Go core/CLI, native Swift/AppKit macOS app and Kotlin Android app.
Alpha software; not independently security-audited. LAN only.

## Versioning

The next development release is **1.0.0-alpha.3** (tag `v1.0.0-alpha.3`).
This does not mean a release has been published.
Use `MAJOR.MINOR.PATCH-alpha.N` for prereleases and `MAJOR.MINOR.PATCH` for stable releases.
Android `versionName` matches the release version; `versionCode` increases for each distributed build (this build: 3).
macOS uses numeric `CFBundleShortVersionString = 1.0.0` and `CFBundleVersion = 6`;
its release tag identifies the alpha. Increase the build number for each distributed build.
Go **1.22** is the minimum language/toolchain requirement in `go.mod`, not the app version.
Go 1.23.2 in earlier testing notes described a test environment, not a separate requirement.
Use a maintained Go toolchain for release builds; minimum-version compatibility still needs testing.

## Features

- QR pairing and Wi-Fi pairing with comparison-code confirmation; mDNS discovery.
- A connection request can start on either device; the other shows a confirmation prompt.
- Android tabs: Connections, Receive and Send.
- Discovery and pairing do not require an Android destination folder; receiving files does.
- Sequential multi-file sending, cancellation and receiving into a chosen folder.
- Android received-file preview, Open and Share actions.
- TLS 1.3, certificate fingerprint checks and SHA-256 verification.
- Existing files are not overwritten; up to two incoming transfers and 2 GiB per file.

## Build

macOS: macOS 12+, Go 1.22+ and Xcode Command Line Tools.
Run `bash scripts/run-macos.sh`.
The script currently hard-codes the MacOSX26.5 SDK path; another SDK installation may require adjusting it.

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

No cross-network connection, folder transfer, resume or remote file browsing.
The Go receiver requires a destination filesystem supporting hard links (for example APFS;
FAT/exFAT do not). Android uses a document-provider folder and has separate permission semantics.
macOS builds are ad-hoc signed, not notarized; Android alpha builds use debug signing.
For known test gaps and the device checklist, see [TESTING.md](TESTING.md).

## Checks

```bash
go vet ./...
go test -race ./...
python3 scripts/check-core.py
```

See [CLI documentation](docs/CLI.md). Windows, Linux desktop apps, cross-network
connections, folder transfer and resume remain roadmap items.

## License

Apache-2.0. See [LICENSE](LICENSE).
