package proxy

import (
	"net"
	"testing"
	"time"
)

type stubPacketConn struct {
	net.PacketConn
	closed       bool
	lastDeadline time.Time
	readErr      error
}

func (s *stubPacketConn) Close() error {
	s.closed = true
	return nil
}

func (s *stubPacketConn) SetReadDeadline(t time.Time) error {
	s.lastDeadline = t
	return nil
}

func (s *stubPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	if !s.lastDeadline.IsZero() && !time.Now().Before(s.lastDeadline) {
		return 0, nil, s.readErr
	}
	return 1, nil, nil
}

func TestDetachablePacketConnDoesNotCloseTransport(t *testing.T) {
	base := &stubPacketConn{readErr: net.ErrClosed}
	d := &detachablePacketConn{PacketConn: base}

	if err := d.Close(); err != nil {
		t.Fatalf("unexpected Close error: %v", err)
	}
	if base.closed {
		t.Fatalf("expected underlying PacketConn to remain open when detachablePacketConn is closed")
	}
	if base.lastDeadline.IsZero() {
		t.Fatalf("expected Close to cancel blocked ReadFrom via SetReadDeadline")
	}
	if _, _, err := d.ReadFrom(make([]byte, 8)); err != net.ErrClosed {
		t.Fatalf("expected ReadFrom after Close to return net.ErrClosed, got %v", err)
	}
}

func TestProxyLastBootstrapAttemptErrorStoresAndClears(t *testing.T) {
	p := &Proxy{}
	if got := p.LastBootstrapAttemptError(); got != "" {
		t.Fatalf("expected empty initial error, got %q", got)
	}
	p.lastBootstrapAttemptErr.Store("WRAP-A DTLS: handshake error")
	if got := p.LastBootstrapAttemptError(); got != "WRAP-A DTLS: handshake error" {
		t.Fatalf("unexpected stored error: %q", got)
	}
	p.lastBootstrapAttemptErr.Store("")
	if got := p.LastBootstrapAttemptError(); got != "" {
		t.Fatalf("expected empty cleared error, got %q", got)
	}
}
