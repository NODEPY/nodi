# Changelog

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
