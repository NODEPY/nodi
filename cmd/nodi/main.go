package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"nodi/internal/internet"
	"nodi/internal/transfer"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type invitation struct {
	Address string `json:"address"`
	Code    string `json:"code"`
}
type event struct {
	Pin     string          `json:"pin,omitempty"`
	Devices []transfer.Peer `json:"devices,omitempty"`
	ID      string          `json:"id,omitempty"`
	Name    string          `json:"name,omitempty"`
	Compare string          `json:"compare,omitempty"`
	Code    string          `json:"code,omitempty"`
	Type    string          `json:"type"`
	Message string          `json:"message,omitempty"`
	Path    string          `json:"path,omitempty"`
	Text    string          `json:"text,omitempty"`
	Invites []invitation    `json:"invites,omitempty"`
	Done    int64           `json:"done,omitempty"`
	Total   int64           `json:"total,omitempty"`
}

var outputMu sync.Mutex

func report(machine bool, e event, human string) {
	outputMu.Lock()
	defer outputMu.Unlock()
	if machine {
		_ = json.NewEncoder(os.Stdout).Encode(e)
	} else {
		fmt.Println(human)
	}
}

func main() {
	if err := run(os.Args[1:]); err != nil {
		machine := false
		for _, arg := range os.Args[1:] {
			if arg == "-json" || arg == "--json" {
				machine = true
			}
		}
		if machine {
			report(true, event{Type: "fatal", Message: err.Error()}, "")
		} else {
			fmt.Fprintln(os.Stderr, "Nodi:", err)
		}
		os.Exit(1)
	}
}
func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("usage: nodi receive | nodi send -code CODE -file PATH | nodi send-text [-stdin]")
	}
	switch args[0] {
	case "pair":
		return pair(args[1:])
	case "receive":
		return receive(args[1:])
	case "send":
		return send(args[1:])
	case "send-text":
		return sendText(args[1:])
	default:
		return fmt.Errorf("unknown command %q; use receive, send or send-text", args[0])
	}
}
func receive(args []string) error {
	flags := flag.NewFlagSet("receive", flag.ContinueOnError)
	deviceDir := flags.String("devices", "", "persistent paired-device storage")
	interactive := flags.Bool("interactive", false, "allow pairing through stdin decisions")
	out := flags.String("out", "received", "folder for received files")
	listen := flags.String("listen", "0.0.0.0:7331", "local listening address")
	max := flags.Int64("max-mib", 2048, "maximum size per file in MiB")
	machine := flags.Bool("json", false, "newline-delimited events for the desktop app")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *max < 1 || *max > 1048576 {
		return fmt.Errorf("max-mib must be between 1 and 1048576")
	}
	inbox, err := filepath.Abs(*out)
	if err != nil {
		return err
	}
	if err = os.MkdirAll(inbox, 0700); err != nil {
		return err
	}
	// A fresh receiver has no active transfers, so every leftover .nodi-*.part
	// is from a previous crash and can be removed immediately.
	if err = transfer.RemoveStaleParts(inbox, 0); err != nil {
		return err
	}
	cert, pin, token, err := transfer.NewIdentity()
	if err != nil {
		return err
	}
	var book *transfer.DeviceBook
	if *deviceDir != "" {
		if !*machine {
			return fmt.Errorf("-devices requires -json")
		}
		book, cert, pin, err = transfer.OpenDeviceBook(*deviceDir)
		if err != nil {
			return err
		}
		defer book.Close()
		token = strings.Repeat("0", 64)
	}
	listener, err := tls.Listen("tcp", *listen, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13})
	if err != nil {
		return err
	}
	defer listener.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() { <-ctx.Done(); listener.Close() }()
	host, port, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		return err
	}
	hosts := []string{host}
	if ip := net.ParseIP(host); ip != nil && ip.IsUnspecified() {
		hosts = []string{"127.0.0.1"}
		addresses, err := net.InterfaceAddrs()
		if err != nil {
			return err
		}
		for _, addr := range addresses {
			if n, ok := addr.(*net.IPNet); ok && n.IP.To4() != nil && !n.IP.IsLoopback() {
				hosts = append(hosts, n.IP.String())
			}
		}
	}
	invites := make([]invitation, 0, len(hosts))
	var human strings.Builder
	fmt.Fprintf(&human, "Receiving into: %s\nKeep this window open. Ctrl+C stops the receiver.\nSecret codes allow uploads until you stop this process.\n", inbox)
	for _, host := range hosts {
		i := transfer.Invite{Address: net.JoinHostPort(host, port), Token: token, Fingerprint: pin}
		invites = append(invites, invitation{Address: i.Address, Code: i.Encode()})
		fmt.Fprintf(&human, "\n%s\n%s\n", i.Address, i.Encode())
	}
	human.WriteString("\nWaiting for files...")
	report(*machine, event{Type: "ready", Path: inbox, Invites: invites}, human.String())
	var internetMu sync.Mutex
	var roomCancelMu sync.Mutex
	var roomCancel context.CancelFunc
	var currentInternet *internet.Client
	startInternet := func(client *internet.Client) {
		currentInternet = client
		go client.RunReceiver(ctx, net.JoinHostPort("127.0.0.1", port), func() []string {
			var pins []string
			for _, p := range book.Peers() {
				pins = append(pins, p.Fingerprint)
			}
			return pins
		}, func(message string) { report(true, event{Type: "internet", Message: message}, "") })
	}
	if book != nil && relayClient() != nil && os.Getenv("NODI_RELAY_PIN") == pin {
		startInternet(relayClient())
	}
	approve := pairingBroker(ctx, *machine && *interactive && book == nil)
	if book != nil {
		wifi := &wifiBroker{}
		book.WiFiApprove = func(p transfer.PairRequest) bool { return wifi.approve(ctx, p) }
		book.Changed = func() { report(true, event{Type: "devices", Devices: book.Peers()}, "") }
		book.Changed()
		go func() {
			scanner := bufio.NewScanner(os.Stdin)
			scanner.Buffer(make([]byte, 4096), 4096)
			for scanner.Scan() {
				var command struct {
					Address string `json:"address"`
					Type    string `json:"type"`
					Pin     string `json:"pin"`
					ID      string `json:"id"`
					Allow   bool   `json:"allow"`
				}
				if json.Unmarshal(scanner.Bytes(), &command) != nil {
					continue
				}
				switch command.Type {
				case "room_cancel":
					roomCancelMu.Lock()
					if roomCancel != nil {
						roomCancel()
					}
					roomCancelMu.Unlock()
				case "internet_enable", "room_host", "room_join":
					kind, code := command.Type, command.Address
					go func() {
						if !internetMu.TryLock() {
							report(true, event{Type: "error", Message: "Підключення вже виконується"}, "")
							return
						}
						defer internetMu.Unlock()
						roomCtx, cancelRoom := context.WithCancel(ctx)
						roomCancelMu.Lock()
						roomCancel = cancelRoom
						roomCancelMu.Unlock()
						defer func() {
							cancelRoom()
							roomCancelMu.Lock()
							roomCancel = nil
							roomCancelMu.Unlock()
							report(true, event{Type: "room_closed"}, "")
						}()
						if currentInternet == nil {
							client, activation, e := internet.Enroll(roomCtx, cert, os.Getenv("NODI_TEST_INSTALL") == "1")
							if e != nil {
								report(true, event{Type: "error", Message: e.Error()}, "")
								return
							}
							startInternet(client)
							report(true, event{Type: "internet_activation", Code: activation}, "")
						}
						own := internet.RoomPeer{Fingerprint: pin, Name: "Nodi Mac"}
						save := func(p internet.RoomPeer) error {
							return book.RememberInternet(transfer.Peer{Name: p.Name, Address: "192.0.2.1:1", Fingerprint: p.Fingerprint, Token: p.Token})
						}
						var e error
						if kind == "room_host" {
							e = currentInternet.Host(roomCtx, own, func(code string) { report(true, event{Type: "room_code", Code: code}, "") }, func(p internet.RoomPeer, code string) bool {
								return wifi.approve(roomCtx, transfer.PairRequest{ID: "internet-room", Name: p.Name, Compare: code})
							}, save)
						}
						if kind == "room_join" {
							e = currentInternet.Join(roomCtx, code, own, save)
						}
						if e != nil {
							report(true, event{Type: "error", Message: e.Error()}, "")
						} else if kind != "internet_enable" {
							report(true, event{Type: "room_connected", Message: "Пристрої підключено через інтернет"}, "")
						}
					}()
				case "wifi_select":
					book.SelectWiFi(command.Pin)
				case "wifi_request":
					book.SelectWiFi(command.Pin)
					phonePin, address := command.Pin, command.Address
					go func() {
						ownPort, _ := strconv.Atoi(port)
						if err := transfer.RequestWiFi(address, phonePin, pin, ownPort); err != nil {
							report(true, event{Type: "error", Message: err.Error()}, "")
						}
					}()
				case "pair_response":
					wifi.respond(command.ID, command.Allow)
				case "new_qr":
					secret := book.RefreshQR()
					codes := make([]invitation, 0, len(hosts))
					for _, host := range hosts {
						i := transfer.Invite{Address: net.JoinHostPort(host, port), Token: secret, Fingerprint: pin}
						codes = append(codes, invitation{Address: i.Address, Code: i.Encode()})
					}
					report(true, event{Type: "qr_ready", Invites: codes}, "")
				case "forget":
					_, e := book.Forget(command.Pin)
					if e != nil {
						report(true, event{Type: "error", Message: e.Error()}, "")
					} else {
						report(true, event{Type: "forgotten", ID: command.Pin}, "")
					}
				}
			}
		}()
	}

	slots := transfer.NewSlots()
	var workers sync.WaitGroup
	// Cancel the connections before waiting for all cleanup to finish.
	defer func() { stop(); workers.Wait() }()
	for {
		c, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		workers.Add(1)
		go func(c net.Conn) {
			defer workers.Done()
			finished := make(chan struct{})
			defer close(finished)
			go func() {
				select {
				case <-ctx.Done():
					c.Close()
				case <-finished:
				}
			}()
			path, err := transfer.ServeText(c, inbox, token, *max*1024*1024, pin, approve, book, slots,
				func(text string) error {
					report(*machine, event{Type: "text_received", Text: text}, "Отримано текст:\n"+text)
					return nil
				})
			if err != nil {
				if ctx.Err() == nil && err != transfer.ErrBusy {
					report(*machine, event{Type: "error", Message: err.Error(), Path: path}, "Transfer: "+err.Error())
				}
				return
			}
			if path == "" {
				return
			}
			report(*machine, event{Type: "received", Path: path}, fmt.Sprintf("Received: %q", path))
		}(c)
	}
}
func send(args []string) error {
	flags := flag.NewFlagSet("send", flag.ContinueOnError)
	code := flags.String("code", "", "secret connection code shown by receiver")
	stdin := flags.Bool("code-stdin", false, "read connection code from standard input")
	file := flags.String("file", "", "file to send")
	machine := flags.Bool("json", false, "newline-delimited events for the desktop app")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *file == "" {
		return fmt.Errorf("provide -file PATH")
	}
	if *stdin {
		if *code != "" {
			return fmt.Errorf("choose either -code or -code-stdin")
		}
		b, err := io.ReadAll(io.LimitReader(os.Stdin, 4097))
		if err != nil {
			return err
		}
		if len(b) > 4096 {
			return fmt.Errorf("connection code is too long")
		}
		*code = strings.TrimSpace(string(b))
	}
	invite, err := transfer.ParseInvite(*code)
	if err != nil {
		return err
	}
	configureInternet(&invite, *machine, *file)
	report(*machine, event{Type: "sending"}, "Checking file and sending...")
	if err = transfer.SendProgress(invite, *file, func(p transfer.Progress) {
		if !*machine {
			return
		}
		kind := "progress"
		if p.Stage == "hashing" {
			kind = "hashing"
		}
		report(true, event{Type: kind, Path: *file, Done: p.Done, Total: p.Total}, "")
	}); err != nil {
		return err
	}
	report(*machine, event{Type: "sent", Path: *file}, "Sent. Receiver verified and saved the file.")
	return nil
}

func sendText(args []string) error {
	flags := flag.NewFlagSet("send-text", flag.ContinueOnError)
	code := flags.String("code", "", "secret code shown by the receiving device")
	text := flags.String("text", "", "text or link to send")
	stdin := flags.Bool("stdin", false, "read one JSON payload {code,text} from standard input")
	machine := flags.Bool("json", false, "newline-delimited events for the desktop app")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *stdin {
		if *code != "" || *text != "" {
			return fmt.Errorf("choose either -stdin or -code with -text")
		}
		// The desktop app keeps secrets off the command line, where ps can read them.
		b, err := io.ReadAll(io.LimitReader(os.Stdin, 6*transfer.MaxTextBytes+8192+1))
		if err != nil {
			return err
		}
		if len(b) > 6*transfer.MaxTextBytes+8192 {
			return fmt.Errorf("text payload is too long")
		}
		var payload struct {
			Code string `json:"code"`
			Text string `json:"text"`
		}
		if err = json.Unmarshal(b, &payload); err != nil {
			return fmt.Errorf("invalid text payload")
		}
		*code, *text = payload.Code, payload.Text
	}
	if strings.TrimSpace(*text) == "" {
		return fmt.Errorf("provide -text or -stdin with a non-empty text")
	}
	invite, err := transfer.ParseInvite(*code)
	if err != nil {
		return err
	}
	configureInternet(&invite, *machine)
	report(*machine, event{Type: "sending_text"}, "Sending text...")
	if err = transfer.SendText(invite, *text); err != nil {
		return err
	}
	report(*machine, event{Type: "text_sent"}, "Text sent. The other device confirmed it.")
	return nil
}

func pair(args []string) error {
	f := flag.NewFlagSet("pair", flag.ContinueOnError)
	address := f.String("address", "", "numeric endpoint")
	pin := f.String("fingerprint", "", "public certificate fingerprint")
	name := f.String("name", "Nodi Mac", "device name")
	machine := f.Bool("json", false, "JSON events")
	if e := f.Parse(args); e != nil {
		return e
	}
	invite, e := transfer.Pair(*address, *pin, *name, func(code string) { report(*machine, event{Type: "pair_compare", Compare: code}, "Compare: "+code) })
	if e != nil {
		return e
	}
	report(*machine, event{Type: "paired", Code: invite.Encode()}, invite.Encode())
	return nil
}
func pairingBroker(ctx context.Context, enabled bool) transfer.PairHandler {
	if !enabled {
		return nil
	}
	var mu sync.Mutex
	pending := map[string]chan bool{}
	closed := false
	var last time.Time
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		scanner.Buffer(make([]byte, 4096), 4096)
		for scanner.Scan() {
			var v struct {
				Address string `json:"address"`
				Type    string `json:"type"`
				ID      string `json:"id"`
				Allow   bool   `json:"allow"`
			}
			if json.Unmarshal(scanner.Bytes(), &v) != nil || v.Type != "pair_response" {
				continue
			}
			mu.Lock()
			ch := pending[v.ID]
			if ch != nil {
				select {
				case ch <- v.Allow:
				default:
				}
			}
			mu.Unlock()
		}
		mu.Lock()
		closed = true
		for _, ch := range pending {
			select {
			case ch <- false:
			default:
			}
		}
		mu.Unlock()
	}()
	return func(p transfer.PairRequest) bool {
		mu.Lock()
		if closed || len(pending) > 0 || time.Since(last) < 3*time.Second {
			mu.Unlock()
			return false
		}
		last = time.Now()
		ch := make(chan bool, 1)
		pending[p.ID] = ch
		mu.Unlock()
		defer func() {
			mu.Lock()
			delete(pending, p.ID)
			mu.Unlock()
			report(true, event{Type: "pair_closed", ID: p.ID}, "")
		}()
		report(true, event{Type: "pair_request", ID: p.ID, Name: p.Name, Compare: p.Compare}, "")
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
}

// Per-device credentials arrive from the platform secure store, never CLI args.
func relayClient() *internet.Client {
	token := os.Getenv("NODI_RELAY_TOKEN")
	if len(token) != 64 {
		return nil
	}
	client := &internet.Client{Token: token}
	if os.Getenv("NODI_RELAY_DIAGNOSTICS") == "1" {
		client.Diagnostic = func(message string) { fmt.Fprintln(os.Stderr, message) }
	}
	return client
}
func configureInternet(invite *transfer.Invite, machine bool, files ...string) {
	relay := relayClient()
	if relay == nil {
		return
	}
	pin := invite.Fingerprint
	invite.Fallback = func() (net.Conn, error) {
		if len(files) > 0 {
			info, err := os.Stat(files[0])
			if err != nil {
				return nil, err
			}
			if info.Size() > 16*1024*1024 {
				return nil, fmt.Errorf("Інтернет-альфа: файл має бути не більшим за 16 MiB")
			}
		}
		report(machine, event{Type: "route", Message: "Інтернет"}, "")
		ctx := context.Background()
		if err := relay.Grant(ctx, pin); err != nil {
			return nil, err
		}
		session, err := relay.Create(ctx, pin)
		if err != nil {
			return nil, err
		}
		c, err := relay.Connect(ctx, session)
		if err != nil {
			relay.Cancel(session)
		}
		return c, err
	}
}
