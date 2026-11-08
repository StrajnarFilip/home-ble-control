// Command garage-ctl drives a garage-door relay (pulse, GPIO17 by default) and
// a second relay (on/off, GPIO27 by default) on a Raspberry Pi, via a web UI /
// REST API and via authenticated BLE for the Android app. It is configured with
// the AES key and, optionally, the $GARAGE_GPIO and $RELAY_GPIO pin numbers and
// the $GPIO_ACTIVE_LOW relay polarity (active-low by default). Until it
// restarts, it remembers the last $VALID_HISTORY garage pulses and the last
// $REJECTED_HISTORY rejected or failed ones (20 each by default).
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	keyPath := flag.String("key", defaultKeyPath, "path to the hex AES key file (overrides $"+keyEnv+")")
	listen := flag.String("listen", ":8080", "HTTP listen address")
	mockGPIO := flag.Bool("mock-gpio", false, "log GPIO changes instead of driving pins (development)")
	noBLE := flag.Bool("no-ble", false, "disable Bluetooth (development)")
	flag.Parse()
	explicitKey := false
	flag.Visit(func(f *flag.Flag) {
		if f.Name == "key" {
			explicitKey = true
		}
	})

	key, source, err := loadKey(*keyPath, explicitKey)
	if err != nil {
		log.Fatalf("key: %v", err)
	}
	log.Printf("key: AES-%d from %s", len(key)*8, source)

	auth, err := NewAuthenticator(key)
	if err != nil {
		log.Fatalf("key: %v", err)
	}

	pins, err := loadPins()
	if err != nil {
		log.Fatalf("gpio: %v", err)
	}

	activeLow, err := loadActiveLow()
	if err != nil {
		log.Fatalf("gpio: %v", err)
	}

	var lines Lines = mockLines{pins, activeLow}
	if !*mockGPIO {
		if lines, err = openGPIO(pins, activeLow); err != nil {
			log.Fatalf("gpio: %v", err)
		}
	}
	validSize, rejectedSize, err := loadHistorySizes()
	if err != nil {
		log.Fatalf("history: %v", err)
	}
	log.Printf("history: keeping the last %d garage pulses and %d rejected or failed ones in memory", validSize, rejectedSize)
	history := NewHistory(validSize, rejectedSize)

	ctrl := NewController(lines, history)
	defer ctrl.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	web := &webServer{ctrl: ctrl, history: history, key: key, pins: pins}

	// BLE setup can block for a while if the adapter is busy, so do it in the
	// background; the web UI works regardless.
	bleDone := make(chan *bleServer, 1)
	if *noBLE {
		bleDone <- nil
	} else {
		go func() {
			s, addr, err := startBLE(ctx, auth, ctrl, history)
			if err != nil {
				log.Printf("ble: disabled: %v", err)
			} else {
				web.setBLEAddr(addr)
			}
			bleDone <- s
		}()
	}

	srv := &http.Server{Addr: *listen, Handler: web.routes(), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		log.Printf("http: listening on %s", *listen)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("http: %v", err)
			stop()
		}
	}()

	<-ctx.Done()
	log.Printf("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	srv.Shutdown(shutdownCtx)
	select {
	case s := <-bleDone:
		if s != nil {
			s.Stop()
		}
	case <-shutdownCtx.Done():
	}
}
