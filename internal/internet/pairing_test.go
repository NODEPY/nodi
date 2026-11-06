package internet

import (
	"strings"
	"testing"
)

func TestRoomEncryptionAndNormalization(t *testing.T) {
	code, err := NormalizeRoom("abcd-efgh-ijkl-mnop")
	if err != nil || code != "ABCDEFGHIJKLMNOP" {
		t.Fatal(code, err)
	}
	peer := RoomPeer{Fingerprint: strings.Repeat("a", 64), Name: "Телефон", Token: strings.Repeat("b", 64)}
	blob, err := encryptPeer(code, peer)
	if err != nil {
		t.Fatal(err)
	}
	got, err := decryptPeer(code, blob)
	if err != nil || got != peer {
		t.Fatal(got, err)
	}
	if _, err = decryptPeer("BBCDEFGHIJKLMNOP", blob); err == nil {
		t.Fatal("wrong room code accepted")
	}
	if _, err = NormalizeRoom("ABC"); err == nil {
		t.Fatal("short code accepted")
	}
}
