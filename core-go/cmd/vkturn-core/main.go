package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	initLogger()

	apiPort := flag.Int("api-port", 19823, "Port for local REST API management server")
	apiToken := flag.String("token", "", "Optional authorization token for REST API")
	versionFlag := flag.Bool("v", false, "Print version and exit")
	flag.Parse()

	if *versionFlag {
		fmt.Println("vkturn-core version 1.4.41 (windows/amd64)")
		os.Exit(0)
	}

	logInfo("Starting VK TURN Proxy Core Engine v1.4.41...")
	addr := fmt.Sprintf("127.0.0.1:%d", *apiPort)
	server := newApiServer(addr, *apiToken)

	// Graceful shutdown on signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		sig := <-sigChan
		logInfo("Received signal %v, shutting down tunnel...", sig)
		globalRunner.Stop()
		os.Exit(0)
	}()

	if err := server.Start(); err != nil {
		logError("REST API server crashed: %v", err)
		os.Exit(1)
	}
}
