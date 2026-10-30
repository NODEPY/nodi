package transfer

import (
	"bufio"
	"crypto/tls"
	"encoding/json"
	"net"
	"testing"
)

func TestRequestWiFiUsesPinnedTLS(t *testing.T) {
	for _, mismatch := range []bool{false, true} {
		cert, pin, _, err := NewIdentity()
		if err != nil {
			t.Fatal(err)
		}
		ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13})
		if err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		own := nonce()
		go func() {
			c, e := ln.Accept()
			if e != nil {
				done <- e
				return
			}
			defer c.Close()
			var h header
			e = readJSON(bufio.NewReaderSize(c, 4096), &h)
			if e == nil {
				if h.Action != "wifi_request" || h.Fingerprint != own || h.Port != 7331 || h.Secret != "" || h.Token != "" {
					t.Error("unexpected knock payload")
				}
				e = json.NewEncoder(c).Encode(reply{OK: true})
			}
			done <- e
		}()
		expected := pin
		if mismatch {
			expected = nonce()
		}
		err = RequestWiFi(ln.Addr().String(), expected, own, 7331)
		if (err != nil) != mismatch {
			t.Fatalf("mismatch=%v error=%v", mismatch, err)
		}
		<-done
		ln.Close()
	}
}
func TestRequestWiFiRejectsInvalidEndpoint(t *testing.T) {
	for _, addr := range []string{"example.org:443", net.JoinHostPort("127.0.0.1", "bad"), "127.0.0.1"} {
		if RequestWiFi(addr, nonce(), nonce(), 0) == nil {
			t.Fatal("invalid request accepted")
		}
	}
}
