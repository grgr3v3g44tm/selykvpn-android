package main

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/cacggghp/vk-turn-proxy/pkg/speedtest"
)

type ApiServer struct {
	addr  string
	token string
}

func newApiServer(addr, token string) *ApiServer {
	return &ApiServer{
		addr:  addr,
		token: token,
	}
}

func (s *ApiServer) Start() error {
	mux := http.NewServeMux()

	mux.HandleFunc("/api/status", s.handleStatus)
	mux.HandleFunc("/api/start", s.handleStart)
	mux.HandleFunc("/api/stop", s.handleStop)
	mux.HandleFunc("/api/logs", s.handleLogs)
	mux.HandleFunc("/api/speedtest", s.handleSpeedtest)
	mux.HandleFunc("/api/version", s.handleVersion)

	handler := s.corsMiddleware(mux)
	logInfo("REST API listening on http://%s", s.addr)
	return http.ListenAndServe(s.addr, handler)
}

func (s *ApiServer) corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}

		if s.token != "" {
			auth := r.Header.Get("Authorization")
			if !strings.HasSuffix(auth, s.token) {
				http.Error(w, "Unauthorized", http.StatusUnauthorized)
				return
			}
		}

		next.ServeHTTP(w, r)
	})
}

func (s *ApiServer) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	st := globalRunner.GetStatus()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(st)
}

func (s *ApiServer) handleStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	var req StartRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad Request: "+err.Error(), http.StatusBadRequest)
		return
	}

	if err := globalRunner.Start(req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "status": "connecting"})
}

func (s *ApiServer) handleStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	globalRunner.Stop()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "status": "disconnected"})
}

func (s *ApiServer) handleLogs(w http.ResponseWriter, r *http.Request) {
	lines := globalLogs.GetLines()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"lines": lines,
	})
}

func (s *ApiServer) handleSpeedtest(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		snap := speedtest.Snapshot()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(snap)
		return
	}
	if r.Method == http.MethodPost {
		go func() {
			_ = speedtest.Start(speedtest.Config{
				DurationSec: 5,
				Threads:     4,
				Direction:   "both",
			})
		}()

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"ok": true, "status": "running"})
		return
	}
	http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
}

func (s *ApiServer) handleVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"version": "1.4.42",
		"service": "vkturn-core",
		"target":  "windows",
	})
}
