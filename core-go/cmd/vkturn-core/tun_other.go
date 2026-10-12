//go:build !windows

package main

import (
	"errors"
	"golang.zx2c4.com/wireguard/tun"
)

type WindowsTUN struct {
	dev tun.Device
}

func createPlatformTUN(name string, mtu int, clientIP string, dns string, turnIP string, bypassRu bool, bypassLan bool) (*WindowsTUN, tun.Device, error) {
	return nil, nil, errors.New("Wintun is only available on Windows")
}

func (w *WindowsTUN) Close() error {
	if w != nil && w.dev != nil {
		return w.dev.Close()
	}
	return nil
}
