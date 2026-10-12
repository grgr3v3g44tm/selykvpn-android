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
	dev         tun.Device
	name        string
	clientIP    string
	turnIP      string
	defGateway  string
	bypassRu    bool
	bypassLan   bool
}

func createPlatformTUN(name string, mtu int, clientIP string, dns string, turnIP string, bypassRu bool, bypassLan bool) (*WindowsTUN, tun.Device, error) {
	if mtu <= 0 {
		mtu = 1420
	}
	dev, err := tun.CreateTUN(name, mtu)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to create Wintun adapter: %w", err)
	}

	w := &WindowsTUN{
		dev:       dev,
		name:      name,
		clientIP:  clientIP,
		turnIP:    turnIP,
		bypassRu:  bypassRu,
		bypassLan: bypassLan,
	}

	// 1. Configure IP address on the adapter
	cleanIP := clientIP
	if idx := strings.Index(cleanIP, "/"); idx != -1 {
		cleanIP = cleanIP[:idx]
	}

	cmd := exec.Command("netsh", "interface", "ipv4", "set", "address",
		fmt.Sprintf("name=\"%s\"", name),
		"source=static",
		fmt.Sprintf("address=%s", cleanIP),
		"mask=255.255.255.255",
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		logWarn("netsh set address error (%v): %s", err, string(out))
	} else {
		logInfo("Configured adapter IP %s on %s", cleanIP, name)
	}

	// 2. Configure DNS on the adapter if provided
	if dns != "" {
		primaryDNS := strings.Split(dns, ",")[0]
		primaryDNS = strings.TrimSpace(primaryDNS)
		if primaryDNS != "" {
			cmdDNS := exec.Command("netsh", "interface", "ipv4", "set", "dns",
				fmt.Sprintf("name=\"%s\"", name),
				"static",
				primaryDNS,
			)
			if out, err := cmdDNS.CombinedOutput(); err != nil {
				logWarn("netsh set dns error (%v): %s", err, string(out))
			} else {
				logInfo("Configured DNS %s on %s", primaryDNS, name)
			}
		}
	}

	// 3. Find default gateway to prevent route loop to TURN server
	gw := findDefaultGateway()
	w.defGateway = gw
	if gw != "" && turnIP != "" {
		cmdHost := exec.Command("route", "add", turnIP, "mask", "255.255.255.255", gw, "metric", "1")
		if out, err := cmdHost.CombinedOutput(); err != nil {
			logWarn("route add turnIP error (%v): %s", err, string(out))
		} else {
			logInfo("Added host route for TURN server %s via gateway %s", turnIP, gw)
		}
	}

	// 4. Override default routes with 0.0.0.0/1 and 128.0.0.0/1 pointing to adapter IP
	cmdR1 := exec.Command("route", "add", "0.0.0.0", "mask", "128.0.0.0", cleanIP, "metric", "1")
	if out, err := cmdR1.CombinedOutput(); err != nil {
		logWarn("route add 0.0.0.0/1 error (%v): %s", err, string(out))
	}

	cmdR2 := exec.Command("route", "add", "128.0.0.0", "mask", "128.0.0.0", cleanIP, "metric", "1")
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

	cleanIP := w.clientIP
	if idx := strings.Index(cleanIP, "/"); idx != -1 {
		cleanIP = cleanIP[:idx]
	}

	// Remove default override routes
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0").Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0").Run()

	// Remove host route
	if w.turnIP != "" {
		exec.Command("route", "delete", w.turnIP).Run()
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
			if ip := net.ParseIP(gw); ip != nil && !ip.IsUnspecified() {
				return gw
			}
		}
	}
	return ""
}
