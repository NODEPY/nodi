package internet

import (
	"context"
	"fmt"
	"github.com/coder/websocket"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestStreamAcknowledgementsAndCancellation(t *testing.T) {
	errors := make(chan error, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "DELETE" {
			w.Write([]byte("{}"))
			return
		}
		if r.Header.Get("Authorization") != "Bearer test" || r.Header.Get("X-Nodi-Ticket") != "ticket" {
			http.Error(w, "auth", 401)
			return
		}
		ws, e := websocket.Accept(w, r, nil)
		if e != nil {
			errors <- e
			return
		}
		defer ws.CloseNow()
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		if e = ws.Write(ctx, websocket.MessageText, []byte(`{"type":"ready"}`)); e != nil {
			errors <- e
			return
		}
		kind, b, e := ws.Read(ctx)
		if e != nil {
			errors <- e
			return
		}
		if kind != websocket.MessageBinary || string(b) != "ciphertext" {
			t.Error("bad outgoing frame")
		}
		if e = ws.Write(ctx, websocket.MessageBinary, nil); e != nil {
			errors <- e
			return
		}
		if e = ws.Write(ctx, websocket.MessageBinary, []byte("reply")); e != nil {
			errors <- e
			return
		}
		_, b, e = ws.Read(ctx)
		if e != nil {
			errors <- e
			return
		}
		if len(b) != 0 {
			t.Error("missing consumption ack")
		}
		errors <- nil
		_, _, _ = ws.Read(ctx)
	}))
	defer server.Close()
	c := Client{Base: server.URL, Token: "test"}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream, e := c.Connect(ctx, Session{ID: "one", Ticket: "ticket"})
	if e != nil {
		t.Fatal(e)
	}
	defer stream.Close()
	if _, e = stream.Write([]byte("ciphertext")); e != nil {
		t.Fatal(e)
	}
	b := make([]byte, 5)
	if _, e = io.ReadFull(stream, b); e != nil {
		t.Fatal(e)
	}
	if string(b) != "reply" {
		t.Fatal("bad reply")
	}
	if e = <-errors; e != nil {
		t.Fatal(e)
	}
	cancel()
	done := make(chan error, 1)
	go func() { _, e := stream.Read(b); done <- e }()
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("cancel did not close stream")
		}
	case <-time.After(time.Second):
		t.Fatal("cancel stalled")
	}
}

// TLS may write its next record before reading a peer record. An incoming data
// frame must not prevent processing the ACK that releases that next write.
func TestIncomingDataDoesNotBlockOutgoingAck(t *testing.T) {
	done := make(chan error, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "DELETE" {
			w.Write([]byte("{}"))
			return
		}
		ws, e := websocket.Accept(w, r, nil)
		if e != nil {
			return
		}
		defer ws.CloseNow()
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		ws.Write(ctx, websocket.MessageText, []byte(`{"type":"ready"}`))
		_, first, e := ws.Read(ctx)
		if e != nil {
			return
		}
		ws.Write(ctx, websocket.MessageBinary, []byte("incoming"))
		ws.Write(ctx, websocket.MessageBinary, nil)
		// The client packs ready bytes into one frame, so "second" may already have
		// arrived together with "first" (and was acknowledged just above).
		sawSecond, sawAck := strings.Contains(string(first), "second"), false
		for !sawSecond || !sawAck {
			_, data, err := ws.Read(ctx)
			if err != nil {
				done <- err
				return
			}
			switch string(data) {
			case "second":
				sawSecond = true
				if err := ws.Write(ctx, websocket.MessageBinary, nil); err != nil {
					done <- err
					return
				}
			case "":
				sawAck = true
			default:
				done <- fmt.Errorf("unexpected payload %q", data)
				return
			}
		}
		done <- nil
		_, _, _ = ws.Read(ctx)
	}))
	defer server.Close()
	c := Client{Base: server.URL, Token: "test"}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	stream, e := c.Connect(ctx, Session{ID: "one", Ticket: "ticket"})
	if e != nil {
		t.Fatal(e)
	}
	defer stream.Close()
	if _, e = stream.Write([]byte("first")); e != nil {
		t.Fatal(e)
	}
	if _, e = stream.Write([]byte("second")); e != nil {
		t.Fatal("duplex deadlock:", e)
	}
	data := make([]byte, 8)
	if _, e = io.ReadFull(stream, data); e != nil {
		t.Fatal(e)
	}
	if string(data) != "incoming" {
		t.Fatal("wrong payload")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
}

func TestWaitIncomingLongPollsAndPaces(t *testing.T) {
	var mu sync.Mutex
	var polls int
	var sawWait bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/incoming" {
			w.WriteHeader(http.StatusOK)
			return
		}
		mu.Lock()
		polls++
		sawWait = sawWait || r.Header.Get("X-Nodi-Wait") == "1"
		mu.Unlock()
		// Behave like a relay without long-poll support: answer at once, empty.
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"sessions":[]}`))
	}))
	defer server.Close()
	oldPause, oldQuick := pollPause, quickReturn
	pollPause, quickReturn = 300*time.Millisecond, time.Second
	defer func() { pollPause, quickReturn = oldPause, oldQuick }()

	c := &Client{Token: strings.Repeat("a", 64), Base: server.URL}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	c.RunReceiver(ctx, "127.0.0.1:1", func() []string { return nil }, func(string) {})

	mu.Lock()
	defer mu.Unlock()
	if !sawWait {
		t.Fatal("receiver did not ask for a long-poll")
	}
	// One immediate first poll, then a 300 ms pause after each quick empty long-poll
	// answer: at most 5 polls in one second.
	if polls < 2 || polls > 5 {
		t.Fatalf("receiver polled %d times in 1s; quick empty answers must be paced", polls)
	}
}

// TLS writes 16 KiB records; the relay counts frames, so the tunnel must pack them.
func TestTunnelPacksRecordsIntoLargerFrames(t *testing.T) {
	type result struct {
		frames, bytes, largest int
		err                    error
	}
	done := make(chan result, 1)
	const chunk, chunks = 16 * 1024, 10
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "DELETE" {
			w.Write([]byte("{}"))
			return
		}
		ws, e := websocket.Accept(w, r, nil)
		if e != nil {
			return
		}
		defer ws.CloseNow()
		ws.SetReadLimit(64 * 1024) // the relay's MAX_FRAME
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		ws.Write(ctx, websocket.MessageText, []byte(`{"type":"ready"}`))
		var res result
		for res.bytes < chunk*chunks {
			_, data, err := ws.Read(ctx)
			if err != nil {
				res.err = err
				break
			}
			res.frames++
			res.bytes += len(data)
			if len(data) > res.largest {
				res.largest = len(data)
			}
			ws.Write(ctx, websocket.MessageBinary, nil) // ACK, as the relay peer does
		}
		done <- res
		_, _, _ = ws.Read(ctx)
	}))
	defer server.Close()
	c := Client{Base: server.URL, Token: "test"}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	stream, e := c.Connect(ctx, Session{ID: "one", Ticket: "ticket"})
	if e != nil {
		t.Fatal(e)
	}
	defer stream.Close()
	go func() {
		block := make([]byte, chunk)
		for i := 0; i < chunks; i++ {
			if _, err := stream.Write(block); err != nil {
				return
			}
		}
	}()
	res := <-done
	if res.err != nil {
		t.Fatal(res.err)
	}
	if res.largest > 64*1024 {
		t.Fatalf("frame of %d bytes exceeds the relay limit", res.largest)
	}
	if res.frames >= chunks {
		t.Fatalf("%d records were sent as %d frames; expected them to be packed", chunks, res.frames)
	}
	t.Logf("%d × 16 KiB records → %d frames (largest %d bytes)", chunks, res.frames, res.largest)
}
