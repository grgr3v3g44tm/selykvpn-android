package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type LocalProxyServer struct {
	socksPort int
	httpPort  int

	socksListener net.Listener
	httpListener  net.Listener

	mu       sync.Mutex
	running  bool
	dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)
}

func newLocalProxyServer(socksPort, httpPort int, dialer func(ctx context.Context, network, addr string) (net.Conn, error)) *LocalProxyServer {
	if socksPort <= 0 {
		socksPort = 10808
	}
	if httpPort <= 0 {
		httpPort = 10809
	}
	if dialer == nil {
		dialer = func(ctx context.Context, network, addr string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, addr)
		}
	}
	return &LocalProxyServer{
		socksPort: socksPort,
		httpPort:  httpPort,
		dialFunc:  dialer,
	}
}

func (s *LocalProxyServer) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return nil
	}

	// 1. SOCKS5 Listener
	sLn, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", s.socksPort))
	if err != nil {
		return fmt.Errorf("failed to bind SOCKS5 port %d: %w", s.socksPort, err)
	}
	s.socksListener = sLn

	// 2. HTTP Listener
	hLn, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", s.httpPort))
	if err != nil {
		sLn.Close()
		return fmt.Errorf("failed to bind HTTP port %d: %w", s.httpPort, err)
	}
	s.httpListener = hLn

	s.running = true
	logInfo("Local Proxy started: SOCKS5=127.0.0.1:%d, HTTP=127.0.0.1:%d", s.socksPort, s.httpPort)

	go s.serveSocks5(sLn)
	go s.serveHttp(hLn)

	return nil
}

func (s *LocalProxyServer) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}
	s.running = false

	if s.socksListener != nil {
		s.socksListener.Close()
	}
	if s.httpListener != nil {
		s.httpListener.Close()
	}
	logInfo("Local Proxy server stopped")
}

func (s *LocalProxyServer) serveSocks5(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go s.handleSocks5Conn(conn)
	}
}

func (s *LocalProxyServer) handleSocks5Conn(client net.Conn) {
	defer client.Close()
	client.SetDeadline(time.Now().Add(30 * time.Second))

	// 1. Auth negotiation (RFC 1928)
	buf := make([]byte, 258)
	if _, err := io.ReadFull(client, buf[:2]); err != nil {
		return
	}
	if buf[0] != 0x05 {
		return
	}
	numMethods := int(buf[1])
	if _, err := io.ReadFull(client, buf[:numMethods]); err != nil {
		return
	}

	// NO AUTH (0x00)
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. Request
	if _, err := io.ReadFull(client, buf[:4]); err != nil {
		return
	}
	if buf[0] != 0x05 || buf[1] != 0x01 { // Only CONNECT command (0x01)
		client.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch buf[3] {
	case 0x01: // IPv4
		if _, err := io.ReadFull(client, buf[:4]); err != nil {
			return
		}
		host = net.IP(buf[:4]).String()
	case 0x03: // Domain
		if _, err := io.ReadFull(client, buf[:1]); err != nil {
			return
		}
		dLen := int(buf[0])
		if _, err := io.ReadFull(client, buf[:dLen]); err != nil {
			return
		}
		host = string(buf[:dLen])
	case 0x04: // IPv6
		if _, err := io.ReadFull(client, buf[:16]); err != nil {
			return
		}
		host = net.IP(buf[:16]).String()
	default:
		client.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Port
	if _, err := io.ReadFull(client, buf[:2]); err != nil {
		return
	}
	port := (int(buf[0]) << 8) | int(buf[1])
	target := fmt.Sprintf("%s:%d", host, port)

	// 3. Connect to destination
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	remote, err := s.dialFunc(ctx, "tcp", target)
	if err != nil {
		client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	// Reply Success
	if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	client.SetDeadline(time.Time{})
	pipe(client, remote)
}

func (s *LocalProxyServer) serveHttp(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go s.handleHttpConn(conn)
	}
}

func (s *LocalProxyServer) handleHttpConn(client net.Conn) {
	defer client.Close()
	client.SetDeadline(time.Now().Add(30 * time.Second))

	br := bufio.NewReader(client)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}

	if req.Method == http.MethodConnect {
		// HTTPS Tunnel
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()

		remote, err := s.dialFunc(ctx, "tcp", req.Host)
		if err != nil {
			client.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
			return
		}
		defer remote.Close()

		client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
		client.SetDeadline(time.Time{})
		pipe(client, remote)
	} else {
		// Plain HTTP
		host := req.Host
		if !strings.Contains(host, ":") {
			host += ":80"
		}

		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()

		remote, err := s.dialFunc(ctx, "tcp", host)
		if err != nil {
			client.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
			return
		}
		defer remote.Close()

		if err := req.Write(remote); err != nil {
			return
		}

		client.SetDeadline(time.Time{})
		pipe(client, remote)
	}
}

func pipe(c1, c2 net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		io.Copy(c2, c1)
		if tc, ok := c2.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
	}()

	go func() {
		defer wg.Done()
		io.Copy(c1, c2)
		if tc, ok := c1.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
	}()

	wg.Wait()
}
