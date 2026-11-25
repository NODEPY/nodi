package transfer

import (
	"net"
	"strings"
	"testing"
	"time"
)

func TestFallbackOnlyOnTCPFailure(t *testing.T) {
	got := make(chan string, 1)
	invite, done := textReceiver(t, t.TempDir(), func(s string) error { got <- s; return nil })
	address := invite.Address
	// Reserve and close a loopback port to get an unavailable endpoint.
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	invite.Address = l.Addr().String()
	l.Close()
	called := 0
	invite.Fallback = func() (net.Conn, error) { called++; return net.DialTimeout("tcp", address, time.Second) }
	if e = SendText(invite, "fallback"); e != nil {
		t.Fatal(e)
	}
	if e = wait(t, done); e != nil {
		t.Fatal(e)
	}
	if called != 1 || <-got != "fallback" {
		t.Fatal("fallback did not preserve payload")
	}
}
func TestCertificateFailureNeverFallsBack(t *testing.T) {
	invite, done := textReceiver(t, t.TempDir(), func(string) error { t.Error("unexpected payload"); return nil })
	invite.Fingerprint = strings.Repeat("0", 64)
	called := false
	invite.Fallback = func() (net.Conn, error) { called = true; return nil, nil }
	if e := SendText(invite, "secret"); e == nil {
		t.Fatal("bad certificate accepted")
	}
	_ = wait(t, done)
	if called {
		t.Fatal("certificate failure triggered fallback")
	}
}

func TestRelayOnlySkipsDirectAddress(t *testing.T) {
	got := make(chan string, 1)
	invite, done := textReceiver(t, t.TempDir(), func(s string) error { got <- s; return nil })
	relayed := invite.Address
	// The direct address would answer; Internet mode must still not dial it.
	direct, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer direct.Close()
	touched := make(chan struct{}, 1)
	go func() {
		if c, err := direct.Accept(); err == nil {
			touched <- struct{}{}
			c.Close()
		}
	}()
	invite.Address = direct.Addr().String()
	invite.RelayOnly = true
	called := 0
	invite.Fallback = func() (net.Conn, error) { called++; return net.DialTimeout("tcp", relayed, time.Second) }
	if e = SendText(invite, "via relay"); e != nil {
		t.Fatal(e)
	}
	if e = wait(t, done); e != nil {
		t.Fatal(e)
	}
	if called != 1 || <-got != "via relay" {
		t.Fatal("relay-only send did not use the fallback")
	}
	select {
	case <-touched:
		t.Fatal("relay-only send dialed the direct address")
	case <-time.After(100 * time.Millisecond):
	}
}

func TestRelayOnlyWithoutRelayFails(t *testing.T) {
	invite := Invite{Address: "127.0.0.1:1", Token: strings.Repeat("a", 64), Fingerprint: strings.Repeat("0", 64), RelayOnly: true}
	if e := SendText(invite, "x"); e != ErrNoRelay {
		t.Fatalf("got %v, want ErrNoRelay", e)
	}
}
