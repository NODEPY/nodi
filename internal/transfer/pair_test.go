package transfer

import (
	"bufio"
	"crypto/tls"
	"encoding/json"
	"errors"
	"net"
	"strings"
	"testing"
	"time"
)

// Preserve real socket deadlines, shortening only the protocol's long wait in tests.
type shortPairConn struct{ net.Conn }

func (c shortPairConn) SetDeadline(deadline time.Time) error {
	limit := time.Now().Add(100 * time.Millisecond)
	if deadline.After(limit) {
		deadline = limit
	}
	return c.Conn.SetDeadline(deadline)
}

func TestPairTLSRoundTrip(t *testing.T) {
	for _, kind := range []string{"approved", "declined", "wrong-pin"} {
		t.Run(kind, func(t *testing.T) {
			cert, pin, token, err := NewIdentity()
			if err != nil {
				t.Fatal(err)
			}
			ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13})
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			serverCompare := make(chan string, 1)
			done := make(chan error, 1)
			go func() {
				c, e := ln.Accept()
				if e != nil {
					done <- e
					return
				}
				_, e = ReceiveWithPairing(c, t.TempDir(), token, 1024, pin, func(p PairRequest) bool { serverCompare <- p.Compare; return kind != "declined" })
				done <- e
			}()
			expected := pin
			if kind == "wrong-pin" {
				expected = strings.Repeat("0", 64)
			}
			clientCompare := ""
			invite, err := Pair(ln.Addr().String(), expected, "Phone", func(code string) { clientCompare = code })
			serverErr := wait(t, done)
			if kind == "approved" {
				if err != nil || serverErr != nil {
					t.Fatalf("pair: %v / %v", err, serverErr)
				}
				if invite.Token != token || invite.Fingerprint != pin {
					t.Fatal("wrong invite")
				}
			} else if err == nil || serverErr == nil {
				t.Fatal("invalid pairing accepted")
			}
			if kind == "wrong-pin" {
				if clientCompare != "" || len(serverCompare) != 0 {
					t.Fatal("comparison reached before TLS pin validation")
				}
				return
			}
			select {
			case code := <-serverCompare:
				if code != clientCompare || len(code) != 6 {
					t.Fatal("comparison mismatch")
				}
			default:
				t.Fatal("missing approval")
			}
		})
	}
}
func TestPairCommitRevealAndTimeout(t *testing.T) {
	for _, kind := range []string{"valid", "wrong-nonce", "malformed-nonce", "declined", "timeout"} {
		t.Run(kind, func(t *testing.T) {
			server, client := net.Pipe()
			defer server.Close()
			defer client.Close()
			client.SetDeadline(time.Now().Add(3 * time.Second))
			n := strings.Repeat("1", 64)
			pin := strings.Repeat("2", 64)
			token := strings.Repeat("3", 64)
			approval := make(chan PairRequest, 1)
			done := make(chan error, 1)
			go func() {
				defer server.Close()
				var conn net.Conn = server
				if kind == "timeout" {
					conn = shortPairConn{server}
				}
				done <- receivePair(conn, bufio.NewReader(server), header{Commit: digest(n), Name: "Phone"}, token, pin, func(p PairRequest) bool { approval <- p; return kind != "declined" })
			}()
			reader := bufio.NewReader(client)
			var challenge struct {
				Pending bool
				Nonce   string
			}
			if err := readJSON(reader, &challenge); err != nil {
				t.Fatal(err)
			}
			if !challenge.Pending || !hex32(challenge.Nonce) {
				t.Fatal("invalid challenge")
			}
			if kind == "timeout" {
				err := wait(t, done)
				var ne net.Error
				if !errors.As(err, &ne) || !ne.Timeout() {
					t.Fatalf("expected deadline timeout: %v", err)
				}
				if len(approval) != 0 {
					t.Fatal("approved without reveal")
				}
				return
			}
			reveal := n
			if kind == "wrong-nonce" {
				reveal = strings.Repeat("4", 64)
			}
			if kind == "malformed-nonce" {
				reveal = "bad"
			}
			if err := json.NewEncoder(client).Encode(map[string]string{"nonce": reveal}); err != nil {
				t.Fatal(err)
			}
			var response struct {
				OK    bool
				Token string
			}
			if err := readJSON(reader, &response); err != nil {
				t.Fatal(err)
			}
			err := wait(t, done)
			if kind == "valid" {
				if err != nil || !response.OK || response.Token != token {
					t.Fatalf("valid reveal rejected: %v", err)
				}
				p := <-approval
				if p.Compare != PairCompare(pin, n, challenge.Nonce) {
					t.Fatal("different comparison codes")
				}
			} else {
				if err == nil || response.OK || response.Token != "" {
					t.Fatal("secret released after rejection")
				}
			}
			if strings.Contains(kind, "nonce") && len(approval) != 0 {
				t.Fatal("approval called for invalid commitment")
			}
		})
	}
}
func TestPairCompareKnownVector(t *testing.T) {
	// Independently calculated SHA-256 / big-endian uint32 modulo 1,000,000.
	pin := strings.Repeat("a", 64)
	client := strings.Repeat("b", 64)
	server := strings.Repeat("c", 64)
	if got := PairCompare(pin, client, server); got != "662084" {
		t.Fatalf("comparison vector: %s", got)
	}
	if PairCompare(strings.ToUpper(pin), client, server) != PairCompare(pin, client, server) {
		t.Fatal("pin normalization differs")
	}
}
