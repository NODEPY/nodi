# Nodi handoff — manual address / VPN

Updated: 2026-11-03. Base main: 5293cf3 (Alpha 5).
Working branch: codex/manual-vpn-address. Final checkpoint: `git log -1` on this branch.
User requested manual address/VPN support and progress reports. Implementation and
local verification complete; no push, PR or new release made for this feature.
Published Alpha 5 and its tag must remain unchanged.

## Implemented

- Android and Mac: Address / VPN dialog for already-paired devices; save numeric
  IPv4:port override and reset with Automatic. Display this receiver's endpoints.
- Persist override per certificate fingerprint. Override takes precedence over
  mDNS for file AND text sends. Initial pairing still uses existing QR/Wi-Fi flow.
- Preserve peer token and exact certificate pin. Saving an address never grants
  trust, sends content or probes a server. Manual label does not imply online.
- Android receiver address list includes VPN and other active IPv4 interfaces.
- Strict matching Kotlin and Swift validation; current receivers bind IPv4,
  so DNS/IPv6 support is intentionally deferred.
- Added @main NodiApplication because the Mac app now has two Swift source files.
- New guide docs/VPN-ADDRESS.md; README and Unreleased changelog updated.

## Verification — all final local checks passed

- `bash android/gradlew -p android assembleDebug testDebugUnitTest`: PASS.
  Android unit tests remain template tests, not physical share/VPN UI validation.
- `bash android/gradlew -p tests/interop test --no-daemon`: PASS, 9 tests:
  2 endpoint validation tests + 7 real Kotlin/Go TLS interoperability tests.
  New manual-route test sends file/text with preserved token/pin, rejects wrong pin.
- `SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk bash scripts/check-endpoint-macos.sh`:
  PASS, 21 Swift parser cases.
- `NODI_NO_OPEN=1 SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk bash scripts/run-macos.sh`:
  PASS, optimized compile, plist validation, deep/strict ad-hoc signature verification.
- `git diff --check` and shell syntax: PASS.
- Added Swift parser step to macOS Go CI; remote CI has not run for this branch.
- Earlier Mac build failures (multi-file entry point / edited source during compile)
  were resolved; final source was built without concurrent edits.

## Local outputs

- Android: android/app/build/outputs/apk/debug/app-debug.apk
- Mac: build/Nodi.app
- These are development builds after Alpha 5, with unchanged Alpha 5 version fields.
  Do not upload as Alpha 5 replacement artifacts. Bump versions for a future release.

## Next steps / manual acceptance

1. Pair Android/Mac using existing flow, connect VPN on both, restart receivers.
2. Put devices on different Wi-Fi networks; set each peer's VPN IPv4:current-port.
3. Send file and text BOTH directions; compare received bytes/text.
4. Test restart persistence, Automatic reset, stale port, unreachable endpoint,
   invalid input, and attempting to point at a device with a different certificate.
5. Validate UI on both physical platforms, including Android VPN permission/routing.
6. If publication requested, push branch, run CI, open PR; choose future alpha
   version/build numbers and rebuild artifacts from its final commit.

## Boundaries

No relay, no NAT traversal, no guaranteed Internet connectivity without an existing
VPN/routable TCP. VPN provider infrastructure may still be involved. IPv4 only.
Dynamic receiver ports can change on restart. Address display is refreshed when
receiver starts; connect VPN first. This task did not install/configure a VPN.
Physical dual-network testing not done. Strix remains incomplete from earlier work.

## Inherited issue found (not changed)

Root .gitignore has unanchored `nodi`, hiding new files inside dev/nodi packages as
well as the root binary. New NodiEndpoint.kt and EndpointTest.kt are explicitly
staged with `git add -f`; check they are in the checkpoint commit. Prefer changing
this rule to `/nodi` after user confirmation. Prior instruction requires confirming
corrections to the other agent's work; no change to that rule was made here.

## Key files

- android/.../MainActivity.kt: manualEndpoint, editEndpoint, renderNearby,
  fillSelectedDevice. Preferences: nodi_endpoints, fingerprint key.
- android/.../NodiEndpoint.kt; NodiReceiveService.kt: localAddresses.
- macos/Nodi.swift: manualEndpoint, endpoint(for:), editEndpoint, sendFile/sendText.
  Preferences: nodi.endpoint.<fingerprint>.
- macos/NodiEndpoint.swift and tests/endpoint/main.swift.
- tests/interop/.../EndpointTest.kt, GoInteropTest.kt.
- CI (Go 1.22.x ubuntu, Go stable macos/ubuntu, interop) passed for PR #2 (checks 2) — verified 2026-11-03.
