package main

import (
	"os"
	"sync"

	"golang.zx2c4.com/wireguard/tun"
)

type androidTUN struct {
	file   *os.File
	events chan tun.Event
	mtu    int
	closed sync.Once
}

func createAndroidTUN(file *os.File, mtu int) tun.Device {
	if mtu <= 0 {
		mtu = 1420
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
