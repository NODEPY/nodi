package internet

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

type RoomPeer struct {
	Fingerprint string `json:"fingerprint"`
	Name        string `json:"name"`
	Token       string `json:"token,omitempty"`
}

func Enroll(ctx context.Context, cert tls.Certificate, test bool) (*Client, string, error) {
	pinBytes := sha256.Sum256(cert.Certificate[0])
	pin := hex.EncodeToString(pinBytes[:])
	c := &Client{}
	var challenge struct {
		Nonce string `json:"nonce"`
	}
	if err := c.API(ctx, "POST", "/v1/enroll/challenge", map[string]string{"id": pin}, &challenge); err != nil {
		return nil, "", err
	}
	digest := sha256.Sum256([]byte("nodi-enroll-v1\n" + pin + "\n" + challenge.Nonce))
	key, ok := cert.PrivateKey.(*ecdsa.PrivateKey)
	if !ok {
		return nil, "", errors.New("unsupported identity")
	}
	sig, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		return nil, "", err
	}
	var response struct {
		Token string `json:"token"`
	}
	err = c.API(ctx, "POST", "/v1/enroll", map[string]any{"id": pin, "nonce": challenge.Nonce, "certificate": base64.StdEncoding.EncodeToString(cert.Certificate[0]), "signature": base64.StdEncoding.EncodeToString(sig), "testInstall": test}, &response)
	if err != nil {
		return nil, "", err
	}
	c.Token = response.Token
	payload, _ := json.Marshal(map[string]string{"fingerprint": pin, "token": c.Token})
	return c, "nodi-internet1-" + base64.RawURLEncoding.EncodeToString(payload), nil
}
func NormalizeRoom(code string) (string, error) {
	code = strings.ToUpper(strings.ReplaceAll(strings.ReplaceAll(strings.TrimSpace(code), "-", ""), " ", ""))
	if len(code) != 16 {
		return "", errors.New("Код кімнати має містити 16 символів")
	}
	if _, e := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(code); e != nil {
		return "", errors.New("Неправильний код кімнати")
	}
	return code, nil
}
func roomID(code string) string {
	b := sha256.Sum256([]byte("nodi-room-id-v1\n" + code))
	return hex.EncodeToString(b[:])
}
func roomCipher(code string) (cipher.AEAD, error) {
	key := sha256.Sum256([]byte("nodi-room-key-v1\n" + code))
	b, e := aes.NewCipher(key[:])
	if e != nil {
		return nil, e
	}
	return cipher.NewGCM(b)
}
func encryptPeer(code string, p RoomPeer) (string, error) {
	a, e := roomCipher(code)
	if e != nil {
		return "", e
	}
	n := make([]byte, a.NonceSize())
	if _, e = rand.Read(n); e != nil {
		return "", e
	}
	b, _ := json.Marshal(p)
	return base64.StdEncoding.EncodeToString(a.Seal(n, n, b, []byte(roomID(code)))), nil
}
func decryptPeer(code, blob string) (RoomPeer, error) {
	var p RoomPeer
	a, e := roomCipher(code)
	if e != nil {
		return p, e
	}
	b, e := base64.StdEncoding.DecodeString(blob)
	if e != nil || len(b) < a.NonceSize() {
		return p, errors.New("invalid room payload")
	}
	b, e = a.Open(nil, b[:a.NonceSize()], b[a.NonceSize():], []byte(roomID(code)))
	if e != nil {
		return p, e
	}
	e = json.Unmarshal(b, &p)
	if len(p.Fingerprint) != 64 || len(p.Name) > 160 {
		return p, errors.New("invalid room peer")
	}
	return p, e
}
func pause(ctx context.Context) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(2 * time.Second):
		return nil
	}
}

// Host keeps the transfer token encrypted under the out-of-band room code.
func (c *Client) Host(ctx context.Context, own RoomPeer, onCode func(string), approve func(RoomPeer, string) bool, save func(RoomPeer) error) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	bytes := make([]byte, 10)
	if _, e := rand.Read(bytes); e != nil {
		return e
	}
	code := base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(bytes)
	token := make([]byte, 32)
	if _, e := rand.Read(token); e != nil {
		return e
	}
	own.Token = hex.EncodeToString(token)
	blob, e := encryptPeer(code, own)
	if e != nil {
		return e
	}
	id := roomID(code)
	if e = c.API(ctx, "POST", "/v1/rooms", map[string]string{"id": id, "blob": blob}, nil); e != nil {
		return e
	}
	success := false
	defer func() {
		if !success {
			c.deleteRoom(id)
		}
	}()
	display := code[:4] + "-" + code[4:8] + "-" + code[8:12] + "-" + code[12:]
	onCode(display)
	for {
		var r struct {
			Guest string `json:"guest"`
			Blob  string `json:"blob"`
		}
		if e = c.API(ctx, "GET", "/v1/rooms/"+id, nil, &r); e != nil {
			return e
		}
		if r.Guest != "" {
			peer, e := decryptPeer(code, r.Blob)
			if e != nil {
				return e
			}
			if peer.Fingerprint != r.Guest || peer.Fingerprint == own.Fingerprint {
				return errors.New("room identity mismatch")
			}
			peer.Token = own.Token
			if !approve(peer, display) {
				return errors.New("Підключення скасовано")
			}
			if e = save(peer); e != nil {
				return e
			}
			if e = c.API(ctx, "POST", "/v1/rooms/"+id+"/approve", map[string]string{}, nil); e != nil {
				return e
			}
			success = true
			return nil
		}
		if e = pause(ctx); e != nil {
			return e
		}
	}
}
func (c *Client) Join(ctx context.Context, code string, own RoomPeer, save func(RoomPeer) error) error {
	code, e := NormalizeRoom(code)
	if e != nil {
		return e
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	blob, e := encryptPeer(code, own)
	if e != nil {
		return e
	}
	id := roomID(code)
	var joined struct {
		Host string `json:"host"`
	}
	if e = c.API(ctx, "POST", "/v1/rooms/join", map[string]string{"id": id, "blob": blob}, &joined); e != nil {
		return e
	}
	defer c.deleteRoom(id)
	for {
		var r struct {
			Approved bool   `json:"approved"`
			Blob     string `json:"blob"`
			Host     string `json:"host"`
		}
		if e = c.API(ctx, "GET", "/v1/rooms/"+id, nil, &r); e != nil {
			return e
		}
		if r.Approved {
			peer, e := decryptPeer(code, r.Blob)
			if e != nil {
				return e
			}
			if peer.Fingerprint != joined.Host || peer.Fingerprint != r.Host || len(peer.Token) != 64 {
				return errors.New("room identity mismatch")
			}
			return save(peer)
		}
		if e = pause(ctx); e != nil {
			return e
		}
	}
}
func (c *Client) deleteRoom(id string) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = c.API(ctx, "DELETE", "/v1/rooms/"+id, nil, nil)
}
