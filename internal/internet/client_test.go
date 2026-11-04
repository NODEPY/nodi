package internet

import (
	"context"
	"fmt"
	"github.com/coder/websocket"
	"io"
	"net/http"
	"net/http/httptest"
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
		_, _, e = ws.Read(ctx)
		if e != nil {
			return
		}
		ws.Write(ctx, websocket.MessageBinary, []byte("incoming"))
		ws.Write(ctx, websocket.MessageBinary, nil)
		sawSecond, sawAck := false, false
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
