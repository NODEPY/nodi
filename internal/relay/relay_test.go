package relay_test

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"nodi/internal/relay"
	"nodi/internal/transfer"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func start(t *testing.T, wait time.Duration) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go relay.New(8, wait, 5*time.Second).Serve(l)
	return l.Addr().String()
}

func TestNodiThroughRelay(t *testing.T) {
	for _, kind := range []string{"text", "file", "wrong-pin"} {
		t.Run(kind, func(t *testing.T) {
			addr := start(t, time.Second)
			ticket, err := relay.NewTicket()
			if err != nil {
				t.Fatal(err)
			}
			cert, pin, token, err := transfer.NewIdentity()
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			inbox := t.TempDir()
			received := make(chan string, 1)
			done := make(chan error, 1)
			go func() {
				c, e := relay.Join(ctx, addr, ticket, 0)
				if e == nil {
					defer c.Close()
					secure := tls.Server(c, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13})
					_, e = transfer.ServeText(secure, inbox, token, 8*1024*1024, "", nil, nil, nil, func(text string) error { received <- text; return nil })
				}
				done <- e
			}()
			// The adapter lets the existing Nodi sender establish pinned TLS end-to-end.
			l, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer l.Close()
			bridgeDone := make(chan error, 1)
			go func() {
				c, e := l.Accept()
				if e != nil {
					bridgeDone <- e
					return
				}
				defer c.Close()
				r, e := relay.Join(ctx, addr, ticket, 1)
				if e != nil {
					bridgeDone <- e
					return
				}
				defer r.Close()
				copied := make(chan struct{})
				go func() { io.Copy(r, c); r.Close(); c.Close(); close(copied) }()
				io.Copy(c, r)
				r.Close()
				c.Close()
				<-copied
				bridgeDone <- nil
			}()
			invite := transfer.Invite{Address: l.Addr().String(), Token: token, Fingerprint: pin}
			payload := "Привіт через relay!"
			if kind == "wrong-pin" {
				_, invite.Fingerprint, _, err = transfer.NewIdentity()
				if err != nil {
					t.Fatal(err)
				}
			}
			if kind == "file" {
				source := filepath.Join(t.TempDir(), "sample.txt")
				if err = os.WriteFile(source, []byte(payload), 0600); err != nil {
					t.Fatal(err)
				}
				err = transfer.Send(invite, source)
			} else {
				err = transfer.SendText(invite, payload)
			}
			if kind == "wrong-pin" {
				if err == nil {
					t.Fatal("wrong pin accepted")
				}
			} else if err != nil {
				t.Fatal(err)
			}
			select {
			case e := <-done:
				if kind != "wrong-pin" && e != nil {
					t.Fatal(e)
				}
			case <-ctx.Done():
				t.Fatal("receiver timeout")
			}
			select {
			case e := <-bridgeDone:
				if e != nil {
					t.Fatal(e)
				}
			case <-ctx.Done():
				t.Fatal("bridge timeout")
			}
			if kind == "text" {
				select {
				case got := <-received:
					if got != payload {
						t.Fatal("text mismatch")
					}
				default:
					t.Fatal("no text")
				}
			}
			if kind == "file" {
				b, e := os.ReadFile(filepath.Join(inbox, "sample.txt"))
				if e != nil || string(b) != payload {
					t.Fatalf("file mismatch: %v", e)
				}
			}
		})
	}
}

func TestUnmatchedTicketExpires(t *testing.T) {
	addr := start(t, 30*time.Millisecond)
	ticket, _ := relay.NewTicket()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	c, err := relay.Join(ctx, addr, ticket, 0)
	if c != nil {
		c.Close()
	}
	if err == nil {
		t.Fatal("unmatched ticket accepted")
	}
}

func TestInvalidTicket(t *testing.T) {
	if _, err := relay.Join(context.Background(), "127.0.0.1:1", "bad", 0); err == nil {
		t.Fatal("invalid ticket accepted")
	}
}
