# Current checkpoint — Internet relay (private alpha server)

Updated: 2026-11-03. Branch `codex/internet-relay`, based on PR #2 HEAD
`26c67eb`. PR #2 is still unmerged and `main` was not modified. No deployment,
app integration, PR, merge, tag or release was made.

## Implemented

- `internal/relay` + `cmd/nodi-relay`: loopback-only Go rendezvous prototype
  with its own open TCP protocol; unchanged in this checkpoint except review.
- `server/cloudflare`: Worker + SQLite-backed Durable Object relay with HTTPS/WSS
  enforcement, operator-provisioned device credentials, mutual peer grants,
  private polling presence, single-use role-bound tickets, revocation, bounded
  duplex framing, flow control, quotas and DO-alarm expiry.
- Five-second JSON body deadline and safe outbound WebSocket sends that close the
  session instead of throwing when the peer socket has just gone away.
- Tests use Miniflare/workerd, plus a TLS 1.3 pinned-certificate transport test.
- `.github/workflows/relay.yml`: Node 22, npm cache on
  `server/cloudflare/package-lock.json`, `npm ci`, `npm test`, `npm run check`.

## Verified locally (actual command results)

- `npm ci` in `server/cloudflare`: added 42 packages, 0 vulnerabilities.
- `npm test`: 15 tests, 15 pass. Three consecutive final runs after all edits:
  15 pass / 0 fail each, about 36.9–37.0 s per run. The unclaimed-ticket test
  intentionally takes about 31 s.
- `npm run check`: `wrangler 4.157.0 deploy --dry-run` passed; 10.73 KiB upload
  (3.40 KiB gzip); no deploy.
- Node v22.23.4 / npm 10.9.9. `compatibility_date` 2026-08-06 is accepted by
  Miniflare 4.20260730.0 and Wrangler 4.157.0.
- `node --check src/worker.js` and `node --check test/relay.test.js`: pass.
- `go vet ./...`: pass. `go test -race -count=1 ./...`: pass
  (`internal/relay` 1.730 s, `internal/transfer` 3.797 s).
- `git diff --check`: pass. CI workflow YAML parses; it was inspected but not
  run as GitHub Actions.
- README numbers checked against `src/worker.js`: 64 devices, 16 sessions,
  2 sessions/device, 120 requests/device/minute, 45 s presence, 30 s
  rendezvous, 10 min paired lifetime, 64 KiB frame, 64 MiB tunnel bytes,
  4096 frames/session, 1024-byte JSON body, 5 s body deadline. Device/global
  session/frame/byte ceilings are code-confirmed; most are not exercised at
  their exact boundary by tests.

## Fixes in this checkpoint

- The reported failure of “inner TLS rejects an incorrect certificate pin”
  did not reproduce in 10 targeted runs. The test bridge could still call
  WebSocket `send()`/`close()` after teardown. It now guards `readyState`,
  handles socket errors, and asserts the receiver got zero application bytes
  after pin rejection. The test still requires a `pin mismatch` error.
- Added tests for a stalled body (rejected after ~5 s) and a truncated body
  (rejected immediately); neither registers the device. A hard client-side
  abort cannot be observed as an HTTP response through Miniflare, so that
  transport case is covered by truncation plus the timeout.
- README corrected: exact `LOCAL_TEST` hosts (`localhost`/`127.0.0.1`), and the
  5 s body deadline is now documented. The test-coverage sentence was updated.
- Server-side authenticated presence and single-use ticket issuance now exist.
  This supersedes the old “next: authenticated presence/ticket issuance” note
  for the Cloudflare server; the Go prototype still lacks them.

## Security review notes

- Tickets are distinct per role/session, checked against the authenticated
  device, consumed before the 101 response and persisted before it is visible.
  Revocation, unpairing, cancellation and expiry delete sessions and close
  sockets. No mutual-authorization bypass was found.
- Admin token is never logged or returned. Tokens are compared as SHA-256
  digests, not with a constant-time routine; a leaked digest prefix is not
  enough to authenticate, but this remains an open review question.
- HTTPS is required; `LOCAL_TEST` only permits `localhost`/`127.0.0.1`. It must
  never be set on a deployed Worker.
- Open availability questions: the 5 s body read runs inside
  `blockConcurrencyWhile`, so an authenticated slow-body request can stall the
  single Durable Object for up to 5 s. Admin requests have no per-token rate
  limit and depend on edge controls. The 64 MiB check runs after adding a frame,
  so teardown can occur up to one frame past the advertised cap.

## Not verified

- No Cloudflare deployment, account configuration, secret, real HTTPS/WSS or
  domain; no paid hosting was purchased.
- No real Android/macOS client integration or secure enrollment UI; no traffic
  through this relay from physical devices on different networks or mobile data.
- No Android/Go TLS interoperability test against this WebSocket relay; the TLS
  test is a transport test. The Go prototype has a different protocol.
- No load, soak, restart/deployment-interruption, free-plan quota or edge abuse
  testing. No independent security audit. Presence is polling-based, not a
  connectivity guarantee. Do not present this as production-ready.

The prior handoff below is preserved as historical context; its early “not
pushed” statements predate PR #2, which another agent published successfully.

---

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
- Not verified / manual acceptance pending: two devices on different networks via VPN both directions; receiver restart with port change; address persistence after app restart; unreachable endpoint; different-certificate device rejected; physical Android/macOS UI validation. Autotest is loopback only, not end-to-end VPN. Source update, no signed/notarized release.
