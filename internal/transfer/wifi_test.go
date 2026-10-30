package transfer

import (
	"bufio"
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"
)

func TestWiFiMutualApproval(t *testing.T) {
	for _, tc := range []struct {
		name                                 string
		selected, mac, phone, mutate, change bool
		want                                 bool
	}{
		{"approved", true, true, true, false, false, true},
		{"discovery-open", true, true, true, false, false, true},
		{"discovery-declined", true, false, true, false, false, false},
		{"not-selected", false, true, true, false, false, false},
		{"mac-declines", true, false, true, false, false, false},
		{"phone-declines", true, true, false, false, false, false},
		{"changed-commitment", true, true, true, true, false, false},
		{"selection-changed", true, true, true, false, true, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			book, _, serverPin, e := OpenDeviceBook(t.TempDir())
			if e != nil {
				t.Fatal(e)
			}
			defer book.Close()
			pin, secret, client := nonce(), nonce(), nonce()
			qr := book.RefreshQR()
			if tc.selected {
				book.SelectWiFi(pin)
			}
			if strings.HasPrefix(tc.name, "discovery-") {
				book.SelectWiFi("*")
			}
			book.WiFiApprove = func(p PairRequest) bool {
				if len(p.Compare) != 6 {
					t.Error("invalid comparison code")
				}
				if tc.change {
					book.SelectWiFi(nonce())
				}
				return tc.mac
			}
			ln, e := net.Listen("tcp", "127.0.0.1:0")
			if e != nil {
				t.Fatal(e)
			}
			defer ln.Close()
			done := make(chan error, 1)
			go func() {
				c, e := ln.Accept()
				if e != nil {
					done <- e
					return
				}
				_, e = ReceiveTrusted(c, t.TempDir(), 1024, book)
				done <- e
			}()
			c, e := net.Dial("tcp", ln.Addr().String())
			if e != nil {
				t.Fatal(e)
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(3 * time.Second))
			h := header{Action: "wifi_link", Name: "Phone", Fingerprint: pin, Secret: secret, Port: 7331, Commit: wifiCommit(client, pin, 7331, secret)}
			if tc.mutate {
				h.Port = 7332
			}
			enc := json.NewEncoder(c)
			r := bufio.NewReader(c)
			if e = enc.Encode(h); e != nil {
				t.Fatal(e)
			}
			var first struct {
				Pending bool
				Nonce   string
			}
			if e = readJSON(r, &first); e != nil {
				t.Fatal(e)
			}
			var final reply
			if first.Pending {
				if len(wifiCompare(serverPin, pin, client, first.Nonce, 7331, secret)) != 6 {
					t.Fatal("invalid SAS")
				}
				enc.Encode(map[string]string{"nonce": client})
				enc.Encode(map[string]bool{"allow": tc.phone})
				if e = readJSON(r, &final); e != nil {
					t.Fatal(e)
				}
			}
			if final.OK != tc.want {
				t.Fatalf("success=%v want %v", final.OK, tc.want)
			}
			if e = <-done; (e == nil) != tc.want {
				t.Fatalf("server error=%v", e)
			}
			peers := book.Peers()
			if (len(peers) == 1) != tc.want {
				t.Fatal("unauthorized enrollment or missing peer")
			}
			if tc.want && (peers[0].Token != secret || peers[0].Fingerprint != pin) {
				t.Fatal("wrong capability")
			}
			if book.bootstrap != qr {
				t.Fatal("Wi-Fi changed QR pairing")
			}
		})
	}
}

func TestWiFiExpiredSelection(t *testing.T) {
	book, _, _, e := OpenDeviceBook(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer book.Close()
	pin := nonce()
	book.SelectWiFi(pin)
	book.wifiUntil = time.Now().Add(-time.Second)
	if e = book.enrollWiFi(Peer{Fingerprint: pin}); e == nil {
		t.Fatal("expired selection accepted")
	}
}

func TestWiFiComparisonBinding(t *testing.T) {
	s, c, n, r, key := strings.Repeat("1", 64), strings.Repeat("2", 64), strings.Repeat("3", 64), strings.Repeat("4", 64), strings.Repeat("5", 64)
	original := wifiCompare(s, c, n, r, 7331, key)
	for _, v := range []string{
		wifiCompare(s, c, n, r, 7332, key), wifiCompare(s, c, n, r, 7331, s),
		wifiCompare(c, c, n, r, 7331, key), wifiCompare(s, s, n, r, 7331, key),
		wifiCompare(s, c, r, r, 7331, key),
	} {
		if original == v {
			t.Fatal("SAS did not bind transcript")
		}
	}
	t.Log("interop vector", original)
}
