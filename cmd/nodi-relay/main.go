// nodi-relay is a loopback-only development rendezvous server.
package main

import (
	"flag"
	"log"
	"net"
	"nodi/internal/relay"
	"time"
)

func main() {
	addr := flag.String("listen", "127.0.0.1:8787", "loopback address for development")
	flag.Parse()
	host, _, err := net.SplitHostPort(*addr)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
		log.Fatal("prototype supports loopback only; public deployment is not implemented")
	}
	l, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("development relay listening on %s", l.Addr())
	log.Fatal(relay.New(64, 30*time.Second, 10*time.Minute).Serve(l))
}
