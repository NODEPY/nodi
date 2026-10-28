package transfer

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"time"
	"unicode"
)

type PairRequest struct{ ID, Name, Compare string }
type PairHandler func(PairRequest) bool

func hex32(s string) bool {
	b, e := hex.DecodeString(s)
	return e == nil && len(b) == 32 && s == strings.ToLower(s)
}
func nonce() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func digest(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }

// Commit/reveal binds the client nonce before the server chooses its nonce.
func PairCompare(pin, client, server string) string {
	h := sha256.Sum256([]byte("nodi-pair-v1\n" + strings.ToLower(pin) + "\n" + client + "\n" + server))
	return fmt.Sprintf("%06d", binary.BigEndian.Uint32(h[:4])%1000000)
}
func receivePair(c net.Conn, r *bufio.Reader, h header, token, pin string, approve PairHandler) error {
	if approve == nil || !hex32(h.Commit) {
		return reject(c, "pairing unavailable")
	}
	_ = c.SetDeadline(time.Now().Add(75 * time.Second))
	server := nonce()
	if e := json.NewEncoder(c).Encode(map[string]any{"pending": true, "nonce": server}); e != nil {
		return e
	}
	var reveal struct {
		Nonce string `json:"nonce"`
	}
	if e := readJSON(r, &reveal); e != nil {
		return e
	}
	if !hex32(reveal.Nonce) || subtle.ConstantTimeCompare([]byte(digest(reveal.Nonce)), []byte(h.Commit)) != 1 {
		return reject(c, "invalid pairing commitment")
	}
	name := strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, h.Name)
	if len([]rune(name)) > 80 {
		name = string([]rune(name)[:80])
	}
	if !approve(PairRequest{ID: nonce()[:32], Name: name, Compare: PairCompare(pin, reveal.Nonce, server)}) {
		return reject(c, "pairing declined or timed out")
	}
	return json.NewEncoder(c).Encode(map[string]any{"ok": true, "token": token})
}
func Pair(address, pin, name string, onCompare func(string)) (Invite, error) {
	var empty Invite
	host, port, e := net.SplitHostPort(address)
	if e != nil || net.ParseIP(host) == nil || port == "" || !hex32(pin) {
		return empty, errors.New("invalid discovered endpoint")
	}
	expected, _ := hex.DecodeString(pin)
	c, e := tls.DialWithDialer(&net.Dialer{Timeout: 10 * time.Second}, "tcp", address, &tls.Config{
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
		}})
	if e != nil {
		return empty, e
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(75 * time.Second))
	client := nonce()
	enc := json.NewEncoder(c)
	r := bufio.NewReaderSize(c, 4096)
	if e = enc.Encode(map[string]string{"action": "pair", "name": name, "commit": digest(client)}); e != nil {
		return empty, e
	}
	var challenge struct {
		Pending bool   `json:"pending"`
		Nonce   string `json:"nonce"`
	}
	if e = readJSON(r, &challenge); e != nil {
		return empty, e
	}
	if !challenge.Pending || !hex32(challenge.Nonce) {
		return empty, errors.New("pairing unavailable; use QR")
	}
	if e = enc.Encode(map[string]string{"nonce": client}); e != nil {
		return empty, e
	}
	onCompare(PairCompare(pin, client, challenge.Nonce))
	var response struct {
		OK    bool   `json:"ok"`
		Token string `json:"token"`
	}
	if e = readJSON(r, &response); e != nil {
		return empty, e
	}
	if !response.OK || !hex32(response.Token) {
		return empty, errors.New("pairing declined or timed out")
	}
	return Invite{Address: address, Token: response.Token, Fingerprint: pin}, nil
}
