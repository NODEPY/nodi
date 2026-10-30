package transfer

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestTransferPartCleanup(t *testing.T) {
	for _, kind := range []string{"success", "interrupted", "checksum", "revoked", "destination-race"} {
		t.Run(kind, func(t *testing.T) {
			b := testBook(t)
			p := testPeer(0)
			addPeer(t, b, p)
			inbox := t.TempDir()
			// An unrelated old partial must not be deleted by cleanup of this transfer.
			stale := filepath.Join(inbox, ".nodi-existing.part")
			if err := os.WriteFile(stale, []byte("keep"), 0600); err != nil {
				t.Fatal(err)
			}
			server, client := net.Pipe()
			defer server.Close()
			defer client.Close()
			client.SetDeadline(time.Now().Add(3 * time.Second))
			done := make(chan error, 1)
			go func() { _, err := ReceiveTrusted(server, inbox, 1024, b); done <- err }()
			sum := sha256.Sum256([]byte("abc"))
			h := header{Token: p.Token, Name: "result", Size: 3, SHA256: hex.EncodeToString(sum[:])}
			if err := json.NewEncoder(client).Encode(h); err != nil {
				t.Fatal(err)
			}
			r := bufio.NewReader(client)
			var ack reply
			if err := readJSON(r, &ack); err != nil || !ack.OK {
				t.Fatalf("header: %v", err)
			}
			parts, err := filepath.Glob(filepath.Join(inbox, ".nodi-*.part"))
			if err != nil || len(parts) != 2 {
				t.Fatal("no active partial file")
			}
			switch kind {
			case "interrupted":
				if _, err = client.Write([]byte("a")); err != nil {
					t.Fatal(err)
				}
				client.Close()
			case "revoked":
				if _, err = b.Forget(p.Fingerprint); err != nil {
					t.Fatal(err)
				}
			default:
				if kind == "destination-race" {
					if err = os.WriteFile(filepath.Join(inbox, "result"), []byte("original"), 0600); err != nil {
						t.Fatal(err)
					}
				}
				payload := "abc"
				if kind == "checksum" {
					payload = "bad"
				}
				if _, err = client.Write([]byte(payload)); err != nil {
					t.Fatal(err)
				}
				if err = readJSON(r, &ack); err != nil {
					t.Fatal(err)
				}
				if ack.OK != (kind == "success") {
					t.Fatal("unexpected final ack")
				}
			}
			err = wait(t, done)
			if (err == nil) != (kind == "success") {
				t.Fatalf("receive result: %v", err)
			}
			parts, err = filepath.Glob(filepath.Join(inbox, ".nodi-*.part"))
			if err != nil || len(parts) != 1 || parts[0] != stale {
				t.Fatalf("leaked partials: %v / %v", parts, err)
			}
			kept, _ := os.ReadFile(stale)
			if string(kept) != "keep" {
				t.Fatal("unrelated partial modified")
			}
			final, err := os.ReadFile(filepath.Join(inbox, "result"))
			switch kind {
			case "success":
				if err != nil || string(final) != "abc" {
					t.Fatal("bad result")
				}
			case "destination-race":
				if err != nil || string(final) != "original" {
					t.Fatal("overwrote existing file")
				}
			default:
				if !os.IsNotExist(err) {
					t.Fatal("failed transfer created destination")
				}
			}
		})
	}
}

// Characterization for Stage 2: process death bypasses deferred cleanup.
// Keep this distinct from handled failures above; it is not a startup-cleanup guarantee.
func TestAbruptExitLeavesPartForRecovery(t *testing.T) {
	if inbox := os.Getenv("NODI_TEST_CRASH_INBOX"); inbox != "" {
		server, client := net.Pipe()
		go func() { _, _ = Receive(server, inbox, "test-token", 1024) }()
		client.SetDeadline(time.Now().Add(3 * time.Second))
		sum := sha256.Sum256([]byte("abc"))
		if err := json.NewEncoder(client).Encode(header{Token: "test-token", Name: "unfinished", Size: 3, SHA256: hex.EncodeToString(sum[:])}); err != nil {
			t.Fatal(err)
		}
		var ack reply
		if err := readJSON(bufio.NewReader(client), &ack); err != nil || !ack.OK {
			t.Fatalf("header: %v", err)
		}
		os.Exit(0) // Intentionally bypass the receiver's defers after it creates the part.
	}
	inbox := t.TempDir()
	cmd := exec.Command(os.Args[0], "-test.run=^TestAbruptExitLeavesPartForRecovery$")
	cmd.Env = append(os.Environ(), "NODI_TEST_CRASH_INBOX="+inbox)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("crash fixture: %v %s", err, out)
	}
	parts, err := filepath.Glob(filepath.Join(inbox, ".nodi-*.part"))
	if err != nil || len(parts) != 1 {
		t.Fatalf("expected recoverable crash leftover: %v %v", parts, err)
	}
	if _, err = os.Stat(filepath.Join(inbox, "unfinished")); !os.IsNotExist(err) {
		t.Fatal("unfinished final file exists")
	}
}
