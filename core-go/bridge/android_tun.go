package main

import (
	"fmt"
	"log"
	"os"
	"sync"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/tun"
)

// openAndroidTUN creates a high-performance tun.Device from a duplicated TUN descriptor.
// It prioritizes wireguard-go's native unmonitored TUN (CreateUnmonitoredTUNFromFD) which
// registers the descriptor with Go's epoll netpoller, activates Linux TCP/UDP GRO offloads,
// and supports conn.IdealBatchSize (multi-packet batching).
// If native creation fails (e.g. on restricted kernel environments), it seamlessly falls
// back to an optimized non-blocking androidTUN.
func openAndroidTUN(fd int, mtu int) (tun.Device, error) {
	if mtu <= 0 {
		mtu = 1360
	}

	// 1. Ensure descriptor is non-blocking so Go's runtime poller (epoll) manages it.
	if err := unix.SetNonblock(fd, true); err != nil {
		log.Printf("openAndroidTUN: SetNonblock failed: %v", err)
	}

	// 2. Attempt native unmonitored TUN with epoll & GRO batching
	dev, name, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err == nil {
		log.Printf("openAndroidTUN: opened native unmonitored TUN on %q (batchSize=%d, mtu=%d)", name, dev.BatchSize(), mtu)
		return dev, nil
	}
	log.Printf("openAndroidTUN: CreateUnmonitoredTUNFromFD fallback (%v) -> using androidTUN", err)

	// 3. Fallback to androidTUN
	tunFile := os.NewFile(uintptr(fd), "/dev/tun")
	if tunFile == nil {
		return nil, fmt.Errorf("openAndroidTUN: os.NewFile failed for fd %d", fd)
	}
	return createAndroidTUN(tunFile, mtu), nil
}

type androidTUN struct {
	file   *os.File
	events chan tun.Event
	mtu    int
	closed sync.Once
}

func createAndroidTUN(file *os.File, mtu int) tun.Device {
	if mtu <= 0 {
		mtu = 1360
	}
	t := &androidTUN{
		file:   file,
		events: make(chan tun.Event, 2),
		mtu:    mtu,
	}
	t.events <- tun.EventUp
	return t
}

func (t *androidTUN) File() *os.File {
	return t.file
}

func (t *androidTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 || len(sizes) == 0 {
		return 0, nil
	}
	n, err := t.file.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

func (t *androidTUN) Write(bufs [][]byte, offset int) (int, error) {
	total := 0
	for _, buf := range bufs {
		packet := buf[offset:]
		if len(packet) == 0 {
			continue
		}
		n, err := t.file.Write(packet)
		if err != nil {
			return total, err
		}
		total += n
	}
	return total, nil
}

func (t *androidTUN) MTU() (int, error) {
	return t.mtu, nil
}

func (t *androidTUN) Name() (string, error) {
	return "tun0", nil
}

func (t *androidTUN) Events() <-chan tun.Event {
	return t.events
}

func (t *androidTUN) Close() error {
	var err error
	t.closed.Do(func() {
		close(t.events)
		err = t.file.Close()
	})
	return err
}

func (t *androidTUN) BatchSize() int {
	return 1
}
