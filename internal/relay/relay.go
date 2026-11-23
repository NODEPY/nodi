// Package relay provides an experimental, opaque rendezvous tunnel.
// The caller must run device-authenticated TLS inside the returned connection.
package relay

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

type pending struct {
	conn         net.Conn
	role         byte
	paired, done chan struct{}
}

type Server struct {
	mu             sync.Mutex
	waiting        map[[32]byte]*pending
	slots          chan struct{}
	wait, lifetime time.Duration
}

// New limits concurrent connections and bounds waiting and transfer lifetimes.
func New(limit int, wait, lifetime time.Duration) *Server {
	if limit < 2 || wait <= 0 || lifetime <= 0 {
		panic("invalid relay limits")
	}
	return &Server{waiting: make(map[[32]byte]*pending), slots: make(chan struct{}, limit), wait: wait, lifetime: lifetime}
}

func NewTicket() (string, error) {
	var b [32]byte
	_, err := rand.Read(b[:])
	return hex.EncodeToString(b[:]), err
}

// Join uses a shared random ticket and distinct roles (0 receiver, 1 sender).
// This prototype requires a trusted local transport; it is not public-server auth.
func Join(ctx context.Context, address, ticket string, role byte) (net.Conn, error) {
	b, err := hex.DecodeString(ticket)
	if err != nil || len(b) != 32 || role > 1 {
		return nil, errors.New("invalid relay ticket or role")
	}
	c, err := (&net.Dialer{}).DialContext(ctx, "tcp", address)
	if err != nil {
		return nil, err
	}
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	deadline := time.Now().Add(30 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	c.SetDeadline(deadline)
	header := append([]byte{role}, b...)
	if _, err = io.Copy(c, bytes.NewReader(header)); err != nil {
		c.Close()
		return nil, err
	}
	var ack [1]byte
	if _, err = io.ReadFull(c, ack[:]); err != nil || ack[0] != 1 {
		c.Close()
		if err == nil {
			err = errors.New("relay rejected connection")
		}
		return nil, err
	}
	c.SetDeadline(time.Time{})
	return c, nil
}

// Serve accepts connections until the listener is closed. Active connections
// have a fixed lifetime; the caller owns listener shutdown.
func (s *Server) Serve(l net.Listener) error {
	for {
		c, err := l.Accept()
		if err != nil {
			return err
		}
		select {
		case s.slots <- struct{}{}:
			go func() { defer func() { <-s.slots }(); s.handle(c) }()
		default:
			c.Close()
		}
	}
}

func (s *Server) handle(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	var h [33]byte
	if _, err := io.ReadFull(c, h[:]); err != nil || h[0] > 1 {
		return
	}
	c.SetDeadline(time.Now().Add(s.lifetime))
	var key [32]byte
	copy(key[:], h[1:])
	s.mu.Lock()
	p := s.waiting[key]
	if p == nil {
		p = &pending{conn: c, role: h[0], paired: make(chan struct{}), done: make(chan struct{})}
		s.waiting[key] = p
		s.mu.Unlock()
		timer := time.NewTimer(s.wait)
		defer timer.Stop()
		select {
		case <-p.paired:
			<-p.done
		case <-timer.C:
			s.mu.Lock()
			if s.waiting[key] == p {
				delete(s.waiting, key)
				s.mu.Unlock()
				return
			}
			s.mu.Unlock()
			<-p.done
		}
		return
	}
	if p.role == h[0] {
		s.mu.Unlock()
		return
	}
	delete(s.waiting, key)
	close(p.paired)
	s.mu.Unlock()
	defer close(p.done)
	defer p.conn.Close()
	if _, err := p.conn.Write([]byte{1}); err != nil {
		return
	}
	if _, err := c.Write([]byte{1}); err != nil {
		return
	}
	finished := make(chan struct{})
	go func() { io.Copy(c, p.conn); c.Close(); p.conn.Close(); close(finished) }()
	io.Copy(p.conn, c)
	c.Close()
	p.conn.Close()
	<-finished
}
