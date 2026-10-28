package transfer

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func receiver(t *testing.T, inbox string) (Invite, <-chan error) {
	t.Helper()
	cert, pin, token, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	l, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	done := make(chan error, 1)
	go func() {
		c, err := l.Accept()
		if err == nil {
			_, err = Receive(c, inbox, token, 8*1024*1024)
		}
		done <- err
	}()
	return Invite{Address: l.Addr().String(), Token: token, Fingerprint: pin}, done
}

func wait(t *testing.T, done <-chan error) error {
	t.Helper()
	select {
	case err := <-done:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("receiver stalled")
		return nil
	}
}

func TestRoundTrip(t *testing.T) {
	for _, data := range [][]byte{{}, []byte("Привіт, Nodi!\n"), bytes.Repeat([]byte{0, 255, 31, 128}, 256*1024)} {
		t.Run(stringSize(len(data)), func(t *testing.T) {
			inbox := t.TempDir()
			source := filepath.Join(t.TempDir(), "тест.bin")
			if err := os.WriteFile(source, data, 0600); err != nil {
				t.Fatal(err)
			}
			invite, done := receiver(t, inbox)
			parsed, err := ParseInvite(invite.Encode())
			if err != nil {
				t.Fatal(err)
			}
			if err = Send(parsed, source); err != nil {
				t.Fatal(err)
			}
			if err = wait(t, done); err != nil {
				t.Fatal(err)
			}
			got, err := os.ReadFile(filepath.Join(inbox, "тест.bin"))
			if err != nil || !bytes.Equal(got, data) {
				t.Fatalf("payload mismatch: %v", err)
			}
		})
	}
}

func stringSize(n int) string {
	if n == 0 {
		return "empty"
	}
	if n < 100 {
		return "unicode"
	}
	return "binary_1MiB"
}

func TestAuthenticationAndPin(t *testing.T) {
	for _, kind := range []string{"token", "certificate"} {
		t.Run(kind, func(t *testing.T) {
			inbox := t.TempDir()
			source := filepath.Join(t.TempDir(), "secret.txt")
			if err := os.WriteFile(source, []byte("secret"), 0600); err != nil {
				t.Fatal(err)
			}
			invite, done := receiver(t, inbox)
			if kind == "token" {
				invite.Token = strings.Repeat("0", 64)
			} else {
				invite.Fingerprint = strings.Repeat("0", 64)
			}
			if err := Send(invite, source); err == nil {
				t.Fatal("accepted wrong credentials")
			}
			if err := wait(t, done); err == nil {
				t.Fatal("receiver unexpectedly succeeded")
			}
			files, _ := os.ReadDir(inbox)
			if len(files) != 0 {
				t.Fatal("unauthorized file created")
			}
		})
	}
}

func TestExistingFileAndSymlinkPreserved(t *testing.T) {
	for _, symlink := range []bool{false, true} {
		inbox := t.TempDir()
		target := filepath.Join(inbox, "file.txt")
		original := []byte("KEEP")
		if symlink {
			external := filepath.Join(t.TempDir(), "outside.txt")
			if err := os.WriteFile(external, original, 0600); err != nil {
				t.Fatal(err)
			}
			if err := os.Symlink(external, target); err != nil {
				t.Fatal(err)
			}
		} else if err := os.WriteFile(target, original, 0600); err != nil {
			t.Fatal(err)
		}
		source := filepath.Join(t.TempDir(), "file.txt")
		if err := os.WriteFile(source, []byte("REPLACE"), 0600); err != nil {
			t.Fatal(err)
		}
		invite, done := receiver(t, inbox)
		if err := Send(invite, source); err == nil {
			t.Fatal("overwrite accepted")
		}
		if err := wait(t, done); err == nil {
			t.Fatal("receiver accepted overwrite")
		}
		got, err := os.ReadFile(target)
		if err != nil || !bytes.Equal(got, original) {
			t.Fatal("existing data changed")
		}
	}
}

func TestMalformedAndInterruptedTransfer(t *testing.T) {
	for _, kind := range []string{"path", "backslash", "size", "checksum", "interrupted", "oversized_header"} {
		t.Run(kind, func(t *testing.T) {
			inbox := t.TempDir()
			server, client := net.Pipe()
			t.Cleanup(func() { client.Close(); server.Close() })
			done := make(chan error, 1)
			go func() { _, err := Receive(server, inbox, "token", 100); done <- err }()
			_ = client.SetDeadline(time.Now().Add(3 * time.Second))
			sum := sha256.Sum256([]byte("abc"))
			h := header{Token: "token", Name: "file.txt", Size: 3, SHA256: hex.EncodeToString(sum[:])}
			switch kind {
			case "path":
				h.Name = "../escape"
			case "backslash":
				h.Name = "..\\escape"
			case "size":
				h.Size = 101
			case "checksum":
				h.SHA256 = strings.Repeat("0", 64)
			}
			if kind == "oversized_header" {
				_, _ = client.Write([]byte(strings.Repeat("x", 5000) + "\n"))
			} else {
				if err := json.NewEncoder(client).Encode(h); err != nil {
					t.Fatal(err)
				}
				var ack reply
				r := bufio.NewReader(client)
				if err := readJSON(r, &ack); err != nil {
					t.Fatal(err)
				}
				if kind == "checksum" || kind == "interrupted" {
					if !ack.OK {
						t.Fatal("valid header rejected")
					}
					if kind == "interrupted" {
						_, _ = client.Write([]byte("a"))
						client.Close()
					} else {
						_, _ = client.Write([]byte("abc"))
						if err := readJSON(r, &ack); err != nil {
							t.Fatal(err)
						}
						if ack.OK {
							t.Fatal("bad checksum accepted")
						}
					}
				} else if ack.OK {
					t.Fatal("invalid header accepted")
				}
			}
			if err := wait(t, done); err == nil {
				t.Fatal("invalid transfer succeeded")
			}
			files, _ := os.ReadDir(inbox)
			if len(files) != 0 {
				t.Fatal("partial file left behind")
			}
		})
	}
}

func TestInvalidInvites(t *testing.T) {
	for _, code := range []string{"", "garbage", "nodi1-!!!!", "nodi1-" + strings.Repeat("a", 5000)} {
		if _, err := ParseInvite(code); err == nil {
			t.Fatal("invalid invite accepted")
		}
	}
}
