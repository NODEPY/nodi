# Changelog

## Unreleased

- Switching Wi-Fi / Internet no longer changes the screen: «Find on Wi-Fi», «Room», QR and Address / VPN stay in place in both modes; only the route and the hint change.
- Internet transfers: files up to 48 MiB (was 16 MiB) and 2–4× faster — the tunnel packs TLS records into 64 KiB relay frames, so far fewer frames and acknowledgements are needed. Works with the current relay; no server change.
- macOS: Internet credentials move from the Keychain to a private file next to the device identity (`~/Library/Application Support/Nodi/Devices/internet.json`, 0600). Updates no longer ask for the login password; the first launch after updating moves the old Keychain entry once.
- Wi-Fi / Internet toggle on Android and macOS replaces the two connect buttons. Wi-Fi sends only directly (up to 2 GiB); Internet goes straight through the relay without the 4-second direct attempt. The choice is remembered; receiving still works both ways. CLI: `-route auto|wifi|internet` for `send` and `send-text`.
- «Support the author» button in the Feedback dialog on Android and macOS; it opens the support section of the website. Support links on the website and in the README.

## 1.0.0-alpha.9 — 2026-11-24

### ⚡ Faster Internet transfers, room for more people

- Receivers now long-poll the relay: a transfer starts as soon as it is sent instead of after up to 10 seconds, and each receiving device makes ~5x fewer requests.
- Relay polling and forwarding no longer write to storage, so the free Cloudflare plan is no longer exhausted by a handful of devices.
- Relay capacity raised to 1000 devices and 32 simultaneous transfers. Alpha 6–8 keep working unchanged.
- Fixed: a device paired through a room while the receiver was waiting could have its first transfer rejected.

### 🌍 Getting Nodi

- Landing page in `site/` (English by default, Ukrainian and Russian on request; picks the right download for the visitor's device, Mac install command, Android QR on other desktops), served as free static assets on Cloudflare: https://getnodi.pages.dev
- macOS one-command installer (`scripts/install-macos.sh`): downloads the latest release, verifies its SHA-256, installs into /Applications and keeps settings. No Gatekeeper prompt because curl downloads are not quarantined.
- README: macOS install section with the macOS 15+ path (System Settings → Privacy & Security → Open Anyway); the right-click → Open shortcut no longer works there.

## 1.0.0-alpha.8 — 2026-11-24

### 🌐 Fully translated

- English and Russian interfaces no longer show Ukrainian text: statuses (for example "Internet on"), transfer and network errors, Android notifications and messages from the Go core are all translated.
- Error messages that combine a reason and a diagnostic code are translated part by part.
- macOS: the Edit menu, the Add phone window and the QR accessibility label are translated.

### 🛠 Fixes

- Duplicate translation keys removed; a duplicate key would crash the macOS app at launch.

### 🧪 Tests & CI

- `scripts/check-translations.py` runs in CI and fails on any untranslated UI string, non-English English text, Ukrainian letters in Russian text or duplicate keys.

## 1.0.0-alpha.7 — 2026-11-24

### ✨ New look

- **"Aurora night" design** on Android and macOS: inky background, violet→cyan gradient buttons with a soft glow, bordered cards and bolder typography.
- **Internet** is now a button right next to **Find on Wi-Fi**; QR, Address / VPN and Feedback are clear icon rows.
- Android: dark branded dialogs, icons on the bottom tabs, readable inputs in dialogs.
- macOS: titlebar blends into the window; text field and buttons follow the new palette.

### 🛠 Fixes

- Internet room and feedback dialogs are translated into English and Russian (they were always shown in Ukrainian).
- Disabled buttons now look disabled.

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
