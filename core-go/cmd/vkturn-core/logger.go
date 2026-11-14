package main

import (
	"fmt"
	"io"
	"log"
	"os"
	"sync"
)

type LogRingBuffer struct {
	mu    sync.RWMutex
	lines []string
	max   int
}

var globalLogs = &LogRingBuffer{
	lines: make([]string, 0, 1000),
	max:   1000,
}

func (b *LogRingBuffer) Write(p []byte) (n int, err error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	line := string(p)
	if len(b.lines) >= b.max {
		b.lines = b.lines[1:]
	}
	b.lines = append(b.lines, line)
	return len(p), nil
}

func (b *LogRingBuffer) GetLines() []string {
	b.mu.RLock()
	defer b.mu.RUnlock()
	res := make([]string, len(b.lines))
	copy(res, b.lines)
	return res
}

type multiWriter struct {
	writers []io.Writer
}

func (t *multiWriter) Write(p []byte) (n int, err error) {
	for _, w := range t.writers {
		if rw, ok := w.(*LogRingBuffer); ok {
			rw.Write(p)
		} else {
			w.Write(p)
		}
	}
	return len(p), nil
}

func initLogger() {
	mw := &multiWriter{writers: []io.Writer{os.Stdout, globalLogs}}
	log.SetOutput(mw)
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
}

func logInfo(format string, v ...any) {
	msg := fmt.Sprintf(format, v...)
	log.Printf("[INFO] %s", msg)
}

func logError(format string, v ...any) {
	msg := fmt.Sprintf(format, v...)
	log.Printf("[ERROR] %s", msg)
}

func logWarn(format string, v ...any) {
	msg := fmt.Sprintf(format, v...)
	log.Printf("[WARN] %s", msg)
}
