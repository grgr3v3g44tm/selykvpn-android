//go:build windows

package main

import (
	"fmt"
	"net"
	"os/exec"
	"strings"

	"golang.zx2c4.com/wireguard/tun"
)

type WindowsTUN struct {
	dev        tun.Device
	name       string
	clientIP   string
	turnIP     string
	peerIP     string
	defGateway string
	bypassRu   bool
	bypassLan  bool
}

func createPlatformTUN(name string, mtu int, clientIP string, dns string, turnIP string, peerEndpoint string, bypassRu bool, bypassLan bool) (*WindowsTUN, tun.Device, error) {
	if mtu <= 0 {
		mtu = 1280
	}
	dev, err := tun.CreateTUN(name, mtu)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to create Wintun adapter: %w", err)
	}

	cleanIP := clientIP
	if idx := strings.Index(cleanIP, "/"); idx != -1 {
		cleanIP = cleanIP[:idx]
	}

	peerHost := peerEndpoint
	if idx := strings.Index(peerHost, ":"); idx != -1 {
		peerHost = peerHost[:idx]
	}

	w := &WindowsTUN{
		dev:       dev,
		name:      name,
		clientIP:  cleanIP,
		turnIP:    turnIP,
		peerIP:    peerHost,
		bypassRu:  bypassRu,
		bypassLan: bypassLan,
	}

	// 1. Configure IP address on the adapter with gateway 10.77.77.1
	gw := "10.77.77.1"
	cmd := exec.Command("netsh", "interface", "ipv4", "set", "address",
		fmt.Sprintf("name=%s", name),
		"source=static",
		fmt.Sprintf("address=%s", cleanIP),
		"mask=255.255.255.0",
		fmt.Sprintf("gateway=%s", gw),
		"gwmetric=1",
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		logWarn("netsh set address error (%v): %s", err, string(out))
		// PowerShell fallback
		psCmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-Command",
			fmt.Sprintf("New-NetIPAddress -InterfaceAlias '%s' -IPAddress '%s' -PrefixLength 24 -DefaultGateway '%s' -ErrorAction SilentlyContinue; Set-NetIPInterface -InterfaceAlias '%s' -InterfaceMetric 1 -ErrorAction SilentlyContinue", name, cleanIP, gw, name))
		if psOut, psErr := psCmd.CombinedOutput(); psErr != nil {
			logWarn("powershell fallback set address error (%v): %s", psErr, string(psOut))
		} else {
			logInfo("Configured adapter IP via PowerShell fallback on %s", name)
		}
	} else {
		logInfo("Configured adapter IP %s/24 gateway %s on %s", cleanIP, gw, name)
	}

	// 2. Configure DNS on the adapter
	if dns == "" {
		dns = "1.1.1.1, 8.8.8.8"
	}
	primaryDNS := strings.TrimSpace(strings.Split(dns, ",")[0])
	if primaryDNS != "" {
		cmdDNS := exec.Command("netsh", "interface", "ipv4", "set", "dns",
			fmt.Sprintf("name=%s", name),
			"source=static",
			fmt.Sprintf("address=%s", primaryDNS),
			"validate=no",
		)
		if out, err := cmdDNS.CombinedOutput(); err != nil {
			logWarn("netsh set dns error (%v): %s", err, string(out))
			psDNS := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-Command",
				fmt.Sprintf("Set-DnsClientServerAddress -InterfaceAlias '%s' -ServerAddresses '%s' -ErrorAction SilentlyContinue", name, primaryDNS))
			if psDNSOut, psDNSErr := psDNS.CombinedOutput(); psDNSErr != nil {
				logWarn("powershell fallback set dns error (%v): %s", psDNSErr, string(psDNSOut))
			} else {
				logInfo("Configured DNS via PowerShell fallback on %s", name)
			}
		} else {
			logInfo("Configured DNS %s on %s", primaryDNS, name)
		}
	}

	// 3. Find physical default gateway to prevent route loop to TURN server and Peer
	physGW := findDefaultGateway()
	w.defGateway = physGW
	if physGW != "" {
		if turnIP != "" {
			cmdHost := exec.Command("route", "add", turnIP, "mask", "255.255.255.255", physGW, "metric", "1")
			if out, err := cmdHost.CombinedOutput(); err != nil {
				logWarn("route add turnIP error (%v): %s", err, string(out))
			} else {
				logInfo("Pinned host route for TURN server %s via physical gateway %s", turnIP, physGW)
			}
		}
		if peerHost != "" && peerHost != turnIP {
			if ip := net.ParseIP(peerHost); ip != nil {
				cmdPeer := exec.Command("route", "add", peerHost, "mask", "255.255.255.255", physGW, "metric", "1")
				if out, err := cmdPeer.CombinedOutput(); err != nil {
					logWarn("route add peerIP error (%v): %s", err, string(out))
				} else {
					logInfo("Pinned host route for Peer server %s via physical gateway %s", peerHost, physGW)
				}
			}
		}
	}

	// 4. Override default routes with 0.0.0.0/1 and 128.0.0.0/1 pointing to TUN gateway
	cmdR1 := exec.Command("route", "add", "0.0.0.0", "mask", "128.0.0.0", gw, "metric", "1")
	if out, err := cmdR1.CombinedOutput(); err != nil {
		logWarn("route add 0.0.0.0/1 error (%v): %s", err, string(out))
	}

	cmdR2 := exec.Command("route", "add", "128.0.0.0", "mask", "128.0.0.0", gw, "metric", "1")
	if out, err := cmdR2.CombinedOutput(); err != nil {
		logWarn("route add 128.0.0.0/1 error (%v): %s", err, string(out))
	}

	logInfo("Windows Wintun routing enabled successfully")
	return w, dev, nil
}

func (w *WindowsTUN) Close() error {
	if w == nil {
		return nil
	}

	// Remove default override routes
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0").Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0").Run()

	// Remove host routes
	if w.turnIP != "" {
		exec.Command("route", "delete", w.turnIP).Run()
	}
	if w.peerIP != "" {
		exec.Command("route", "delete", w.peerIP).Run()
	}

	logInfo("Cleaned up Windows routes for adapter %s", w.name)
	if w.dev != nil {
		return w.dev.Close()
	}
	return nil
}

func findDefaultGateway() string {
	cmd := exec.Command("route", "print", "0.0.0.0")
	out, err := cmd.Output()
	if err != nil {
		return ""
	}

	lines := strings.Split(string(out), "\n")
	for _, line := range lines {
		fields := strings.Fields(line)
		if len(fields) >= 3 && fields[0] == "0.0.0.0" && fields[1] == "0.0.0.0" {
			gw := fields[2]
			if ip := net.ParseIP(gw); ip != nil && !ip.IsUnspecified() && !strings.HasPrefix(gw, "10.77.77.") {
				return gw
			}
		}
	}
	return ""
}
