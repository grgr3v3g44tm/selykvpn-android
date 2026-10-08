package proxy

// The dial hook: every destination the proxy is about to send to, before its
// first packet leaves.
//
// On iOS nothing is ever set. A Network Extension's own sockets are not
// captured by its own tunnel, so the relays and VK's API hosts are reached over
// the physical path by construction. A console client that moves the DEFAULT
// route into the tunnel has no such exemption: the proxy's own traffic — TURN
// to the relays, the VK API and the captcha's hosts behind every mint — would
// go into the very tunnel it carries, and after a network change a tunnel whose
// sessions are dead could not mint its way back. The console sets a hook that
// pins each destination to the physical gateway the moment it is dialled
// (tools/vk-turn-proxy-console).
//
// 🚨 EVERY socket the proxy opens toward a remote goes through it: the TURN
// control connection of runTURN and of setupSRTPSession on both transports,
// the VK session clients (vkDiagDialer) and the browser-TLS transport
// (browserDialer). A new dial site without it is a leak that shows only in the
// field, on the console, after a network change — dialhook_test.go scans the
// package for a dialer or a socket that bypasses the hook.

import (
	"fmt"
	"net"
	"sync/atomic"
	"syscall"
)

var dialHook atomic.Pointer[func(network, address string) error]
var socketProtector atomic.Pointer[func(fd int)]

// SetSocketProtector installs a callback to protect outbound sockets (e.g. for Android VpnService.protect).
func SetSocketProtector(p func(fd int)) {
	if p == nil {
		socketProtector.Store(nil)
		return
	}
	socketProtector.Store(&p)
}

// ProtectConn invokes the socket protector on a net.Conn or net.PacketConn if available.
func ProtectConn(c any) {
	p := socketProtector.Load()
	if p == nil || c == nil {
		return
	}
	if sc, ok := c.(syscall.Conn); ok {
		if raw, err := sc.SyscallConn(); err == nil {
			_ = raw.Control(func(fd uintptr) {
				(*p)(int(fd))
			})
		}
	}
}

// SetDialHook installs h; nil removes it. h receives the network ("tcp4",
// "udp4", …) and the RESOLVED "ip:port" before the socket sends anything, and
// an error from it aborts the dial. It is called from many goroutines at once.
func SetDialHook(h func(network, address string) error) {
	if h == nil {
		dialHook.Store(nil)
		return
	}
	dialHook.Store(&h)
}

// beforeDial runs the hook, if any.
func beforeDial(network, address string) error {
	h := dialHook.Load()
	if h == nil {
		return nil
	}
	if err := (*h)(network, address); err != nil {
		return fmt.Errorf("dial hook refused %s %s: %w", network, address, err)
	}
	return nil
}

// dialControl is beforeDial in net.Dialer.Control's shape: the dialer hands it
// the address it resolved, after the socket exists and before connect — so a
// name that resolves to several addresses is asked about the one being tried.
func dialControl(network, address string, c syscall.RawConn) error {
	if c != nil {
		if p := socketProtector.Load(); p != nil {
			_ = c.Control(func(fd uintptr) {
				(*p)(int(fd))
			})
		}
	}
	return beforeDial(network, address)
}

// beforeDialUDP asks the hook about a UDP destination written to without a
// dialer (an unconnected socket pion sends on). With no hook nothing is
// resolved here: the path is the one the app had before the hook existed.
func beforeDialUDP(address string) error {
	if dialHook.Load() == nil {
		return nil
	}
	ua, err := net.ResolveUDPAddr("udp", address)
	if err != nil {
		return fmt.Errorf("resolve %s: %w", address, err)
	}
	return beforeDial(udpNetworkOf(ua), ua.String())
}

// udpNetworkOf names the address family the way net.Dialer's Control does.
func udpNetworkOf(ua *net.UDPAddr) string {
	if ua.IP.To4() != nil {
		return "udp4"
	}
	return "udp6"
}
