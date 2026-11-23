# Changelog

## 1.0.0-alpha.6 — 2026-11-23

### ✨ New

- **Nodi over the Internet.** Paired devices try a direct connection first and automatically fall back to an encrypted Cloudflare relay. The existing pinned TLS 1.3 runs inside the relay, which never sees file contents or text.
- **Rooms** for first pairing across networks: one device creates a 16-character code (valid 5 minutes), the other joins, the host approves. Only a code digest and an AES-256-GCM blob reach the server.
- Devices self-enroll with proof of possession of their certificate key; relay credentials are stored in AndroidKeyStore-encrypted storage or the macOS Keychain.
- Opt-in, pseudonymous usage counters (off by default) and an in-app Feedback button.
- Per-paired-device manual IPv4:port routing on Android and macOS for VPN/direct connections; Automatic resets the route. Android shows VPN IPv4 interfaces.

### 🧪 Tests

- Miniflare relay tests, Go relay/room tests and Kotlin ↔ Go interoperability, including public relay runs.
- Physical acceptance on Mac (Wi-Fi) ↔ Galaxy S21 FE (mobile data): rooms both directions, files/text both ways, restart, cancellation, wrong and expired codes.

### ⚠️ Limits

- Internet files up to 16 MiB; receiving must stay enabled; up to 10 s polling delay.
- Private-alpha relay capacity: 64 device identities, 16 simultaneous transfers.
- Statistics opt-in behaviour has automated coverage only.

## 1.0.0-alpha.5 — 2026-11-03

- Fix the Go CI matrix: exclude macOS + Go 1.22 inside `matrix`, keeping Go 1.22 coverage on Ubuntu and stable Go on both platforms.
- Align Android versionName/versionCode (1.0.0-alpha.5 / 5), macOS build number (7), and README.
- Consolidate Alpha 4 notes, including text transfer, without duplicate Unreleased entries.
- Package freshly built Android APK and Apple Silicon macOS app ZIP from the release commit, with SHA-256 checksums and a build report.
- Preserve the existing Alpha 4 tag and assets. Manual device/UI validation and Strix assessment remain incomplete.

## 1.0.0-alpha.4 — 2026-11-02

- Android: accept one or multiple files through Share → Nodi; shared text and links open the Text tab. Sending still requires an explicit tap.
- macOS: drop up to 100 regular files into the window, with drop feedback and protection against replacing an active transfer.
- Add standalone Kotlin ↔ Go TLS integration tests and CI reports.
- macOS builds use a fresh module cache and support SDKROOT for compiler/SDK compatibility.

### ✨ New

- **Text and link transfer.** Paste a snippet or URL on one device, send it to a paired device, and copy or open it there.
  Incoming text is copied to the clipboard automatically and kept in an in-memory history of the last 10 items.
- **Android Text tab** with Paste from clipboard, Send text, Clear, and Copy/Open actions per history entry.
- **macOS text card** with the same actions, plus the `nodi send-text` CLI command.
- Text payloads are limited to 64 KiB, use the existing TLS 1.3 pinned channel, and never touch the receiving folder.

### 🧪 Tests & CI

- Go tests for text round-trips, handler rejection, unsupported-receiver rejection and payload validation.

## 1.0.0-alpha.3 — 2026-10-30

## Nodi Alpha 3 — Connect over Wi-Fi

A simpler connection flow, a refreshed Android interface and a stronger Go test suite.

### ✨ New

- **Wi-Fi pairing without scanning a QR.** Start from either device, compare the code and confirm the connection.
- **Three Android tabs:** Connections, Receive and Send.
- **Received-file cards:** image previews, file details, Open and Share actions, and an arrival animation.
- System-derived device names and a connected-state animation on both platforms.

### 🛠 Fixes & improvements

- Android discovery and pairing no longer require choosing a receiving folder first.
- Restore active Android folder access from the persisted grant after restarting Nodi.
- Cancel selected files before sending, as well as transfers already in progress.
- Change the receiving folder on macOS while the receiver is running; it restarts with the new destination.
- Improve Android file opening through MIME-type handling and URI permissions.
- Keep QR pairing available as an alternative.

### 🧪 Tests & CI

- DeviceBook tests for identity persistence, locking, corrupt storage, QR expiry/reuse, duplicate tokens, device limits and revocation.
- Pairing tests for commit/reveal, invalid nonces, certificate pin mismatch, rejection, timeout and comparison codes.
- Forget/Commit race coverage: revoked transfers cannot finalize a new file after Forget returns.
- Partial-file cleanup tests, including a subprocess crash fixture.
- GitHub Actions configured for Ubuntu/macOS and Go 1.22/stable: vet, race tests and CLI integration.

Go vet, race tests and CLI integration passed locally on Linux (Go 1.23.12) and macOS (Go 1.27.2).
This is not a claim that GitHub Actions or full device/UI regression has passed; check Actions and TESTING.md.

### Downloads & compatibility

- **Nodi-macOS.zip:** macOS 12+; built for the publishing Mac's architecture.
- **Nodi-Android-alpha.apk:** Android 10+; debug-signed alpha APK.
- **SHA256SUMS.txt:** SHA-256 hashes for both downloads.
- Update both devices for the new Wi-Fi flow. QR remains available.

### Known limitations

LAN only. No folder transfer, resume or cross-network connection.
Abrupt process termination can leave `.nodi-*.part` files; crash recovery is planned for the next stage.
macOS builds are ad-hoc signed and not notarized. No independent security audit.

**Changes since the previous push:** [00f1d43 → Alpha 3](https://github.com/NODEPY/nodi/compare/00f1d43a6583ab41afafbcd8ef03f6313bc2c5e2...v1.0.0-alpha.3).

---

### Українською

Wi-Fi-підключення з підтвердженням коду без обов'язкового QR; три вкладки Android;
оновлені картки отриманих файлів із прев'ю, відкриттям і поширенням.
Пошук працює без вибору папки, скасування очищає вибрані файли,
а папку на Mac можна змінювати під час роботи приймача.
Додано тести довіри до пристроїв, парингу, гонок і тимчасових файлів та CI.


## Earlier releases

See [Alpha 2](https://github.com/NODEPY/nodi/releases/tag/v1.0.0-alpha.2) and [Alpha 1](https://github.com/NODEPY/nodi/releases/tag/v1.0.0-alpha.1).
