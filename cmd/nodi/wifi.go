package main

import (
	"context"
	"nodi/internal/transfer"
	"sync"
	"time"
)

type wifiBroker struct {
	mu     sync.Mutex
	id     string
	answer chan bool
}

func (b *wifiBroker) respond(id string, allow bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if id == b.id && b.answer != nil {
		select {
		case b.answer <- allow:
		default:
		}
	}
}
func (b *wifiBroker) approve(ctx context.Context, p transfer.PairRequest) bool {
	b.mu.Lock()
	if b.answer != nil {
		b.mu.Unlock()
		return false
	}
	ch := make(chan bool, 1)
	b.id = p.ID
	b.answer = ch
	b.mu.Unlock()
	defer func() {
		b.mu.Lock()
		b.id = ""
		b.answer = nil
		b.mu.Unlock()
		report(true, event{Type: "pair_closed", ID: p.ID}, "")
	}()
	report(true, event{Type: "pair_request", ID: p.ID, Name: p.Name, Compare: p.Compare, Pin: p.Fingerprint}, "")
	timer := time.NewTimer(60 * time.Second)
	defer timer.Stop()
	select {
	case allow := <-ch:
		return allow
	case <-ctx.Done():
		return false
	case <-timer.C:
		return false
	}
}
