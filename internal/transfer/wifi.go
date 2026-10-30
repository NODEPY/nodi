package transfer

import (
	"bufio"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"time"
)

// A selected peer expires after two minutes. "*" permits incoming requests
// while the UI is listening; every request still requires UI approval.
func (b *DeviceBook) SelectWiFi(pin string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.wifiSelected = pin
	b.wifiUntil = time.Now().Add(2 * time.Minute)
}

func wifiCommit(n, pin string, port int, secret string) string {
	return digest(fmt.Sprintf("nodi-wifi-v1\n%s\n%s\n%d\n%s", n, pin, port, secret))
}
func wifiCompare(serverPin, clientPin, client, server string, port int, secret string) string {
	return PairCompare(serverPin, fmt.Sprintf("nodi-wifi-v1\n%s\n%s\n%d\n%s", client, clientPin, port, secret), server)
}

func (b *DeviceBook) receiveWiFi(c net.Conn, r *bufio.Reader, h header) error {
	b.mu.Lock()
	allowed := hex32(h.Fingerprint) && (h.Fingerprint == b.wifiSelected || b.wifiSelected == "*") &&
		(b.wifiSelected == "*" || time.Now().Before(b.wifiUntil)) && !b.wifiBusy && b.WiFiApprove != nil
	if allowed {
		b.wifiBusy = true
	}
	b.mu.Unlock()
	if !allowed {
		return reject(c, "select phone on Mac first")
	}
	defer func() { b.mu.Lock(); b.wifiBusy = false; b.mu.Unlock() }()
	if !hex32(h.Commit) || !hex32(h.Secret) || h.Port < 1 || h.Port > 65535 {
		return reject(c, "invalid Wi-Fi pairing")
	}
	_ = c.SetDeadline(time.Now().Add(75 * time.Second))
	server := nonce()
	enc := json.NewEncoder(c)
	if e := enc.Encode(map[string]any{"pending": true, "nonce": server}); e != nil {
		return e
	}
	var reveal struct {
		Nonce string `json:"nonce"`
	}
	if e := readJSON(r, &reveal); e != nil {
		return e
	}
	if !hex32(reveal.Nonce) || wifiCommit(reveal.Nonce, h.Fingerprint, h.Port, h.Secret) != h.Commit {
		return reject(c, "invalid pairing commitment")
	}
	compare := wifiCompare(b.wifiOwnPin, h.Fingerprint, reveal.Nonce, server, h.Port, h.Secret)
	if !b.WiFiApprove(PairRequest{ID: nonce()[:32], Name: cleanDeviceName(h.Name), Compare: compare, Fingerprint: h.Fingerprint}) {
		return reject(c, "pairing declined or timed out")
	}
	var confirmation struct {
		Allow bool `json:"allow"`
	}
	if e := readJSON(r, &confirmation); e != nil {
		return e
	}
	if !confirmation.Allow {
		return reject(c, "pairing declined")
	}
	host, _, e := net.SplitHostPort(c.RemoteAddr().String())
	if e != nil {
		return e
	}
	p := Peer{Name: cleanDeviceName(h.Name), Address: net.JoinHostPort(host, strconv.Itoa(h.Port)), Fingerprint: h.Fingerprint, Token: h.Secret}
	if e = b.enrollWiFi(p); e != nil {
		return reject(c, e.Error())
	}
	return enc.Encode(map[string]any{"ok": true, "name": LocalDeviceName()})
}

func (b *DeviceBook) enrollWiFi(p Peer) error {
	b.mu.Lock()
	if (p.Fingerprint != b.wifiSelected && b.wifiSelected != "*") || (b.wifiSelected != "*" && time.Now().After(b.wifiUntil)) {
		b.mu.Unlock()
		return errors.New("device selection changed or expired")
	}
	if b.wifiSelected != "*" {
		b.wifiSelected = ""
	}
	return b.enrollLocked(p, false)
}

func LocalDeviceName() string {
	name, _ := os.Hostname()
	if name == "" {
		return "Mac"
	}
	return cleanDeviceName(name)
}

// A knock only asks the visible phone UI to initiate a pinned, confirmed handshake.
// It grants no capability and sends no secret.
func RequestWiFi(address, pin, ownPin string, ownPort int) error {
	host, _, e := net.SplitHostPort(address)
	if e != nil || net.ParseIP(host) == nil || !hex32(pin) || !hex32(ownPin) || ownPort < 1 || ownPort > 65535 {
		return errors.New("invalid discovered endpoint")
	}
	expected, _ := hex.DecodeString(pin)
	c, e := tls.DialWithDialer(&net.Dialer{Timeout: 5 * time.Second}, "tcp", address, &tls.Config{
		MinVersion: tls.VersionTLS13, InsecureSkipVerify: true,
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) == 0 {
				return errors.New("missing certificate")
			}
			actual := sha256.Sum256(cs.PeerCertificates[0].Raw)
			if subtle.ConstantTimeCompare(actual[:], expected) != 1 {
				return errors.New("receiver certificate mismatch")
			}
			return nil
		},
	})
	if e != nil {
		return e
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	if e = json.NewEncoder(c).Encode(map[string]any{"action": "wifi_request", "fingerprint": ownPin, "port": ownPort}); e != nil {
		return e
	}
	var result reply
	if e = readJSON(bufio.NewReaderSize(c, 4096), &result); e != nil {
		return e
	}
	if !result.OK {
		return errors.New(result.Error)
	}
	return nil
}
