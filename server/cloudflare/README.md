# Nodi relay for Cloudflare — private alpha

Server implementation for cross-network transfers without entering IP addresses.
Android and macOS integration is implemented for the private alpha. This server
does not replace local pairing and does not store files. Private-alpha
deployment: https://nodi-relay.igor-shura2011faik.workers.dev
(2026-11-06; Worker version 9f3d2050-d8f4-4dc8-b20b-a1a984c379de, rooms build).
Public HTTPS/WSS, pinned TLS 1.3 transport and public Go/Kotlin room
interoperability were verified; physical-device testing is still pending.
The original Go loopback prototype is independent and uses a different protocol.

## Run and verify locally

Requires Node.js 22 and npm. From `server/cloudflare`:

```sh
npm ci
npm test
npm run check
```

Tests run the actual local Workers runtime (Miniflare), not an imitation of
Durable Objects. They cover authorization, mutual grants, private presence,
self-enrollment proof/replay, room approval/isolation/cancellation/rate limits,
stored-room deletion, opt-in metrics/feedback and download counting, binary
duplex forwarding, one-use tickets, revocation, quotas, malformed frames,
request-body deadlines and concurrent access during uploads, revocation during
upload, exact byte/frame/admin quotas, expiration, and TLS 1.3 with a pinned test
certificate over WebSockets.
The TLS test is a transport test, not an Android/Go Nodi interoperability test.
`npm run check` packages the Worker with `wrangler deploy --dry-run`; it does
not deploy or require a Cloudflare account.

For manual local development, generate an ignored `.dev.vars` file:

```sh
node --input-type=module -e 'import {randomBytes} from "node:crypto"; import {writeFileSync} from "node:fs"; writeFileSync(".dev.vars", "LOCAL_TEST=true\nADMIN_TOKEN="+randomBytes(32).toString("hex")+"\n", {mode:0o600,flag:"wx"})'
npm run dev
```

The HTTP exemption requires BOTH LOCAL_TEST=true and a `localhost` or
`127.0.0.1` URL; other loopback addresses are not accepted. Never set
LOCAL_TEST on a deployed Worker. All production
requests require HTTPS/WSS; redirects are deliberately not used for credentials.
Native clients pass Authorization headers; browser Origin and query strings
are rejected. Health contains no credentials or device information.

## Enrollment and trust

This is a closed alpha, not a public signup service. The operator provisions
one random relay credential per device via the admin API and delivers it to
that device securely. ADMIN_TOKEN must NEVER be bundled in the apps. Device
IDs are the existing 64-character lowercase certificate fingerprints. The
operator must verify the association when enrolling; an ID alone is not proof
of identity. The relay credential is separate from the existing Nodi pairing
secret and must be stored using the platform secure credential store.

After existing authenticated local pairing, each app explicitly grants the
other device relay access. Both grants must exist. The server authorizes routes;
it does NOT independently validate that the local pairing occurred. Clients
must retain the exact peer certificate pin and transfer token and run existing
Nodi TLS inside the tunnel. Never send plaintext file/text payloads as frames.
Cloudflare terminates outer WSS, while inner device-to-device TLS remains opaque.
The service can observe device IDs, IPs, timing, and traffic size.

## API v1

Except GET /health, all endpoints require `Authorization: Bearer <64 hex token>`.
JSON responses have Cache-Control: no-store. JSON request bodies are limited
to 1024 bytes and are rejected if they are not complete within 5 seconds.
Authentication and request quotas are checked before reading the body. Uploads
do not hold the shared Durable Object lock; credentials and current peer grants
are checked again before any mutation, including after concurrent revocation.
Tokens and tickets must not be placed in URLs or logs.

| Method and path | Credential | Request / result |
| --- | --- | --- |
| GET /health | none | status and protocol version |
| POST /v1/admin/devices | admin | `{ "id": "<fingerprint>" }` → 201 `{id,token}`; token returned once |
| DELETE /v1/admin/devices/:id | admin | revoke device, remove grants, close its sessions |
| PUT /v1/peers/:id | device | grant a locally paired peer permission |
| DELETE /v1/peers/:id | device | withdraw permission and close pair sessions |
| GET /v1/peers | device | mutually authorized peer IDs and recent presence |
| GET /v1/incoming | device | mark receiver online; pending `{id,from,ticket,expiresAt}` entries |
| POST /v1/sessions | device | `{ "peer": "<fingerprint>" }` → 201 `{id,ticket,expiresAt}` for sender |
| GET /v1/sessions/:id | device | WebSocket upgrade plus `X-Nodi-Ticket: <ticket>` |
| DELETE /v1/sessions/:id | participant | cancel, invalidating both tickets and sockets |
| POST /v1/enroll/challenge | none | `{ "id": "<pin>" }` → 201 `{nonce}`; one use, 60 s |
| POST /v1/enroll | none | signed EC proof → 201 `{id,token}` for that certificate pin |
| POST /v1/rooms | device | `{id,blob}` → 201; code digest + encrypted peer payload |
| POST /v1/rooms/join | device | `{id,blob}` → 200 `{host}`; only one guest per room |
| GET /v1/rooms/:id | participant | encrypted host/guest payload and approval state |
| POST /v1/rooms/:id/approve | host | approve the expected guest and establish mutual grants |
| DELETE /v1/rooms/:id | participant | cancel the room and delete its encrypted payload |
| POST /v1/usage | device | opt-in `active`/`file`/`text` event |
| POST /v1/feedback | device | up to 1000 characters, three per device per day |
| GET /v1/admin/stats | admin | aggregated usage, feedback and download counts |
| PUT /v1/admin/test-devices/:id | admin | mark test install and purge its metrics |
| GET /download/android, /download/macos | none | counted redirect; 503 until configured |

Receiver polls /v1/incoming only while receiving is enabled (for example every
15 seconds). Presence expires after 45 seconds and means recent polling, not
a guarantee of connectivity. Each side gets a distinct random ticket bound
to its device and session; consuming one is serialized and persisted. Tickets
cannot reconnect a used socket. A lost upgrade response requires a new session.
Sender may create a session for an offline peer, but it expires in 30 seconds.

On a successful WebSocket upgrade, wait for the text control message
`{"type":"ready"}` on BOTH sides before transmitting. From then on:

- Nonempty binary messages carry consecutive bytes of the inner TLS stream,
  with no application framing added inside those bytes. Maximum 64 KiB/message.
- After consuming each binary message, send an empty binary message as its
  acknowledgement. The server forwards it to the sender.
- At most one unacknowledged nonempty message per direction. This bounds
  outstanding data for slow clients. Do not acknowledge until the local buffer
  has room; otherwise the client defeats its own backpressure.
- Empty frames are transport acknowledgements and MUST NOT be fed to TLS.
- Client text messages, premature data, unsolicited acknowledgements and limit
  violations terminate the session. Both sides are closed when either closes.
- No half-close, resumption or automatic retry. The Nodi final acknowledgement
  must be received before reporting delivery. An ambiguous disconnect must
  not silently resend a file.

## Limits and storage

One SQLite-backed Durable Object holds a bounded private-alpha registry:
64 devices, 16 concurrent sessions, at most 2 sessions involving each device,
32 pending rooms, at most 2 rooms per host, 128 outstanding enrollment challenges,
120 authenticated API requests/device/minute (room joins included), 120
authenticated admin requests per 60-second window, 30-second rendezvous timeout,
10-minute paired lifetime, 64 MiB combined tunnel bytes and 4096 frames/session
(including acknowledgements). The content limit includes TLS overhead, so the
maximum actual file is smaller. These are alpha safety limits, not product limits.

Registration tokens are stored as SHA-256 hashes of random 256-bit values.
The receiver's unconsumed random ticket is stored until session cleanup so it
can be delivered by authenticated polling. Authorization and replay flags are
persisted before external results become visible. Expiration uses DO alarms;
request handlers also prune expired rooms. Hibernating sockets use attachments
for session/participant identity. Closing/deleting a room invalidates its tickets.
Byte limits are checked before forwarding and before increasing the byte counter.
No names, file contents, or transfer tokens are stored. Revoked device records
are removed; local test state stays in ignored .wrangler directories.

Deployment/restart may disconnect active sockets. Clients must handle this as
an interrupted transfer. Authentication state persists. No delivery guarantee
is made for in-flight transfers across deployments.

## Deployment and operations

Use a Cloudflare account with Workers and SQLite Durable Objects available.
The configuration includes the SQLite migration required by the Free plan.
After local tests, authenticate Wrangler, create a random ADMIN_TOKEN through
`npx wrangler secret put ADMIN_TOKEN`, then use `npx wrangler deploy`. An admin
secret missing or in an invalid format makes all private APIs return 503.
Do not enable request-header/body logging. The repository disables Workers
observability by default; diagnostics must redact Authorization and ticket values.

This code is suitable for further private-alpha staging, not unrestricted public
signup. Before exposure, configure edge abuse/rate controls, check account quotas
and current provider terms, and monitor usage. App limits do not prevent anonymous
request floods from consuming platform quotas. Per-frame durable writes and polling
also consume the free allocation; free unlimited file transfer is NOT promised.
Do not enable paid billing automatically. An account/hosting choice remains needed.

## Dependencies and remaining verification

The lockfile pins development tooling. Overrides update undici and sharp because
the selected Miniflare release's transitive versions had npm audit advisories.
They are not bundled into the deployed Worker. Recheck tests when updating them.

Public HTTPS/WSS was checked on 2026-11-03 with two temporary test devices,
a 150,000-byte payload inside pinned TLS 1.3, and a reverse reply. Both test
registrations were removed. The deployed admin key is a Worker secret; the
ignored local recovery file .dev.vars.relay-admin has permissions 0600.

Remaining: real Android/macOS client integration and
secure enrollment UI; physical devices on different networks; interrupt/restart
and load tests on Cloudflare; independent security review. No published app can
use this API yet. Public self-service enrollment and initial remote pairing are
intentionally outside this private-alpha server milestone.

### Rooms and private insights (client integration)

Self-enrollment uses a one-minute, one-use server challenge signed by the device's
existing EC certificate key. The certificate digest must equal its Nodi pin.
Revoked device identities cannot self-enroll again; explicit admin registration
can restore them. Verification uses the supported [Workers Node crypto API](https://developers.cloudflare.com/workers/runtime-apis/nodejs/crypto/).

Room codes carry 80 random bits (16 base32 characters), expire after five minutes,
and are generated on the client. Only the code digest and AES-256-GCM encrypted
peer data reach the server. The host approves the expected guest before the
server establishes mutual grants or discloses the encrypted host payload.
The shared transfer capability stays inside the encrypted payload. The existing
pinned inner TLS still verifies the peer on every transfer. A code is a secret;
share it privately with the intended participant.

`POST /v1/usage` is opt-in and accepts only active/file/text events and platform.
`POST /v1/feedback` accepts at most 1000 characters and three submissions per
registered device per day. `GET /v1/admin/stats` requires the admin bearer token.
The report omits device IDs, content of transfers, filenames and network addresses.
Stored per-device daily metrics are pseudonymous, not anonymous. Thirty-day
retention is maintained by scheduled pruning. Test installations are excluded.
`PUT /v1/admin/test-devices/:pin` permanently marks existing operator devices as
tests and removes their previous usage/feedback records.

The owner runs `node scripts/internet-report.mjs` from the repository (Node 22+).
It reads the ignored local admin credential and writes `build/private-report/index.html`
with mode 0600. Neither the token nor a public dashboard is created.

Download request counting is available at `/download/android` and `/download/macos`
only after `DOWNLOAD_ANDROID_URL` / `DOWNLOAD_MACOS_URL` point to published assets
under `https://github.com/NODEPY/nodi/releases/download/`. Until configured, the
routes return 503 and do not count anything. `/download/test/android` and
`/download/test/macos` bypass counting for owner test downloads. These are counts
of redirects, not completed downloads or unique people. Direct GitHub downloads
cannot be attributed to, or filtered by, the owner's physical devices.

After publishing the APK and ZIP in a GitHub release, configure both values from
`server/cloudflare` (replace `<tag>` with the published release tag):

```sh
printf '%s' 'https://github.com/NODEPY/nodi/releases/download/<tag>/nodi-android-rooms.apk' \
  | npx wrangler secret put DOWNLOAD_ANDROID_URL
printf '%s' 'https://github.com/NODEPY/nodi/releases/download/<tag>/nodi-macos-rooms.zip' \
  | npx wrangler secret put DOWNLOAD_MACOS_URL
```

Wrangler uploads each value as a Worker secret and creates a new version; no
`wrangler deploy` is needed only for these two values. Run `npm run check` first
and `npx wrangler deploy` only if code or configuration also changed. Verify with
`curl -sI https://nodi-relay.igor-shura2011faik.workers.dev/download/android`
(expect `302` and a `Location` under the release URL) and
`.../download/test/android` (expect `302` without incrementing the counter).

Capacity remains private-alpha: 64 registered identities, 32 pending rooms,
16 relay sessions total. Public enrollment is limited globally and per source
IP per minute. This is not a claim of readiness for unrestricted public scale.
