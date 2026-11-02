package transfer

import "errors"

// ErrBusy is returned when the handshake pool is full and the peer was dropped
// before a protocol message. A classified connection that cannot take a slot
// is rejected with a protocol error instead.
var ErrBusy = errors.New("receiver busy")

const (
	MaxTransfers = 2
	maxPairing   = 2
	maxHandshake = 8
)

// Slots split pairing from file transfer so a comparison prompt cannot occupy
// the two transfer lanes. Handshake is only held while the header is read.
type Slots struct {
	handshake chan struct{}
	transfer  chan struct{}
	pairing   chan struct{}
}

func NewSlots() *Slots {
	return &Slots{
		handshake: make(chan struct{}, maxHandshake),
		transfer:  make(chan struct{}, MaxTransfers),
		pairing:   make(chan struct{}, maxPairing),
	}
}

func controlAction(action string) bool {
	switch action {
	case "pair", "wifi_link", "link":
		return true
	}
	return false
}

func (s *Slots) enterHandshake() bool {
	select {
	case s.handshake <- struct{}{}:
		return true
	default:
		return false
	}
}

func (s *Slots) leaveHandshake() { <-s.handshake }

func (s *Slots) upgrade(control bool) bool {
	dest := s.transfer
	if control {
		dest = s.pairing
	}
	select {
	case dest <- struct{}{}:
		s.leaveHandshake()
		return true
	default:
		return false
	}
}

func (s *Slots) release(control bool) {
	if control {
		<-s.pairing
		return
	}
	<-s.transfer
}
