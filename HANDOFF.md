# Current checkpoint — rooms deployed and verified (2026-11-06)

Base `ceb5c1c`; branch `codex/internet-relay`. No merge, release, tag, version
bump, paid-plan change or force push. `main` was not modified.

## Implemented

- EC certificate proof-of-possession self-enrollment with one-minute, one-use
  challenges. Revoked identities cannot re-enroll; explicit admin restore remains.
- Encrypted 16-character (80-bit) room codes: host creates, guest joins, host
  approves the expected fingerprint. Only the code digest and AES-256-GCM blob
  reach the server. Approval establishes mutual grants; the shared transfer token
  stays inside the encrypted blob. Inner pinned TLS remains unchanged.
- Go, Android and macOS room clients/UI; Android KeyStore and macOS Keychain
  storage; room cancellation; saved pairing after room success.
- Opt-in usage (`active`/`file`/`text`) and feedback endpoints; test builds send
  no metrics. Owner-only `scripts/internet-report.mjs` writes
  `build/private-report/index.html` (0600/0700). Admin token never in clients/HTML.
- Download redirect counters (`/download/android`, `/download/macos`, test
  variants) return 503 until `DOWNLOAD_*_URL` is configured; no release created.
- Storage format 2 migration, bounded records, `legacy-v1-backup` retained.
- Test/public build separation is explicit in `scripts/build-rooms.sh`; the
  Android property and macOS Info.plist field are set per variant.

## Deployed and verified

- Worker: https://nodi-relay.igor-shura2011faik.workers.dev
- Active version `9f3d2050-d8f4-4dc8-b20b-a1a984c379de`, created
  2026-11-06T16:07:14Z (confirmed with `wrangler deployments list`).
- Full Kotlin/Go interop: **11/11 PASS**, including both public tests
  `publicRoomEnrollmentAndBidirectionalTransfer` and
  `publicRelayKotlinGoFilesAndTextBothDirections`. This covers self-enrollment,
  both room ownership directions, files/text both ways, byte comparison, and
  temporary device cleanup.
- Server Miniflare: **26/26 PASS**, 40.4 s. `npm run check` dry-run PASS
  (22.69 KiB, gzip 6.42 KiB); no deploy from this checkpoint.
- Go: `go vet ./...` PASS; `go test -race -count=1 ./...` PASS
  (`internal/internet`, `internal/relay`, `internal/transfer`).
- Android `assembleDebug testDebugUnitTest`: BUILD SUCCESSFUL.
- macOS builds with `SDKROOT=.../MacOSX26.sdk`: both variants and ad-hoc
  signature verification PASS. Default SDK 27.0 is incompatible with Swift 6.3.3
  in this environment; use the documented SDKROOT.
- Build artifacts verified:
  - Android test `BuildConfig.NODI_TEST_INSTALL=true`; public `false`.
    Both 17,057,492 bytes, debug cert SHA-256
    `c14152d85e91dc9f7c7ea177f12f5c0c62f95afc7a313830ed3027638ce198b7`.
  - macOS test `NodiTestInstall=true`; public `false`; both codesign strict PASS.
- New edge tests: room code cannot be reused after approval/cancellation; third
  device gets 404; room joins share the 120/device/minute quota; deleting a room
  removes its persisted encrypted blob; Android clears `roomThread` in
  `onDestroy`.
- Owner devices are marked test on the server; private report exists locally.
  Do not print or commit activation files or tokens.

## Remaining

- Physical Mac (Wi-Fi) ↔ S21 FE (mobile data) manual run: room creation in both
  directions, file/text both ways, restart persistence, cancellation, wrong and
  expired codes, and test/public statistics behavior. The checklist and result
  table are in `docs/INTERNET-ALPHA.md`; all ten scenarios are still NOT RUN.
- No GitHub release exists, so download counters stay 503. Configuration commands
  are documented in `server/cloudflare/README.md`; do not publish without approval.
- No new merge/tag/release/version bump. PR #2/base history is unchanged.
- Historical checkpoints below are preserved as history; statements about missing
  rooms/client integration are superseded by this checkpoint.

## Commits in this checkpoint

- `0f4edf5` server rooms, self-enrollment, insights and tests
- `012244d` Go room enrollment and relay lifecycle
- `e3b99ca` Android rooms, secure enrollment and feedback UI
- `584cecc` macOS room UI, Keychain enrollment and feedback
- `16f39c3` public room interoperability coverage
- `7466999` reproducible rooms build script
- `dd22f29` owner-only private report script
- `3dd1e65` rooms/build/manual-test documentation

---

# Latest checkpoint — Android/macOS Internet integration

Updated: 2026-11-04. Branch `codex/internet-relay`; base `ceb5c1c`.
User requested completing integration autonomously. No merge, release, tag,
version bump, paid-plan change or force push.

## Implemented

- Go and production Kotlin WebSocket clients connect through the deployed
  Cloudflare relay. Direct TCP is tried first (4-second timeout); only TCP
  connection failure triggers relay. TLS/authentication failures do not retry.
- Existing pinned TLS and pairing token checks run inside WSS. Relay does not
  receive plaintext files/text. Per-device credentials are separate from admin.
- Receiver polling every 10 seconds, local paired-device allowlist, mutual grants,
  session cancellation, bounded buffers and consumption ACKs. Internet alpha
  limits files to 16 MiB; LAN file limit remains unchanged.
- Internet activation UI on both clients; Android supports scanning activation QR.
  Android encrypts credentials using AndroidKeyStore, excludes them from backup;
  Mac uses Keychain. Codes are bound to the existing device certificate pin.
- Mobile-data-only Android receiver can start without a Wi-Fi IPv4 address.
- Fixed Go duplex deadlock: inbound delivery no longer blocks reading the ACK
  that releases outgoing TLS writes. Regression test exercises incoming data
  arriving before ACK while the application writes before reading.

## Validation

- `go vet ./...` and `go test -race ./...`: PASS.
- `go test -race -count=5 ./internal/internet`: PASS (both stream tests).
- Android `assembleDebug testDebugUnitTest`: BUILD SUCCESSFUL (12s).
- Mac build with MacOSX26.5 SDK: PASS, plist lint and ad-hoc signature verification
  PASS. Built with NODI_NO_OPEN=1, so existing user app was not interrupted.
- Public Kotlin/Go test after duplex fix: PASS (53s). Uses production polling
  clients, forced LAN failure, 180,000-byte files and Unicode text both ways,
  byte-for-byte comparison and temporary device cleanup.
- Full interop suite including a second public run: 10/10 PASS, BUILD SUCCESSFUL
  (54s). Two consecutive public runs passed after the duplex fix.
- Earlier public runs exposed the deadlock; one also failed before transport
  setup with NoRouteToHost to the public API. Do not count these as passing runs.
- Physical Android/macOS UI, KeyStore/Keychain activation and mobile-data↔Wi-Fi
  behavior are NOT yet verified. These require installing the new builds.

## User artifacts and activation (local only)

- `build/nodi-android-internet.apk`: 17,057,492 bytes, 2026-11-04 20:04:23 EET.
  SHA-256: `91f5d0a83ef9589ce8b8d3768c66b79eb41d2fd9075bdfc348d6cedd7015cc98`.
- `build/Nodi.app` and `build/nodi-macos-internet.zip`: zip 6,262,894 bytes,
  SHA-256: `2279fba5a444d035663087de0ca3cf88ad4ceeec109b31a19c66d72014f9f0c4`.
- APK keeps 1.0.0-alpha.5 / code 5 and existing debug signing key. Install over the
  existing app (do not uninstall/clear data, which would change device identity).
- Real Mac and paired S21 FE registrations already created on Cloudflare.
  DO NOT register/rotate them again. Secrets are only in ignored
  `build/internet-activation/{mac.txt,android.txt,android-qr.png}`, files 0600,
  directory 0700. Never print/publish these codes or include them in Git/builds.
- Activation still needs to be imported through each app's Internet UI: paste
  mac.txt on Mac; scan android-qr.png on phone. Keep receiving enabled on both.
- Instructions and manual test checklist: `docs/INTERNET-ALPHA.md`.
- Root .gitignore rule `nodi` intentionally unchanged; new Kotlin files must be
  explicitly force-added. No admin key belongs in any client.

Historical checkpoints below describe their own point in time; statements that
client integration/provisioning is absent are superseded by this checkpoint.

---

# Latest checkpoint — Cloudflare deployed and remotely verified

Updated: 2026-11-03. Branch `codex/internet-relay`. Deployed source commit
`c75730f` (the later documentation commit does not change Worker code).
User authorized Cloudflare deployment and explicitly confirmed creating,
locally storing, and uploading the ADMIN_TOKEN after an approval review block.

- Live endpoint: https://nodi-relay.igor-shura2011faik.workers.dev
- Worker: nodi-relay; SQLite Durable Object binding HUB / RelayHub.
- Active version: `f4428c60-d4c7-40e7-86a7-3fd67e232a93`, deployed
  2026-11-03T19:56:20.380Z following secret configuration. Initial source upload
  was version `b712c6f6-d520-4e2f-98e6-e900ffce0389`.
- Wrangler OAuth login completed with the user's browser consent. The account
  initially had no workers.dev subdomain; opening its Workers dashboard created
  igor-shura2011faik.workers.dev. Dashboard showed 100,000 requests/day allowance.
  No paid plan was enabled or payment submitted. OAuth subscriptions endpoint
  returned 403; account-settings returned default_usage_model=standard, which
  alone is not a billing-plan proof.
- ADMIN_TOKEN is set as a Worker secret. Local recovery copy is
  server/cloudflare/.dev.vars.relay-admin, mode 0600 and git-ignored (verified).
  Never print, commit, upload elsewhere, or include this key in app builds.
  No LOCAL_TEST variable was deployed. Secret values were not printed.
- Public HTTPS initially failed TLS handshakes on the new hostname; it later
  succeeded without disabling certificate verification or changing TLS policy.
- Remote smoke test PASS: /health=200, unauthenticated /v1/incoming=401,
  registration of two synthetic test devices, mutual grants, role-bound tickets,
  public WSS rendezvous, inner pinned TLS 1.3, exact 150,000-byte random payload,
  and reverse reply. Both temporary device registrations deleted (2/2).
- Smoke driver is /private/tmp/nodi-relay-smoke.mjs; it uses existing dev
  dependencies and public test-only TLS fixtures. This was a transport test,
  NOT real Nodi Android/Go application interoperability or physical-device test.
- No Android/macOS integration or enrollment UI yet. Next milestone is secure
  per-device enrollment and client WebSocket transport with automatic routing.
  Keep existing device pins/tokens; never embed ADMIN_TOKEN in clients.
- No GitHub push/PR/merge/tag/release in this deployment step. PR #2 merge status
  has not been rechecked. Local commits remain ahead of origin.

Previous no-deployment/no-secret statements below are historical and superseded
by this checkpoint. Server performance and public abuse controls still need
further validation before broad rollout.

---

# Latest checkpoint — relay availability and boundary fixes

Updated: 2026-11-03. Branch `codex/internet-relay`; base for this change
`1c083a4`. User asked to continue; current milestone remains server-side.

- Moved bounded JSON body reading outside blockConcurrencyWhile. Admission
  authenticates and charges quota first; final mutation reauthenticates against
  current persisted state. This preserves revocation during an in-flight upload.
- Added persisted admin quota: 120 authenticated requests per 60-second window.
  Anonymous abuse still requires edge controls; this is not a global DDoS limit.
- Check available byte budget before increasing counters or forwarding. The
  previous code already rejected an excessive frame BEFORE forwarding; the
  earlier note about exceeding the cap referred to accounting, not leaked data.
- Tests verify another device remains responsive during a stalled upload,
  revocation during upload, exactly 64 MiB accepted with the next byte rejected,
  exactly 4096 frames accepted with the next rejected, and admin request 121.
- Actual local results: `npm test` 19/19 PASS, 0 fail, 38.9 s; `npm run check`
  PASS (Wrangler dry-run, 11.96 KiB / gzip 3.70 KiB); JS syntax checks and
  `git diff --check` PASS. Node remains in /private/tmp/node-v22.23.4-darwin-arm64.
- No dependency, Go, Android, macOS, root .gitignore or version changes.
- No deployment/login/secret creation, PR, merge, tag or release. These tests
  are local; the earlier remote CI results below apply to earlier commits.
- Still outstanding: app integration/secure enrollment, deployed WSS validation,
  physical cross-network testing, deployment interruption/load tests, and the
  digest-comparison/LOCAL_TEST review questions. Device/global session limits
  are not yet tested at every exact boundary.

The prior checkpoint below is historical. Its slow-body/global-lock, missing
admin quota and byte-accounting notes are superseded by the fixes above.

---

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
- `git diff --check`: pass.
- GitHub Actions on pushed head `2ad779c` (push at 2026-11-03T15:30:08Z):
  Cloudflare relay checks success (run 41664531408; `npm ci`, `npm test` 15/15,
  `npm run check` dry-run), Go checks success (run 41664530921; macOS/Ubuntu,
  Go stable/1.22.x, vet, race tests, CLI integration), and Kotlin-Go interop
  success (run 41664530955). CI results are now verified, not merely local YAML
  inspection.
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
- Added `/.wrangler/` to the root `.gitignore`. A root `.wrangler/` directory
  appeared once and was removed manually; running `npm test` and `npm run check`
  from `server/cloudflare` did not recreate it in controlled checks, so its
  origin remains unknown. The nested `server/cloudflare/.wrangler/` was already
  ignored by that package's `.gitignore`.
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
