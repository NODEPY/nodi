package transfer

import (
	"crypto/tls"
	"errors"
	"strings"
	"testing"
)

func textReceiver(t *testing.T, inbox string, deliver TextHandler) (Invite, <-chan error) {
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
			_, err = ServeText(c, inbox, token, 8*1024*1024, "", nil, nil, nil, deliver)
		}
		done <- err
	}()
	return Invite{Address: l.Addr().String(), Token: token, Fingerprint: pin}, done
}

func TestTextRoundTrip(t *testing.T) {
	inbox := t.TempDir()
	values := []string{
		"https://example.com/nodi",
		"Привіт, Nodi!\nДругий рядок із табуляцією:\tok",
		strings.Repeat("текст ", 1000),
	}
	for _, value := range values {
		got := make(chan string, 1)
		invite, done := textReceiver(t, inbox, func(text string) error { got <- text; return nil })
		parsed, err := ParseInvite(invite.Encode())
		if err != nil {
			t.Fatal(err)
		}
		if err = SendText(parsed, value); err != nil {
			t.Fatal(err)
		}
		if err = wait(t, done); err != nil {
			t.Fatal(err)
		}
		select {
		case text := <-got:
			if text != value {
				t.Fatalf("text mismatch: got %q", text)
			}
		default:
			t.Fatal("receiver never delivered the text")
		}
	}
}

func TestTextRejectedByHandler(t *testing.T) {
	inbox := t.TempDir()
	invite, done := textReceiver(t, inbox, func(string) error { return errors.New("clipboard unavailable") })
	parsed, err := ParseInvite(invite.Encode())
	if err != nil {
		t.Fatal(err)
	}
	if err = SendText(parsed, "hello"); err == nil || !strings.Contains(err.Error(), "clipboard unavailable") {
		t.Fatalf("expected handler rejection, got %v", err)
	}
	if err = wait(t, done); err == nil {
		t.Fatal("receiver reported success after rejecting the text")
	}
}

func TestTextRejectedWithoutHandler(t *testing.T) {
	inbox := t.TempDir()
	cert, pin, token, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	l, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	done := make(chan error, 1)
	go func() {
		c, err := l.Accept()
		if err == nil {
			_, err = Serve(c, inbox, token, 8*1024*1024, "", nil, nil, nil)
		}
		done <- err
	}()
	invite, err := ParseInvite(Invite{Address: l.Addr().String(), Token: token, Fingerprint: pin}.Encode())
	if err != nil {
		t.Fatal(err)
	}
	if err = SendText(invite, "hello"); err == nil || !strings.Contains(err.Error(), "not supported") {
		t.Fatalf("expected unsupported text error, got %v", err)
	}
	if err = wait(t, done); err == nil {
		t.Fatal("receiver accepted text without a handler")
	}
}

func TestTextValidation(t *testing.T) {
	for _, value := range []string{"", strings.Repeat("a", MaxTextBytes+1)} {
		if err := SendText(Invite{}, value); err == nil {
			t.Fatalf("expected length rejection for %d bytes", len(value))
		}
	}
	for _, value := range []string{"nul\x00byte", "bell\x07", "\u007fdelete"} {
		if validText(value) {
			t.Fatalf("control characters must be rejected: %q", value)
		}
	}
	for _, value := range []string{"plain", "лінія\nвкладка\ttab", "emoji 🥒", "  spaced  "} {
		if !validText(value) {
			t.Fatalf("printable text rejected: %q", value)
		}
	}
}
