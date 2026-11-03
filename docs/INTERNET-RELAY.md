# Current server implementation

The Cloudflare private-alpha server now lives in
[server/cloudflare](../server/cloudflare/README.md). It implements HTTPS/WSS,
operator-issued device credentials, mutual peer grants, presence, single-use
participant-bound tickets, revocation, hibernating sockets and bounded framing.
See that README for the API, local tests, deployment instructions and limits.
It has not been deployed or integrated into Android/macOS.

The Go experiment below is preserved as historical context. Its TCP protocol
is different from the Cloudflare WebSocket protocol and is not a public server.

---

# Internet relay — local prototype

Status: 2026-11-03. Server and local Go integration tests only. Android and
macOS interfaces do not use this transport yet. No public server is deployed.

## Intended user experience

Pair devices once using the existing trusted pairing flow. Select the saved
recipient and send. Prefer a reachable local connection; otherwise use an
Internet relay automatically. Show “Local network” or “Internet”, with no IP or
port entry. Receiving must be enabled and the recipient online. Remote initial
pairing, offline delivery and NAT traversal are separate future work.

## Implemented experiment

Run `go run ./cmd/nodi-relay` from the repository root. It listens on
127.0.0.1:8787; `-listen` accepts only numeric loopback addresses. There is no
health HTTP endpoint, storage, account system or public deployment mode.

Two clients call `relay.Join` with a shared cryptographically random 32-byte
hex ticket and opposite roles (0 receiver, 1 sender). Both initiate outgoing
TCP connections. The server reads exactly 33 header bytes, acknowledges each
side with byte 1 when matched, then copies opaque bytes in both directions.
Tickets are never logged. Limits: 64 concurrent connections, 5 seconds for a
header, 30 seconds waiting for a partner, 10 minutes connection lifetime.
A duplicate role is rejected; waiting tickets expire. Connection close ends
both sides. Tickets are not permanently marked consumed: replay prevention
and authenticated ticket issuance are NOT implemented.

Existing Nodi TLS must run INSIDE the tunnel. The integration test uses the
unchanged sender and receiver protocol, exact receiver certificate pin, and
transfer token. The relay does not terminate this inner TLS. Both clients must
still reject untrusted devices. This prototype's outer TCP is plaintext: ticket
confidentiality is not provided, which is why the executable is loopback-only.

## Verification

`go test -race ./...` passed locally on 2026-11-03.
Tests cover real Nodi text and file delivery through the relay (including byte
comparison), wrong receiver certificate rejection, unmatched ticket expiry,
and invalid ticket rejection. This is a loopback test, not a two-network or
physical Android test. The test uses a local TCP adapter for the existing
sender; production apps have not been connected to relay.Join.

## Required before Internet deployment

1. Public server/domain and TLS certificate; TLS-protected relay connection
   (likely HTTPS/WebSocket on 443 for network compatibility).
2. Device authentication and authorization for presence and rendezvous; issue
   short-lived, single-use tickets only to authenticated paired devices. Do not
   derive authorization from a public fingerprint or send pairing secrets to
   the server. Define revocation, replay defense and reconnect behavior.
3. Abuse controls: per-device quotas, bandwidth limits, rate limiting,
   deployment monitoring, graceful shutdown, and operational tests. Current
   caps alone are insufficient for a public service.
4. Android and Go client transport adapters preserving end-to-end TLS and
   peer pins. Automatic route selection on both platforms; fallback only on
   connectivity failure, not certificate/token rejection. Avoid duplicate
   delivery when a transfer acknowledgement is lost.
5. Receiver presence/reconnection, Android foreground service lifecycle,
   cancellation and progress UI. No retry of a partially sent file until
   delivery semantics are defined.
6. Test file/text both ways over mobile data and separate Wi-Fi networks,
   wrong identity, expired tickets, disconnects, cancellation and app restarts.

Relay hosting needs bandwidth and ongoing maintenance. File contents need not
be stored; the operator can still observe IP addresses, timing and byte counts.
No hosting has been purchased or configured.
