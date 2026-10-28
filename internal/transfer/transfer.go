// Package transfer implements Nodi's first direct, encrypted file transfer.
package transfer

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode"
)

const Timeout = 30 * time.Minute

// Invite is a secret, temporary capability: anyone holding it can send files.
// Share it only with the intended sender through a trusted channel.
type Invite struct {
	Address     string `json:"address"`
	Token       string `json:"token"`
	Fingerprint string `json:"fingerprint"`
}

func (i Invite) Encode() string {
	b, _ := json.Marshal(i)
	return "nodi1-" + base64.RawURLEncoding.EncodeToString(b)
}

func ParseInvite(code string) (Invite, error) {
	var i Invite
	if !strings.HasPrefix(code, "nodi1-") || len(code) > 4096 {
		return i, errors.New("invalid Nodi connection code")
	}
	b, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(code, "nodi1-"))
	if err != nil {
		return i, err
	}
	if err = json.Unmarshal(b, &i); err != nil {
		return i, err
	}
	if _, _, err = net.SplitHostPort(i.Address); err != nil {
		return i, err
	}
	for _, value := range []string{i.Token, i.Fingerprint} {
		b, err = hex.DecodeString(value)
		if err != nil || len(b) != 32 {
			return i, errors.New("invalid connection credentials")
		}
	}
	return i, nil
}

func NewIdentity() (tls.Certificate, string, string, error) { return newIdentity(24 * time.Hour) }
func newIdentity(validFor time.Duration) (tls.Certificate, string, string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, "", "", err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return tls.Certificate{}, "", "", err
	}
	template := &x509.Certificate{
		SerialNumber: serial, Subject: pkix.Name{CommonName: "Nodi temporary receiver"},
		NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(validFor),
		KeyUsage:    x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, "", "", err
	}
	token := make([]byte, 32)
	if _, err = rand.Read(token); err != nil {
		return tls.Certificate{}, "", "", err
	}
	fingerprint := sha256.Sum256(der)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, hex.EncodeToString(fingerprint[:]), hex.EncodeToString(token), nil
}

type header struct {
	Port        int    `json:"port,omitempty"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Secret      string `json:"secret,omitempty"`
	Action      string `json:"action,omitempty"`
	Commit      string `json:"commit,omitempty"`
	Token       string `json:"token"`
	Name        string `json:"name"`
	Size        int64  `json:"size"`
	SHA256      string `json:"sha256"`
}

type reply struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

// A bounded line keeps metadata parsing separate from the binary stream.
func readJSON(r *bufio.Reader, v any) error {
	line, err := r.ReadSlice('\n')
	if err != nil {
		return fmt.Errorf("invalid or oversized message: %w", err)
	}
	return json.Unmarshal(line, v)
}

func validName(name string) bool {
	if name == "" || name == "." || name == ".." || len(name) > 240 || strings.ContainsAny(name, "/\\:") {
		return false
	}
	for _, c := range name {
		if unicode.IsControl(c) {
			return false
		}
	}
	return true
}

func reject(c net.Conn, message string) error {
	_ = json.NewEncoder(c).Encode(reply{Error: message})
	return errors.New(message)
}

// Receive handles one file. inbox must exist and be writable only by its owner.
// The final file appears only after the entire payload passes its SHA-256 check.
// Linking instead of renaming prevents replacing an existing file or symlink.
func Receive(c net.Conn, inbox, token string, maxBytes int64) (string, error) {
	return ReceiveWithPairing(c, inbox, token, maxBytes, "", nil)
}

func ReceiveWithPairing(c net.Conn, inbox, token string, maxBytes int64, pin string, approve PairHandler) (string, error) {
	return receive(c, inbox, token, maxBytes, pin, approve, nil)
}
func ReceiveTrusted(c net.Conn, inbox string, maxBytes int64, book *DeviceBook) (string, error) {
	return receive(c, inbox, "", maxBytes, "", nil, book)
}
func receive(c net.Conn, inbox, token string, maxBytes int64, pin string, approve PairHandler, book *DeviceBook) (string, error) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(15 * time.Second))
	r := bufio.NewReaderSize(c, 4096)
	var h header
	if err := readJSON(r, &h); err != nil {
		return "", err
	}
	if book != nil {
		if handled, e := book.HandleControl(c, h); handled {
			return "", e
		}
		if !book.Authorize(h.Token, c) {
			return "", reject(c, "device access revoked; scan Mac QR again")
		}
		defer book.Release(c)
	} else {
		if h.Action == "pair" {
			return "", receivePair(c, r, h, token, pin, approve)
		}
		if subtle.ConstantTimeCompare([]byte(h.Token), []byte(token)) != 1 {
			return "", reject(c, "connection code rejected")
		}
	}

	if !validName(h.Name) || h.Size < 0 || h.Size > maxBytes {
		return "", reject(c, "invalid filename or file too large")
	}
	expected, err := hex.DecodeString(h.SHA256)
	if err != nil || len(expected) != sha256.Size {
		return "", reject(c, "invalid checksum")
	}
	destination := filepath.Join(inbox, h.Name)
	if _, err = os.Lstat(destination); !os.IsNotExist(err) {
		return "", reject(c, "destination already exists or is inaccessible")
	}
	f, err := os.CreateTemp(inbox, ".nodi-*.part")
	if err != nil {
		return "", reject(c, "cannot create destination file")
	}
	defer os.Remove(f.Name())
	defer f.Close()
	_ = c.SetDeadline(time.Now().Add(Timeout))
	if err = json.NewEncoder(c).Encode(reply{OK: true}); err != nil {
		return "", err
	}
	hash := sha256.New()
	if _, err = io.CopyN(io.MultiWriter(f, hash), r, h.Size); err != nil {
		return "", reject(c, "incomplete transfer")
	}
	if subtle.ConstantTimeCompare(hash.Sum(nil), expected) != 1 {
		return "", reject(c, "checksum mismatch; file discarded")
	}
	if err = f.Sync(); err != nil {
		return "", reject(c, "cannot save file")
	}
	if err = f.Close(); err != nil {
		return "", reject(c, "cannot close file")
	}
	if book != nil {
		err = book.Commit(h.Token, func() error { return os.Link(f.Name(), destination) })
	} else {
		err = os.Link(f.Name(), destination)
	}
	if err != nil {
		return "", reject(c, "cannot finalize file (exists or filesystem lacks hard links)")
	}
	if err = json.NewEncoder(c).Encode(reply{OK: true}); err != nil {
		return destination, fmt.Errorf("file saved but acknowledgement failed: %w", err)
	}
	return destination, nil
}

// Send verifies the receiver's exact certificate from the trusted invite.
// Plaintext files never pass through an intermediary server.
func Send(invite Invite, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return errors.New("select one regular file")
	}
	name := filepath.Base(path)
	if !validName(name) {
		return errors.New("unsupported filename")
	}
	hash := sha256.New()
	if _, err = io.Copy(hash, f); err != nil {
		return err
	}
	if _, err = f.Seek(0, io.SeekStart); err != nil {
		return err
	}
	pin, err := hex.DecodeString(invite.Fingerprint)
	if err != nil || len(pin) != 32 {
		return errors.New("invalid certificate fingerprint")
	}
	config := &tls.Config{
		MinVersion: tls.VersionTLS13,
		// Default CA validation is replaced by the exact certificate pin below.
		InsecureSkipVerify: true,
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) == 0 {
				return errors.New("receiver has no certificate")
			}
			actual := sha256.Sum256(cs.PeerCertificates[0].Raw)
			if subtle.ConstantTimeCompare(actual[:], pin) != 1 {
				return errors.New("receiver certificate mismatch")
			}
			return nil
		},
	}
	c, err := tls.DialWithDialer(&net.Dialer{Timeout: 10 * time.Second}, "tcp", invite.Address, config)
	if err != nil {
		return err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(Timeout))
	h := header{Token: invite.Token, Name: name, Size: info.Size(), SHA256: hex.EncodeToString(hash.Sum(nil))}
	if err = json.NewEncoder(c).Encode(h); err != nil {
		return err
	}
	r := bufio.NewReaderSize(c, 4096)
	var response reply
	if err = readJSON(r, &response); err != nil {
		return err
	}
	if !response.OK {
		return errors.New(response.Error)
	}
	if _, err = io.CopyN(c, f, info.Size()); err != nil {
		return err
	}
	if err = readJSON(r, &response); err != nil {
		return fmt.Errorf("no final acknowledgement; check receiver before retrying: %w", err)
	}
	if !response.OK {
		return errors.New(response.Error)
	}
	return nil
}
