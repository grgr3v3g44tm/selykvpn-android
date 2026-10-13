package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/cacggghp/vk-turn-proxy/pkg/proxy"
	"github.com/cacggghp/vk-turn-proxy/pkg/turnbind"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type TunnelRunner struct {
	mu sync.RWMutex

	state       TunnelState
	lastError   string
	profileName string
	mode        string
	startTime   time.Time

	proxyInstance *proxy.Proxy
	turnBind      *turnbind.TURNBind
	wgDevice      *device.Device
	platformTun   *WindowsTUN
	localProxy    *LocalProxyServer

	turnServerIP string
	clientIP     string
	bytesRx      atomic.Uint64
	bytesTx      atomic.Uint64
	lastRx       uint64
	lastTx       uint64
	lastCalcTime time.Time
	speedRx      atomic.Uint64
	speedTx      atomic.Uint64
	latencyMs    atomic.Int64

	statsCancel context.CancelFunc
}

var globalRunner = &TunnelRunner{
	state: StateDisconnected,
}

func (r *TunnelRunner) GetStatus() StatusResponse {
	r.mu.RLock()
	defer r.mu.RUnlock()

	var uptime int64
	if r.state == StateConnected && !r.startTime.IsZero() {
		uptime = int64(time.Since(r.startTime).Seconds())
	}

	proxyAddr := ""
	httpProxyAddr := ""
	if r.localProxy != nil {
		proxyAddr = fmt.Sprintf("127.0.0.1:%d", r.localProxy.socksPort)
		httpProxyAddr = fmt.Sprintf("127.0.0.1:%d", r.localProxy.httpPort)
	}

	return StatusResponse{
		State:         r.state,
		Error:         r.lastError,
		ProfileName:   r.profileName,
		Mode:          r.mode,
		BytesRx:       r.bytesRx.Load(),
		BytesTx:       r.bytesTx.Load(),
		SpeedRx:       r.speedRx.Load(),
		SpeedTx:       r.speedTx.Load(),
		LatencyMs:     r.latencyMs.Load(),
		UptimeSec:     uptime,
		LocalIP:       r.clientIP,
		ProxyAddr:     proxyAddr,
		HttpProxyAddr: httpProxyAddr,
		TurnServerIP:  r.turnServerIP,
	}
}

func (r *TunnelRunner) Start(req StartRequest) error {
	r.mu.Lock()
	if r.state == StateConnecting || r.state == StateConnected {
		r.mu.Unlock()
		return errors.New("tunnel is already active")
	}
	r.state = StateConnecting
	r.lastError = ""
	r.profileName = req.Profile.Name
	r.mode = req.Mode
	if r.mode == "" {
		r.mode = "proxy"
	}
	r.clientIP = req.Profile.WireGuard.Address
	r.mu.Unlock()

	logInfo("Starting VK TURN Tunnel (Mode: %s, Profile: %s)", r.mode, req.Profile.Name)

	go func() {
		if err := r.startAsync(req); err != nil {
			logError("Tunnel start failed: %v", err)
			r.mu.Lock()
			r.state = StateError
			r.lastError = err.Error()
			r.mu.Unlock()
			r.Stop()
		}
	}()

	return nil
}

func (r *TunnelRunner) startAsync(req StartRequest) error {
	// 0. VK Cookie Auth
	if req.UseCookieAuth && req.CookieHeader != "" {
		links := []string{}
		if req.Profile.VKLink != "" {
			for _, l := range strings.Split(req.Profile.VKLink, "\n") {
				l = strings.TrimSpace(l)
				if l != "" {
					links = append(links, l)
				}
			}
		}
		logInfo("Enabling VK Cookie Auth with %d link(s)", len(links))
		proxy.SetVKCookieAuth(true, req.CookieHeader, links)
	} else {
		proxy.SetVKCookieAuth(false, "", nil)
	}

	// 1. Build proxy.Config
	pcfg := proxy.Config{
		PeerAddr: req.Profile.WireGuard.Endpoint,
		VKLink:   req.Profile.VKLink,
		UseDTLS:  req.Profile.UseDTLS,
		UseSrtp:  req.Profile.UseSRTP,
		UseWrap:  req.Profile.UseWrap,
		NumConns: req.Profile.NumConns,
	}
	if pcfg.NumConns <= 0 {
		pcfg.NumConns = 1
	}

	if req.Profile.UseWrap && req.Profile.WrapKey != "" {
		keyBytes, err := hex.DecodeString(strings.ReplaceAll(req.Profile.WrapKey, " ", ""))
		if err == nil && len(keyBytes) == 32 {
			pcfg.WrapKey = keyBytes
		}
	}

	p := proxy.NewProxy(pcfg)
	r.mu.Lock()
	r.proxyInstance = p
	r.mu.Unlock()

	logInfo("Connecting to VK TURN relays...")
	if err := p.Start(); err != nil {
		return fmt.Errorf("VK bootstrap start failed: %w", err)
	}

	if err := p.WaitBootstrap(90 * time.Second); err != nil {
		return fmt.Errorf("VK bootstrap wait failed: %w", err)
	}

	turnIP := p.TURNServerIP()
	r.mu.Lock()
	r.turnServerIP = turnIP
	r.mu.Unlock()
	logInfo("VK TURN relay session established! Server IP: %s", turnIP)

	// 2. WireGuard + Bind setup
	bind := turnbind.NewTURNBind(p)
	r.mu.Lock()
	r.turnBind = bind
	r.mu.Unlock()

	var tunDev tun.Device
	var tnet *netstack.Net

	if r.mode == "tun" {
		logInfo("Initializing Wintun network adapter 'VK TURN Proxy'...")
		pt, dev, err := createPlatformTUN(
			"VK TURN Proxy",
			req.Profile.WireGuard.MTU,
			req.Profile.WireGuard.Address,
			req.Profile.WireGuard.DNS,
			turnIP,
			req.Profile.WireGuard.Endpoint,
			req.BypassRu,
			req.BypassLan,
		)
		if err != nil {
			return fmt.Errorf("Wintun adapter creation failed: %w", err)
		}
		tunDev = dev
		r.mu.Lock()
		r.platformTun = pt
		r.mu.Unlock()
	} else {
		logInfo("Initializing userspace Netstack WireGuard tunnel...")
		cleanIP := req.Profile.WireGuard.Address
		if idx := strings.Index(cleanIP, "/"); idx != -1 {
			cleanIP = cleanIP[:idx]
		}
		localAddr, err := netip.ParseAddr(cleanIP)
		if err != nil {
			localAddr, _ = netip.ParseAddr("10.77.77.2")
		}
		dnsAddr, _ := netip.ParseAddr("10.77.77.1")
		mtu := req.Profile.WireGuard.MTU
		if mtu <= 0 {
			mtu = 1280
		}
		dev, ns, err := netstack.CreateNetTUN([]netip.Addr{localAddr}, []netip.Addr{dnsAddr}, mtu)
		if err != nil {
			return fmt.Errorf("Netstack CreateNetTUN failed: %w", err)
		}
		tunDev = dev
		tnet = ns
	}

	if tunDev != nil {
		tunDev = proxy.WrapTUNForStats(tunDev)
		devLogger := device.NewLogger(device.LogLevelSilent, "[WG] ")
		wgDev := device.NewDevice(tunDev, bind, devLogger)

		uapi := buildUAPIConfig(req.Profile.WireGuard)
		if err := wgDev.IpcSet(uapi); err != nil {
			logWarn("WireGuard IpcSet warning: %v", err)
		}

		if err := wgDev.Up(); err != nil {
			return fmt.Errorf("WireGuard Up failed: %w", err)
		}

		r.mu.Lock()
		r.wgDevice = wgDev
		r.mu.Unlock()
		logInfo("WireGuard device is UP and routing packets")
	}

	// 3. Start local SOCKS5 & HTTP Proxy server
	prxPort := req.ProxyPort
	if prxPort <= 0 {
		prxPort = 10808
	}
	httpPort := req.HttpProxyPort
	if httpPort <= 0 {
		httpPort = 10809
	}

	dialer := func(ctx context.Context, network, addr string) (net.Conn, error) {
		if tnet != nil {
			return tnet.DialContext(ctx, network, addr)
		}
		var d net.Dialer
		d.Timeout = 15 * time.Second
		return d.DialContext(ctx, network, addr)
	}

	localPrx := newLocalProxyServer(prxPort, httpPort, dialer)
	if err := localPrx.Start(); err != nil {
		logWarn("Cannot start local proxy on %d/%d: %v", prxPort, httpPort, err)
	} else {
		r.mu.Lock()
		r.localProxy = localPrx
		r.mu.Unlock()
	}

	// 4. Mark connected & start stats ticker
	r.mu.Lock()
	r.state = StateConnected
	r.startTime = time.Now()
	r.lastCalcTime = time.Now()
	ctx, cancel := context.WithCancel(context.Background())
	r.statsCancel = cancel
	r.mu.Unlock()

	go r.statsLoop(ctx)
	logInfo("VK TURN Proxy successfully connected and active!")
	return nil
}

func (r *TunnelRunner) Stop() {
	r.mu.Lock()
	if r.state == StateDisconnected {
		r.mu.Unlock()
		return
	}
	r.state = StateDisconnecting
	logInfo("Stopping VK TURN Tunnel...")

	if r.statsCancel != nil {
		r.statsCancel()
		r.statsCancel = nil
	}

	// Stop local proxy
	if r.localProxy != nil {
		r.localProxy.Stop()
		r.localProxy = nil
	}

	// Stop WireGuard device
	if r.wgDevice != nil {
		r.wgDevice.Close()
		r.wgDevice = nil
	}

	// Stop Wintun & routes
	if r.platformTun != nil {
		r.platformTun.Close()
		r.platformTun = nil
	}

	// Stop proxy instance
	if r.proxyInstance != nil {
		r.proxyInstance.StopWithTimeout(2 * time.Second)
		r.proxyInstance = nil
	}
	r.turnBind = nil

	r.speedRx.Store(0)
	r.speedTx.Store(0)
	r.state = StateDisconnected
	r.mu.Unlock()
	logInfo("VK TURN Tunnel stopped cleanly")
}

func (r *TunnelRunner) statsLoop(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			r.mu.RLock()
			p := r.proxyInstance
			r.mu.RUnlock()

			if p != nil {
				statsJson := p.GetStats()
				// Track bytes through proxy stats
				_ = statsJson
			}

			// Estimate speeds
			now := time.Now()
			curRx := r.bytesRx.Load()
			curTx := r.bytesTx.Load()

			r.mu.Lock()
			elapsed := now.Sub(r.lastCalcTime).Seconds()
			if elapsed >= 0.5 {
				diffRx := curRx - r.lastRx
				diffTx := curTx - r.lastTx
				r.speedRx.Store(uint64(float64(diffRx) / elapsed))
				r.speedTx.Store(uint64(float64(diffTx) / elapsed))
				r.lastRx = curRx
				r.lastTx = curTx
				r.lastCalcTime = now
			}
			r.mu.Unlock()
		}
	}
}

func buildUAPIConfig(wg WireGuardConfig) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("private_key=%s\n", keyToHex(wg.PrivateKey)))
	sb.WriteString("replace_peers=true\n")
	sb.WriteString(fmt.Sprintf("public_key=%s\n", keyToHex(wg.PeerPublicKey)))
	sb.WriteString(fmt.Sprintf("endpoint=%s\n", wg.Endpoint))
	sb.WriteString("persistent_keepalive_interval=25\n")
	sb.WriteString("allowed_ip=0.0.0.0/0\n")
	sb.WriteString("allowed_ip=::/0\n")
	if strings.TrimSpace(wg.PreSharedKey) != "" {
		sb.WriteString(fmt.Sprintf("preshared_key=%s\n", keyToHex(wg.PreSharedKey)))
	}
	return sb.String()
}

func keyToHex(k string) string {
	trimmed := strings.TrimSpace(k)
	if len(trimmed) == 64 {
		return strings.ToLower(trimmed)
	}
	bytes, err := base64.StdEncoding.DecodeString(trimmed)
	if err == nil {
		return hex.EncodeToString(bytes)
	}
	return ""
}
