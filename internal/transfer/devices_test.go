package transfer

import (
	"bytes"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func testBook(t *testing.T) *DeviceBook {
	t.Helper()
	b, _, _, err := OpenDeviceBook(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(b.Close)
	return b
}
func testPeer(n int) Peer {
	return Peer{Name: fmt.Sprint("peer", n), Address: "127.0.0.1:7331", Fingerprint: fmt.Sprintf("%064x", n+1), Token: fmt.Sprintf("%064x", n+1000)}
}
func addPeer(t *testing.T, b *DeviceBook, p Peer) {
	t.Helper()
	if err := b.Enroll(b.RefreshQR(), p); err != nil {
		t.Fatal(err)
	}
}
func TestOpenDeviceBookIdentityAndLock(t *testing.T) {
	dir := t.TempDir()
	b, cert, pin, err := OpenDeviceBook(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if other, _, _, err := OpenDeviceBook(dir); err == nil {
		other.Close()
		t.Fatal("second owner acquired lock")
	}
	p := testPeer(0)
	addPeer(t, b, p)
	b.Close()
	next, again, nextPin, err := OpenDeviceBook(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	key, _ := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	key2, _ := x509.MarshalPKCS8PrivateKey(again.PrivateKey)
	if pin != nextPin || !bytes.Equal(cert.Certificate[0], again.Certificate[0]) || !bytes.Equal(key, key2) {
		t.Fatal("identity changed")
	}
	if got := next.Peers(); len(got) != 1 || got[0] != p {
		t.Fatal("peers not persisted")
	}
	for _, name := range []string{"identity.json", "devices.json", "devices.lock"} {
		info, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != 0600 {
			t.Fatalf("%s permissions %o", name, info.Mode().Perm())
		}
	}
}
func TestOpenDeviceBookCorruptFiles(t *testing.T) {
	for _, kind := range []string{"json", "key", "certificate", "mismatch", "devices-json", "invalid-peer", "duplicate-pin", "over-limit"} {
		t.Run(kind, func(t *testing.T) {
			dir := t.TempDir()
			b, _, _, err := OpenDeviceBook(dir)
			if err != nil {
				t.Fatal(err)
			}
			b.Close()
			identityPath := filepath.Join(dir, "identity.json")
			original, err := os.ReadFile(identityPath)
			if err != nil {
				t.Fatal(err)
			}
			path := identityPath
			var bad []byte
			var id struct{ Certificate, Key []byte }
			if err = json.Unmarshal(original, &id); err != nil {
				t.Fatal(err)
			}
			switch kind {
			case "json":
				bad = []byte("{")
			case "key":
				id.Key = []byte("bad")
				bad, _ = json.Marshal(id)
			case "certificate":
				id.Certificate = []byte("bad")
				bad, _ = json.Marshal(id)
			case "mismatch":
				cert, _, _, e := NewIdentity()
				if e != nil {
					t.Fatal(e)
				}
				id.Certificate = cert.Certificate[0]
				bad, _ = json.Marshal(id)
			default:
				path = filepath.Join(dir, "devices.json")
				switch kind {
				case "devices-json":
					bad = []byte("{")
				case "invalid-peer":
					bad = []byte("[{}]")
				case "duplicate-pin":
					bad, _ = json.Marshal([]Peer{testPeer(0), testPeer(0)})
				case "over-limit":
					peers := []Peer{}
					for i := 0; i < 33; i++ {
						peers = append(peers, testPeer(i))
					}
					bad, _ = json.Marshal(peers)
				}
			}
			if err = os.WriteFile(path, bad, 0600); err != nil {
				t.Fatal(err)
			}
			if opened, _, _, err := OpenDeviceBook(dir); err == nil {
				opened.Close()
				t.Fatal("accepted corrupt state")
			}
			got, _ := os.ReadFile(path)
			if !bytes.Equal(got, bad) {
				t.Fatal("silently replaced corrupt state")
			}
			if path == identityPath {
				err = os.WriteFile(path, original, 0600)
			} else {
				err = os.Remove(path)
			}
			if err != nil {
				t.Fatal(err)
			}
			opened, _, _, err := OpenDeviceBook(dir)
			if err != nil {
				t.Fatalf("failure leaked lock: %v", err)
			}
			opened.Close()
		})
	}
}
func TestEnrollQR(t *testing.T) {
	b := testBook(t)
	p := testPeer(0)
	if b.Enroll("", p) == nil {
		t.Fatal("accepted absent QR")
	}
	qr := b.RefreshQR()
	if b.Enroll("wrong", p) == nil {
		t.Fatal("accepted wrong QR")
	}
	b.mu.Lock()
	b.expires = time.Now().Add(-time.Second)
	b.mu.Unlock()
	if b.Enroll(qr, p) == nil {
		t.Fatal("accepted expired QR")
	}
	qr = b.RefreshQR()
	invalid := p
	invalid.Address = "host:7331"
	if b.Enroll(qr, invalid) == nil {
		t.Fatal("accepted invalid peer")
	}
	if err := b.Enroll(qr, p); err != nil {
		t.Fatalf("failed enrollment consumed QR: %v", err)
	}
	if b.Enroll(qr, testPeer(1)) == nil {
		t.Fatal("reused QR")
	}
	if len(b.Peers()) != 1 {
		t.Fatal("failed requests mutated peers")
	}
}
func TestEnrollLockedTokenAndLimit(t *testing.T) {
	b := testBook(t)
	enroll := func(p Peer) error { b.mu.Lock(); return b.enrollLocked(p, false) }
	if err := enroll(testPeer(0)); err != nil {
		t.Fatal(err)
	}
	dup := testPeer(1)
	dup.Token = testPeer(0).Token
	if enroll(dup) == nil {
		t.Fatal("duplicate token accepted")
	}
	for i := 1; i < 32; i++ {
		if err := enroll(testPeer(i)); err != nil {
			t.Fatal(err)
		}
	}
	if enroll(testPeer(32)) == nil {
		t.Fatal("33rd peer accepted")
	}
	replacement := testPeer(0)
	replacement.Token = fmt.Sprintf("%064x", 9999)
	if err := enroll(replacement); err != nil {
		t.Fatal("replacement at capacity:", err)
	}
	if len(b.Peers()) != 32 {
		t.Fatal("wrong peer count")
	}
}
func TestAuthorizeForgetAndUnlink(t *testing.T) {
	b := testBook(t)
	p, q := testPeer(0), testPeer(1)
	addPeer(t, b, p)
	addPeer(t, b, q)
	a, remote := net.Pipe()
	defer a.Close()
	defer remote.Close()
	other, otherRemote := net.Pipe()
	defer other.Close()
	defer otherRemote.Close()
	if b.Authorize("bad", a) {
		t.Fatal("bad token authorized")
	}
	if !b.Authorize(p.Token, a) || !b.Authorize(q.Token, other) {
		t.Fatal("valid token rejected")
	}
	removed, err := b.Forget(p.Fingerprint)
	if err != nil || removed != p {
		t.Fatalf("forget: %v", err)
	}
	remote.SetReadDeadline(time.Now().Add(time.Second))
	_, err = remote.Read(make([]byte, 1))
	if err == nil {
		t.Fatal("connection not closed")
	}
	if e, ok := err.(net.Error); ok && e.Timeout() {
		t.Fatal("connection still open")
	}
	if b.Authorize(p.Token, a) || b.Commit(p.Token, func() error { t.Error("revoked commit ran"); return nil }) == nil {
		t.Fatal("revoked peer authorized")
	}
	b.mu.Lock()
	_, kept := b.active[other]
	_, gone := b.active[a]
	b.mu.Unlock()
	if !kept || gone {
		t.Fatal("wrong active connection removed")
	}
	b.Release(other)
	b.mu.Lock()
	count := len(b.active)
	b.mu.Unlock()
	if count != 0 {
		t.Fatal("release leaked connection")
	}
	if b.Unlink("bad") == nil {
		t.Fatal("invalid unlink accepted")
	}
	if err = b.Unlink(q.Token); err != nil {
		t.Fatal(err)
	}
	if b.Unlink(q.Token) == nil {
		t.Fatal("replayed unlink accepted")
	}
	if _, err = b.Forget(p.Fingerprint); err == nil {
		t.Fatal("forgot absent peer")
	}
	reopenedData, err := os.ReadFile(filepath.Join(b.dir, "devices.json"))
	if err != nil {
		t.Fatal(err)
	}
	var saved []Peer
	if json.Unmarshal(reopenedData, &saved) != nil || len(saved) != 0 {
		t.Fatal("revocation not persisted")
	}
}

// Force an in-flight finalizer, then race revocation against it. A file committed
// before Forget completes is allowed; no callback may run after its return.
func TestForgetDuringCommit(t *testing.T) {
	b := testBook(t)
	p := testPeer(0)
	addPeer(t, b, p)
	c, r := net.Pipe()
	defer c.Close()
	defer r.Close()
	if !b.Authorize(p.Token, c) {
		t.Fatal("authorize")
	}
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	t.Cleanup(func() { once.Do(func() { close(release) }) })
	dest := filepath.Join(t.TempDir(), "before-forget")
	commitDone := make(chan error, 1)
	go func() {
		commitDone <- b.Commit(p.Token, func() error { close(entered); <-release; return os.WriteFile(dest, []byte("complete"), 0600) })
	}()
	<-entered
	started, forgot := make(chan struct{}), make(chan error, 1)
	go func() { close(started); _, err := b.Forget(p.Fingerprint); forgot <- err }()
	<-started
	select {
	case err := <-forgot:
		t.Fatalf("Forget returned during unfinished commit: %v", err)
	case <-time.After(25 * time.Millisecond):
	}
	once.Do(func() { close(release) })
	if err := wait(t, commitDone); err != nil {
		t.Fatal(err)
	}
	if err := wait(t, forgot); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(dest); err != nil {
		t.Fatal(err)
	}
	// Many already-authorized operations resume only after revocation has returned.
	var wg sync.WaitGroup
	for i := 0; i < 64; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			err := b.Commit(p.Token, func() error {
				return os.WriteFile(filepath.Join(filepath.Dir(dest), fmt.Sprint("late-", i)), nil, 0600)
			})
			if err == nil {
				t.Error("late commit accepted")
			}
		}(i)
	}
	wg.Wait()
	files, err := os.ReadDir(filepath.Dir(dest))
	if err != nil || len(files) != 1 {
		t.Fatalf("post-revocation file created: %v %v", files, err)
	}
}
