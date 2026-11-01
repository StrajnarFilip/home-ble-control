package main

import (
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"sync"

	"github.com/skip2/go-qrcode"
)

//go:embed web/index.html
var webFS embed.FS

// Pairing is what the QR code carries to the Android app.
type Pairing struct {
	V    int    `json:"v"`
	Key  string `json:"key"`
	Addr string `json:"addr"`
	Name string `json:"name"`
}

type webServer struct {
	ctrl *Controller
	key  []byte

	mu      sync.Mutex
	bleAddr string
}

func (w *webServer) setBLEAddr(a string) {
	w.mu.Lock()
	w.bleAddr = a
	w.mu.Unlock()
}

func (w *webServer) pairing() Pairing {
	w.mu.Lock()
	defer w.mu.Unlock()
	return Pairing{V: 1, Key: hex.EncodeToString(w.key), Addr: w.bleAddr, Name: bleName}
}

func (w *webServer) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(rw http.ResponseWriter, r *http.Request) {
		b, _ := webFS.ReadFile("web/index.html")
		rw.Header().Set("Content-Type", "text/html; charset=utf-8")
		rw.Write(b)
	})
	mux.HandleFunc("GET /api/state", func(rw http.ResponseWriter, r *http.Request) {
		writeJSON(rw, http.StatusOK, w.ctrl.State())
	})
	mux.HandleFunc("POST /api/garage/pulse", func(rw http.ResponseWriter, r *http.Request) {
		if err := w.ctrl.PulseGarage(); err != nil {
			status := http.StatusInternalServerError
			if errors.Is(err, errBusy) {
				status = http.StatusConflict
			}
			writeJSON(rw, status, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(rw, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /api/relay", func(rw http.ResponseWriter, r *http.Request) {
		var body struct {
			On *bool `json:"on"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(rw, r.Body, 1024)).Decode(&body); err != nil || body.On == nil {
			writeJSON(rw, http.StatusBadRequest, map[string]string{"error": `body must be {"on":true|false}`})
			return
		}
		if err := w.ctrl.SetRelay(*body.On); err != nil {
			writeJSON(rw, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(rw, http.StatusOK, map[string]bool{"relay": *body.On})
	})
	mux.HandleFunc("GET /api/pairing", func(rw http.ResponseWriter, r *http.Request) {
		writeJSON(rw, http.StatusOK, w.pairing())
	})
	mux.HandleFunc("GET /api/qr.png", func(rw http.ResponseWriter, r *http.Request) {
		b, _ := json.Marshal(w.pairing())
		png, err := qrcode.Encode(string(b), qrcode.Medium, 360)
		if err != nil {
			http.Error(rw, err.Error(), http.StatusInternalServerError)
			return
		}
		rw.Header().Set("Content-Type", "image/png")
		rw.Header().Set("Cache-Control", "no-store")
		rw.Write(png)
	})
	return mux
}

func writeJSON(rw http.ResponseWriter, status int, v any) {
	rw.Header().Set("Content-Type", "application/json")
	rw.Header().Set("Cache-Control", "no-store")
	rw.WriteHeader(status)
	if err := json.NewEncoder(rw).Encode(v); err != nil {
		log.Printf("http: write response: %v", err)
	}
}
