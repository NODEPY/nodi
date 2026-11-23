// Package internet transports the existing pinned TLS stream through Nodi's relay.
package internet

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const Server = "https://nodi-relay.igor-shura2011faik.workers.dev"

type Client struct {
	Diagnostic func(string)
	Token      string
	HTTP       *http.Client
	Base       string
}
type Session struct {
	ID     string `json:"id"`
	Ticket string `json:"ticket"`
	From   string `json:"from"`
}

func (c *Client) base() string {
	if c.Base != "" {
		return c.Base
	}
	return Server
}
func (c *Client) http() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("relay redirect refused") }}
}
func (c *Client) API(ctx context.Context, method, path string, input, output any) error {
	var b []byte
	var err error
	if input != nil {
		b, err = json.Marshal(input)
		if err != nil {
			return err
		}
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base()+path, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	if input != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := c.http().Do(req)
	if err != nil {
		return errors.New("internet relay unavailable")
	}
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return fmt.Errorf("internet relay HTTP %d", res.StatusCode)
	}
	if output != nil {
		return json.NewDecoder(io.LimitReader(res.Body, 64*1024)).Decode(output)
	}
	return nil
}
func (c *Client) Grant(ctx context.Context, pin string) error {
	return c.API(ctx, "PUT", "/v1/peers/"+pin, nil, nil)
}
func (c *Client) Forget(ctx context.Context, pin string) error {
	return c.API(ctx, "DELETE", "/v1/peers/"+pin, nil, nil)
}
func (c *Client) Create(ctx context.Context, pin string) (Session, error) {
	var s Session
	err := c.API(ctx, "POST", "/v1/sessions", map[string]string{"peer": pin}, &s)
	return s, err
}
func (c *Client) Incoming(ctx context.Context) ([]Session, error) {
	var r struct {
		Sessions []Session `json:"sessions"`
	}
	err := c.API(ctx, "GET", "/v1/incoming", nil, &r)
	return r.Sessions, err
}
func (c *Client) Cancel(s Session) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = c.API(ctx, "DELETE", "/v1/sessions/"+s.ID, nil, nil)
}

// Connect returns a bounded stream. The caller MUST authenticate the peer with
// inner TLS; relay credentials only authorize the outer transport.
func (c *Client) Connect(ctx context.Context, s Session) (net.Conn, error) {
	dialCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	h := http.Header{"Authorization": []string{"Bearer " + c.Token}, "X-Nodi-Ticket": []string{s.Ticket}}
	ws, response, err := websocket.Dial(dialCtx, strings.Replace(c.base(), "https:", "wss:", 1)+"/v1/sessions/"+s.ID, &websocket.DialOptions{HTTPHeader: h, HTTPClient: c.http()})
	if err != nil {
		if response != nil {
			return nil, fmt.Errorf("internet relay connection HTTP %d", response.StatusCode)
		}
		return nil, errors.New("internet relay connection failed")
	}
	ws.SetReadLimit(64 * 1024)
	kind, b, err := ws.Read(dialCtx)
	if err != nil || kind != websocket.MessageText || string(b) != "{\"type\":\"ready\"}" {
		ws.CloseNow()
		if err != nil {
			return nil, fmt.Errorf("internet peer not ready: %w", err)
		}
		return nil, errors.New("invalid internet ready message")
	}
	app, pump := net.Pipe()
	go func() { defer c.Cancel(s); bridge(ctx, ws, pump, c.Diagnostic) }()
	return app, nil
}
func bridge(ctx context.Context, ws *websocket.Conn, local net.Conn, diagnostic func(string)) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Minute)
	defer cancel()
	defer local.Close()
	defer ws.CloseNow()
	stopped := context.AfterFunc(ctx, func() { local.Close(); ws.CloseNow() })
	defer stopped()
	ack := make(chan struct{}, 1)
	incoming := make(chan []byte, 1)
	var pumps sync.WaitGroup
	pumps.Add(2)
	go func() {
		defer pumps.Done()
		defer cancel()
		buf := make([]byte, 32*1024)
		for {
			n, e := local.Read(buf)
			if n > 0 {
				if ws.Write(ctx, websocket.MessageBinary, buf[:n]) != nil {
					return
				}
				select {
				case <-ack:
				case <-ctx.Done():
					return
				}
			}
			if e != nil {
				return
			}
		}
	}()
	// Never block the WebSocket reader on application consumption: TLS can write
	// while reading. Its outgoing ACK may follow an incoming data frame, and must
	// remain readable even while that frame waits for the application to consume it.
	go func() {
		defer pumps.Done()
		defer cancel()
		for {
			select {
			case data := <-incoming:
				if _, e := local.Write(data); e != nil {
					return
				}
				if ws.Write(ctx, websocket.MessageBinary, nil) != nil {
					return
				}
			case <-ctx.Done():
				return
			}
		}
	}()
	for {
		kind, data, e := ws.Read(ctx)
		if e != nil || kind != websocket.MessageBinary {
			if diagnostic != nil {
				diagnostic(fmt.Sprintf("WebSocket end: %v", e))
			}
			break
		}
		if len(data) == 0 {
			select {
			case ack <- struct{}{}:
			default:
				cancel()
			}
			continue
		}
		select {
		case incoming <- data:
		default:
			cancel()
		}
	}
	cancel()
	local.Close()
	ws.CloseNow()
	pumps.Wait()
}

// RunReceiver connects only sessions from locally paired identities. LAN TLS
// handling remains unchanged behind a loopback socket; the relay sees ciphertext.
func (c *Client) RunReceiver(ctx context.Context, address string, peers func() []string, status func(string)) {
	active := map[string]bool{}
	var mu sync.Mutex
	var workers sync.WaitGroup
	defer workers.Wait()
	granted := map[string]bool{}
	for ctx.Err() == nil {
		allowed := map[string]bool{}
		for _, pin := range peers() {
			allowed[pin] = true
			if !granted[pin] {
				if c.Grant(ctx, pin) == nil {
					granted[pin] = true
				}
			}
		}
		for pin := range granted {
			if !allowed[pin] {
				if c.Forget(ctx, pin) == nil {
					delete(granted, pin)
				}
			}
		}
		incoming, err := c.Incoming(ctx)
		if err != nil {
			status("Інтернет недоступний: " + err.Error())
		} else {
			status("Інтернет увімкнено")
		}
		for _, s := range incoming {
			if !allowed[s.From] {
				c.Cancel(s)
				continue
			}
			mu.Lock()
			busy := active[s.ID] || len(active) >= 2
			if !busy {
				active[s.ID] = true
			}
			mu.Unlock()
			if busy {
				continue
			}
			workers.Add(1)
			go func(s Session) {
				defer workers.Done()
				defer func() { mu.Lock(); delete(active, s.ID); mu.Unlock() }()
				remote, e := c.Connect(ctx, s)
				if e != nil {
					status("Інтернет: " + e.Error())
					c.Cancel(s)
					return
				}
				defer remote.Close()
				local, e := net.DialTimeout("tcp", address, 5*time.Second)
				if e != nil {
					return
				}
				defer local.Close()
				stop := context.AfterFunc(ctx, func() { remote.Close(); local.Close() })
				defer stop()
				done := make(chan struct{})
				go func() { io.Copy(remote, local); remote.Close(); local.Close(); close(done) }()
				io.Copy(local, remote)
				remote.Close()
				local.Close()
				<-done
			}(s)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(10 * time.Second):
		}
	}
}
