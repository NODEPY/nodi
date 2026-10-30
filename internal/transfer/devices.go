package transfer

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode"
)

type Peer struct {
	Name        string `json:"name"`
	Address     string `json:"address"`
	Fingerprint string `json:"fingerprint"`
	Token       string `json:"token"`
}

func (p Peer) Invite() Invite {
	return Invite{Address: p.Address, Token: p.Token, Fingerprint: p.Fingerprint}
}

type DeviceBook struct {
	wifiSelected string
	wifiUntil    time.Time
	wifiBusy     bool
	wifiOwnPin   string
	WiFiApprove  PairHandler
	mu           sync.Mutex
	dir          string
	peers        []Peer
	bootstrap    string
	expires      time.Time
	active       map[net.Conn]string
	lock         *os.File
	Changed      func()
}

func atomicPrivate(path string, data []byte) error {
	f, e := os.CreateTemp(filepath.Dir(path), ".nodi-state-*")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	defer f.Close()
	if e = f.Chmod(0600); e != nil {
		return e
	}
	if _, e = f.Write(data); e != nil {
		return e
	}
	if e = f.Sync(); e != nil {
		return e
	}
	if e = f.Close(); e != nil {
		return e
	}
	return os.Rename(f.Name(), path)
}
func OpenDeviceBook(dir string) (*DeviceBook, tls.Certificate, string, error) {
	var empty tls.Certificate
	if e := os.MkdirAll(dir, 0700); e != nil {
		return nil, empty, "", e
	}
	if e := os.Chmod(dir, 0700); e != nil {
		return nil, empty, "", e
	}
	lock, e := os.OpenFile(filepath.Join(dir, "devices.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, empty, "", e
	}
	if e = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		lock.Close()
		return nil, empty, "", errors.New("Nodi device storage is already in use")
	}
	book := &DeviceBook{dir: dir, active: map[net.Conn]string{}, lock: lock}
	fail := func(e error) (*DeviceBook, tls.Certificate, string, error) { book.Close(); return nil, empty, "", e }
	path := filepath.Join(dir, "identity.json")
	var identity struct {
		Certificate []byte
		Key         []byte
	}
	b, e := os.ReadFile(path)
	if os.IsNotExist(e) {
		cert, _, _, err := newIdentity(10 * 365 * 24 * time.Hour)
		if err != nil {
			return fail(err)
		}
		identity.Certificate = cert.Certificate[0]
		identity.Key, err = x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
		if err != nil {
			return fail(err)
		}
		b, _ = json.Marshal(identity)
		if err = atomicPrivate(path, b); err != nil {
			return fail(err)
		}
	} else if e != nil {
		return fail(e)
	} else if e = json.Unmarshal(b, &identity); e != nil {
		return fail(errors.New("cannot read saved Nodi identity"))
	}
	key, e := x509.ParsePKCS8PrivateKey(identity.Key)
	if e != nil {
		return fail(errors.New("invalid saved Nodi key"))
	}
	cert, e := x509.ParseCertificate(identity.Certificate)
	if e != nil {
		return fail(errors.New("invalid saved Nodi certificate"))
	}
	ec, ok := key.(*ecdsa.PrivateKey)
	if !ok || !ec.PublicKey.Equal(cert.PublicKey) {
		return fail(errors.New("saved Nodi key mismatch"))
	}
	b, e = os.ReadFile(filepath.Join(dir, "devices.json"))
	if e == nil {
		if json.Unmarshal(b, &book.peers) != nil || len(book.peers) > 32 {
			return fail(errors.New("cannot read saved devices"))
		}
	} else if !os.IsNotExist(e) {
		return fail(e)
	}
	seen := map[string]bool{}
	for _, p := range book.peers {
		if !validPeer(p) || seen[p.Fingerprint] {
			return fail(errors.New("invalid saved device"))
		}
		seen[p.Fingerprint] = true
	}
	pin := sha256.Sum256(identity.Certificate)
	book.wifiOwnPin = hex.EncodeToString(pin[:])
	return book, tls.Certificate{Certificate: [][]byte{identity.Certificate}, PrivateKey: key}, hex.EncodeToString(pin[:]), nil
}
func validPeer(p Peer) bool {
	host, port, e := net.SplitHostPort(p.Address)
	n, err := strconv.Atoi(port)
	return e == nil && err == nil && n > 0 && n < 65536 && net.ParseIP(host) != nil && hex32(p.Fingerprint) && hex32(p.Token) && len(p.Name) <= 320
}
func (b *DeviceBook) Close() {
	if b.lock != nil {
		b.lock.Close()
		b.lock = nil
	}
}
func (b *DeviceBook) Peers() []Peer {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]Peer{}, b.peers...)
}
func (b *DeviceBook) RefreshQR() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.bootstrap = nonce()
	b.expires = time.Now().Add(2 * time.Minute)
	return b.bootstrap
}
func (b *DeviceBook) save(peers []Peer) error {
	data, e := json.Marshal(peers)
	if e != nil {
		return e
	}
	return atomicPrivate(filepath.Join(b.dir, "devices.json"), data)
}
func (b *DeviceBook) notify() {
	if b.Changed != nil {
		b.Changed()
	}
}
func (b *DeviceBook) Enroll(qr string, p Peer) error {
	b.mu.Lock()
	if b.bootstrap == "" || time.Now().After(b.expires) || subtle.ConstantTimeCompare([]byte(qr), []byte(b.bootstrap)) != 1 {
		b.mu.Unlock()
		return errors.New("QR expired; show a new QR on Mac")
	}
	return b.enrollLocked(p, true)
}

// Caller holds mu; every path releases it.
func (b *DeviceBook) enrollLocked(p Peer, consumeQR bool) error {
	if !validPeer(p) {
		b.mu.Unlock()
		return errors.New("invalid device")
	}
	next := make([]Peer, 0, len(b.peers)+1)
	for _, old := range b.peers {
		if old.Fingerprint != p.Fingerprint {
			if old.Token == p.Token {
				b.mu.Unlock()
				return errors.New("device key already in use")
			}
			next = append(next, old)
		}
	}
	if len(next) >= 32 {
		b.mu.Unlock()
		return errors.New("too many saved devices")
	}
	next = append(next, p)
	if e := b.save(next); e != nil {
		b.mu.Unlock()
		return errors.New("cannot save device")
	}
	b.peers = next
	if consumeQR {
		b.bootstrap = ""
		b.expires = time.Time{}
	}
	var closeList []net.Conn
	for c, pin := range b.active {
		if pin == p.Fingerprint {
			closeList = append(closeList, c)
			delete(b.active, c)
		}
	}
	b.mu.Unlock()
	for _, c := range closeList {
		c.Close()
	}
	b.notify()
	return nil
}
func (b *DeviceBook) Authorize(token string, c net.Conn) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, p := range b.peers {
		if subtle.ConstantTimeCompare([]byte(token), []byte(p.Token)) == 1 {
			b.active[c] = p.Fingerprint
			return true
		}
	}
	return false
}
func (b *DeviceBook) Release(c net.Conn) { b.mu.Lock(); delete(b.active, c); b.mu.Unlock() }
func (b *DeviceBook) Forget(pin string) (Peer, error) {
	b.mu.Lock()
	var removed Peer
	next := make([]Peer, 0, len(b.peers))
	for _, p := range b.peers {
		if p.Fingerprint == pin {
			removed = p
		} else {
			next = append(next, p)
		}
	}
	if removed.Fingerprint == "" {
		b.mu.Unlock()
		return removed, errors.New("device not found")
	}
	if e := b.save(next); e != nil {
		b.mu.Unlock()
		return Peer{}, errors.New("cannot remove saved device")
	}
	b.peers = next
	var closeList []net.Conn
	for c, p := range b.active {
		if p == pin {
			closeList = append(closeList, c)
			delete(b.active, c)
		}
	}
	b.mu.Unlock()
	for _, c := range closeList {
		c.Close()
	}
	b.notify()
	return removed, nil
}
func (b *DeviceBook) Unlink(token string) error {
	b.mu.Lock()
	pin := ""
	for _, p := range b.peers {
		if subtle.ConstantTimeCompare([]byte(token), []byte(p.Token)) == 1 {
			pin = p.Fingerprint
			break
		}
	}
	b.mu.Unlock()
	if pin == "" {
		return errors.New("device access revoked")
	}
	_, e := b.Forget(pin)
	return e
}
func cleanDeviceName(name string) string {
	r := []rune(strings.Map(func(c rune) rune {
		if unicode.IsControl(c) {
			return -1
		}
		return c
	}, name))
	if len(r) > 80 {
		r = r[:80]
	}
	return string(r)
}
func (b *DeviceBook) HandleControl(c net.Conn, h header, r *bufio.Reader) (bool, error) {
	switch h.Action {
	case "wifi_link":
		return true, b.receiveWiFi(c, r, h)
	case "link":
		host, _, e := net.SplitHostPort(c.RemoteAddr().String())
		if e != nil {
			return true, reject(c, "invalid peer address")
		}
		p := Peer{Name: cleanDeviceName(h.Name), Address: net.JoinHostPort(host, strconv.Itoa(h.Port)), Fingerprint: h.Fingerprint, Token: h.Secret}
		if e = b.Enroll(h.Token, p); e != nil {
			return true, reject(c, e.Error())
		}
		return true, json.NewEncoder(c).Encode(map[string]any{"ok": true, "name": LocalDeviceName()})
	case "pair":
		return true, reject(c, "scan Mac QR using Nodi on your phone")
	}
	return false, nil
}

// Finalization and revocation share the same lock: after Forget returns, a
// previously authorized transfer cannot commit a new destination file.
func (b *DeviceBook) Commit(token string, finish func() error) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, p := range b.peers {
		if subtle.ConstantTimeCompare([]byte(token), []byte(p.Token)) == 1 {
			return finish()
		}
	}
	return errors.New("device access revoked")
}
