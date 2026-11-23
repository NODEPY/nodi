// Package transfer implements Nodi's first direct, encrypted file transfer.
package transfer

import (
	"bufio"
	"bytes"
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
	"unicode/utf8"
)

const (
	IdleTimeout   = 2 * time.Minute
	HeaderTimeout = 5 * time.Second
	copyChunk     = 32 * 1024
	progressEvery = 256 * 1024
	// MaxTextBytes bounds a clipboard payload. Text arrives in one frame, so it
	// uses the header timeout instead of the long file transfer deadline.
	MaxTextBytes = 64 * 1024
)

var idleTimeout = IdleTimeout
var headerTimeout = HeaderTimeout

// TextHandler receives one validated UTF-8 payload from a paired sender.
// Returning an error rejects the message and reports the reason to the peer.
type TextHandler func(text string) error

type Progress struct {
	Stage string
	Done  int64
	Total int64
}

// Invite is a secret, temporary capability: anyone holding it can send files.
// Share it only with the intended sender through a trusted channel.
type Invite struct {
	// Fallback is used only after TCP dial failure, never after TLS or delivery.
	Fallback    func() (net.Conn, error) `json:"-"`
	Address     string                   `json:"address"`
	Token       string                   `json:"token"`
	Fingerprint string                   `json:"fingerprint"`
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
	return Serve(c, inbox, token, maxBytes, "", nil, nil, nil)
}

func ReceiveWithPairing(c net.Conn, inbox, token string, maxBytes int64, pin string, approve PairHandler) (string, error) {
	return Serve(c, inbox, token, maxBytes, pin, approve, nil, nil)
}
func ReceiveTrusted(c net.Conn, inbox string, maxBytes int64, book *DeviceBook) (string, error) {
	return Serve(c, inbox, "", maxBytes, "", nil, book, nil)
}
func Serve(c net.Conn, inbox, token string, maxBytes int64, pin string, approve PairHandler, book *DeviceBook, slots *Slots) (string, error) {
	return receive(c, inbox, token, maxBytes, pin, approve, book, slots, nil)
}

// ServeText is Serve with clipboard delivery. Text messages are rejected when
// onText is nil, so a caller that only handles files never silently drops them.
func ServeText(c net.Conn, inbox, token string, maxBytes int64, pin string, approve PairHandler, book *DeviceBook, slots *Slots, onText TextHandler) (string, error) {
	return receive(c, inbox, token, maxBytes, pin, approve, book, slots, onText)
}
func receive(c net.Conn, inbox, token string, maxBytes int64, pin string, approve PairHandler, book *DeviceBook, slots *Slots, onText TextHandler) (string, error) {
	defer c.Close()
	inHandshake := false
	if slots != nil {
		if !slots.enterHandshake() {
			return "", ErrBusy
		}
		inHandshake = true
		defer func() {
			if inHandshake {
				slots.leaveHandshake()
			}
		}()
	}
	_ = c.SetDeadline(time.Now().Add(headerTimeout))
	r := bufio.NewReaderSize(c, 4096)
	var h header
	if err := readJSON(r, &h); err != nil {
		return "", err
	}
	control := controlAction(h.Action)
	if slots != nil {
		if !slots.upgrade(control) {
			return "", reject(c, ErrBusy.Error())
		}
		inHandshake = false
		defer slots.release(control)
	}
	if book != nil {
		if handled, e := book.HandleControl(c, h, r); handled {
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

	if h.Action == "text" {
		return "", receiveText(c, r, h, onText)
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
	if err = ensureSpace(inbox, h.Size); err != nil {
		return "", reject(c, err.Error())
	}
	f, err := os.CreateTemp(inbox, ".nodi-*.part")
	if err != nil {
		return "", reject(c, "cannot create destination file")
	}
	defer os.Remove(f.Name())
	defer f.Close()
	_ = c.SetDeadline(time.Now().Add(idleTimeout))
	if err = json.NewEncoder(c).Encode(reply{OK: true}); err != nil {
		return "", err
	}
	hash := sha256.New()
	if err = copyNIdle(io.MultiWriter(f, hash), r, h.Size, c, nil); err != nil {
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

// receiveText accepts one clipboard frame. It has no filename and never touches
// the inbox: the payload stays in memory and is handed to the UI as a string.
func receiveText(c net.Conn, r *bufio.Reader, h header, deliver TextHandler) error {
	if deliver == nil {
		return reject(c, "text is not supported by this receiver")
	}
	if h.Size < 1 || h.Size > MaxTextBytes {
		return reject(c, "text must be between 1 byte and 64 KiB")
	}
	expected, err := hex.DecodeString(h.SHA256)
	if err != nil || len(expected) != sha256.Size {
		return reject(c, "invalid checksum")
	}
	_ = c.SetDeadline(time.Now().Add(idleTimeout))
	if err = json.NewEncoder(c).Encode(reply{OK: true}); err != nil {
		return err
	}
	var body bytes.Buffer
	hash := sha256.New()
	if err = copyNIdle(io.MultiWriter(&body, hash), r, h.Size, c, nil); err != nil {
		return reject(c, "incomplete text")
	}
	if subtle.ConstantTimeCompare(hash.Sum(nil), expected) != 1 {
		return reject(c, "checksum mismatch; text discarded")
	}
	text := body.String()
	if !validText(text) {
		return reject(c, "text is not valid UTF-8")
	}
	if err = deliver(text); err != nil {
		return reject(c, err.Error())
	}
	if err = json.NewEncoder(c).Encode(reply{OK: true}); err != nil {
		return fmt.Errorf("text delivered but acknowledgement failed: %w", err)
	}
	return nil
}

// validText keeps the payload printable: clipboard text may contain newlines,
// tabs and ordinary Unicode, but never NUL or other control characters.
func validText(text string) bool {
	if !utf8.ValidString(text) {
		return false
	}
	for _, r := range text {
		if r == 0 || (unicode.IsControl(r) && r != '\n' && r != '\r' && r != '\t') {
			return false
		}
	}
	return true
}

// Send verifies the receiver's exact certificate from the trusted invite.
// Plaintext files never pass through an intermediary server.
func Send(invite Invite, path string) error {
	return SendProgress(invite, path, nil)
}

func SendProgress(invite Invite, path string, report func(Progress)) error {
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
	sum, err := hashFile(f, info.Size(), report)
	if err != nil {
		return err
	}
	if _, err = f.Seek(0, io.SeekStart); err != nil {
		return err
	}
	c, err := dialPinned(invite)
	if err != nil {
		return err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(headerTimeout))
	h := header{Token: invite.Token, Name: name, Size: info.Size(), SHA256: hex.EncodeToString(sum)}
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
	_ = c.SetDeadline(time.Now().Add(idleTimeout))
	if err = copyNIdle(c, f, info.Size(), c, report); err != nil {
		return err
	}
	_ = c.SetDeadline(time.Now().Add(headerTimeout))
	if err = readJSON(r, &response); err != nil {
		return fmt.Errorf("no final acknowledgement; check receiver before retrying: %w", err)
	}
	if !response.OK {
		return errors.New(response.Error)
	}
	return nil
}

// dialPinned replaces CA validation with the exact certificate from the invite.
// A stable QR pairing therefore still trusts only that one device identity.
func dialPinned(invite Invite) (*tls.Conn, error) {
	pin, err := hex.DecodeString(invite.Fingerprint)
	if err != nil || len(pin) != 32 {
		return nil, errors.New("invalid certificate fingerprint")
	}
	config := &tls.Config{
		MinVersion: tls.VersionTLS13,
		// Default CA validation is replaced by the exact certificate pin below.
		InsecureSkipVerify: true,
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) != 1 {
				return errors.New("receiver must present exactly one certificate")
			}
			actual := sha256.Sum256(cs.PeerCertificates[0].Raw)
			if subtle.ConstantTimeCompare(actual[:], pin) != 1 {
				return errors.New("receiver certificate mismatch")
			}
			return nil
		},
	}
	raw, err := net.DialTimeout("tcp", invite.Address, 4*time.Second)
	if err != nil && invite.Fallback != nil {
		raw, err = invite.Fallback()
	}
	if err != nil {
		return nil, err
	}
	secure := tls.Client(raw, config)
	_ = secure.SetDeadline(time.Now().Add(15 * time.Second))
	if err = secure.Handshake(); err != nil {
		raw.Close()
		return nil, err
	}
	_ = secure.SetDeadline(time.Time{})
	return secure, nil
}

// SendText delivers one clipboard payload to a paired device. The token,
// certificate pin and TLS 1.3 channel are the same as for a file.
func SendText(invite Invite, text string) error {
	if text == "" || len(text) > MaxTextBytes {
		return errors.New("text must be between 1 byte and 64 KiB")
	}
	if !validText(text) {
		return errors.New("text contains unsupported characters")
	}
	sum := sha256.Sum256([]byte(text))
	c, err := dialPinned(invite)
	if err != nil {
		return err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(headerTimeout))
	h := header{Token: invite.Token, Action: "text", Size: int64(len(text)), SHA256: hex.EncodeToString(sum[:])}
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
	_ = c.SetDeadline(time.Now().Add(idleTimeout))
	if err = copyNIdle(c, strings.NewReader(text), int64(len(text)), c, nil); err != nil {
		return err
	}
	_ = c.SetDeadline(time.Now().Add(headerTimeout))
	if err = readJSON(r, &response); err != nil {
		return fmt.Errorf("no final acknowledgement; check the other device before retrying: %w", err)
	}
	if !response.OK {
		return errors.New(response.Error)
	}
	return nil
}

func hashFile(f *os.File, total int64, report func(Progress)) ([]byte, error) {
	h := sha256.New()
	buf := make([]byte, copyChunk)
	var done, last int64
	last = -1
	for {
		n, err := f.Read(buf)
		if n > 0 {
			_, _ = h.Write(buf[:n])
			done += int64(n)
			emitProgress(report, "hashing", done, total, &last)
		}
		if err == io.EOF {
			emitProgress(report, "hashing", done, total, &last)
			return h.Sum(nil), nil
		}
		if err != nil {
			return nil, err
		}
	}
}

func emitProgress(report func(Progress), stage string, done, total int64, last *int64) {
	if report == nil {
		return
	}
	if done != total && *last >= 0 && done-*last < progressEvery {
		return
	}
	*last = done
	report(Progress{Stage: stage, Done: done, Total: total})
}

func copyNIdle(dst io.Writer, src io.Reader, n int64, conn net.Conn, report func(Progress)) error {
	if n == 0 {
		emitProgress(report, "sending", 0, 0, new(int64))
		return nil
	}
	buf := make([]byte, copyChunk)
	var copied, last int64
	last = -1
	_ = conn.SetDeadline(time.Now().Add(idleTimeout))
	for copied < n {
		want := len(buf)
		if left := n - copied; int64(want) > left {
			want = int(left)
		}
		nr, rerr := src.Read(buf[:want])
		if nr > 0 {
			_ = conn.SetDeadline(time.Now().Add(idleTimeout))
			nw, werr := dst.Write(buf[:nr])
			copied += int64(nw)
			if werr != nil {
				return werr
			}
			if nw != nr {
				return io.ErrShortWrite
			}
			_ = conn.SetDeadline(time.Now().Add(idleTimeout))
			emitProgress(report, "sending", copied, n, &last)
		}
		if rerr != nil {
			if rerr == io.EOF {
				return io.ErrUnexpectedEOF
			}
			return rerr
		}
	}
	return nil
}
