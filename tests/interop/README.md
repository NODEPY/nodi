# Kotlin ↔ Go interoperability

Run from the repository root with Go and JDK 25 installed:

```sh
bash android/gradlew -p tests/interop test --no-daemon
```

The standalone JVM project compiles the production Android `NodiProtocol`,
`NodiReceiver`, `NodiPairing` and `NodiDevices` sources directly. It builds the Go
CLI from the current checkout and exchanges real TLS 1.3 traffic over loopback.
No Android SDK, emulator, external device or committed private key is required.
Gradle downloads Kotlin, JUnit and a JVM implementation of `org.json` on first use.

Coverage:

- Empty and 1 MiB binary files, Unicode filenames, byte-for-byte verification,
  duplicate-name rejection preserving original contents, in both directions.
- Unicode text/links and the exact 64 KiB UTF-8 boundary in both directions;
  text must not create destination files.
- Invalid tokens and certificate pins in both directions, followed by a valid
  transfer to ensure the receiver remains usable.
- Cancellation of a Kotlin upload after Go accepts the header, cleanup of both
  temporary files, and a successful retry with the same filename.
- Removal of a trusted device on Kotlin rejects subsequent Go file/text sends.

Each test has a 60-second deadline; subprocess waits and event queues are bounded.
Test reports: `build/reports/tests/test/index.html`. CI also saves XML/HTML reports.

Scope: this is a transport integration suite, not full Android end-to-end testing.
The receiver uses a test filesystem adapter instead of Storage Access Framework,
and fresh JVM-generated keys instead of AndroidKeyStore. Android TLS providers,
UI, service lifecycle, clipboard, mDNS, Wi-Fi pairing approval, revocation during
an active transfer, and physical-device/network behavior still need separate tests.
