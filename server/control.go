package main

import (
	"errors"
	"log"
	"sync"
	"time"
)

const garagePulse = 500 * time.Millisecond

var errBusy = errors.New("garage pulse already in progress")

// Lines drives the two outputs. true = relay energized.
type Lines interface {
	SetGarage(active bool) error
	SetRelay(active bool) error
	Close() error
}

// Controller is the single owner of the outputs; BLE and HTTP both go through it.
type Controller struct {
	mu         sync.Mutex
	lines      Lines
	relayOn    bool
	garageBusy bool
	closed     bool
	watchers   map[chan struct{}]struct{}
}

func NewController(l Lines) *Controller { return &Controller{lines: l} }

type State struct {
	Relay      bool `json:"relay"`
	GarageBusy bool `json:"garageBusy"`
}

func (c *Controller) State() State {
	c.mu.Lock()
	defer c.mu.Unlock()
	return State{Relay: c.relayOn, GarageBusy: c.garageBusy}
}

// Watch returns a channel that is signalled after every state change, and a
// function that stops watching. Signals coalesce, so read State() on each one.
func (c *Controller) Watch() (<-chan struct{}, func()) {
	ch := make(chan struct{}, 1)
	c.mu.Lock()
	if c.watchers == nil {
		c.watchers = map[chan struct{}]struct{}{}
	}
	c.watchers[ch] = struct{}{}
	c.mu.Unlock()
	return ch, func() {
		c.mu.Lock()
		delete(c.watchers, ch)
		c.mu.Unlock()
	}
}

// changedLocked signals every watcher without blocking. c.mu must be held.
func (c *Controller) changedLocked() {
	for ch := range c.watchers {
		select {
		case ch <- struct{}{}:
		default: // A signal is already pending.
		}
	}
}

// PulseGarage energizes the garage relay for garagePulse, then releases it.
func (c *Controller) PulseGarage() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return errors.New("controller closed")
	}
	if c.garageBusy {
		return errBusy
	}
	if err := c.lines.SetGarage(true); err != nil {
		_ = c.lines.SetGarage(false)
		return err
	}
	c.garageBusy = true
	c.changedLocked()
	log.Printf("garage: pulse start")
	time.AfterFunc(garagePulse, c.endPulse)
	return nil
}

func (c *Controller) endPulse() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	// Never leave the garage relay energized: retry a few times on error.
	for i := 0; i < 10; i++ {
		err := c.lines.SetGarage(false)
		if err == nil {
			break
		}
		log.Printf("garage: release failed (attempt %d): %v", i+1, err)
		time.Sleep(100 * time.Millisecond)
	}
	c.garageBusy = false
	c.changedLocked()
	log.Printf("garage: pulse end")
}

func (c *Controller) SetRelay(on bool) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return errors.New("controller closed")
	}
	if err := c.lines.SetRelay(on); err != nil {
		return err
	}
	if c.relayOn != on {
		c.relayOn = on
		c.changedLocked()
	}
	log.Printf("relay: %s", onOff(on))
	return nil
}

// Close de-energizes both outputs and releases the GPIO lines.
func (c *Controller) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	_ = c.lines.SetGarage(false)
	_ = c.lines.SetRelay(false)
	return c.lines.Close()
}

func onOff(b bool) string {
	if b {
		return "on"
	}
	return "off"
}
