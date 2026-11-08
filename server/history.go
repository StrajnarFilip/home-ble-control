package main

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"sync"
	"time"
)

const (
	validHistoryEnv    = "VALID_HISTORY"
	rejectedHistoryEnv = "REJECTED_HISTORY"
	defaultHistorySize = 20
)

// Sources of a garage pulse.
const (
	sourceBLE = "ble"
	sourceWeb = "web"
)

// GarageAction is one garage pulse that actually energized the relay.
type GarageAction struct {
	Time   time.Time `json:"time"`             // UTC, to the second
	Source string    `json:"source"`           // sourceBLE or sourceWeb
	Client string    `json:"client,omitempty"` // web only: the client's IP address
}

// RejectedAction is one garage pulse request that didn't pulse: a BLE command
// write that failed authentication or validation, or a pulse from either source
// that was refused because one was running or failed at the GPIO. Nothing in a
// write that fails authentication can be trusted, including the command it
// claims to be, so those are all recorded, whatever command they claim.
type RejectedAction struct {
	Time   time.Time `json:"time"`             // UTC, to the second
	Reason string    `json:"reason"`           // one of the reason* constants
	Source string    `json:"source"`           // sourceBLE or sourceWeb
	Client string    `json:"client,omitempty"` // web only: the client's IP address
}

// Reasons in RejectedAction.
const (
	reasonBadTag         = "bad-tag"
	reasonExpired        = "expired"
	reasonNoChallenge    = "no-challenge"
	reasonBadLength      = "bad-length"
	reasonUnknownCommand = "unknown-command"
	reasonBusy           = "busy"
	reasonGPIO           = "gpio-error"
	reasonOther          = "other"
)

// ring keeps the last max entries, oldest first, and never holds more however
// long the server runs. max 0 keeps nothing.
type ring[T any] struct {
	max     int
	entries []T
}

func (r *ring[T]) add(v T) {
	if r.max <= 0 {
		return
	}
	if len(r.entries) >= r.max {
		n := copy(r.entries, r.entries[len(r.entries)-r.max+1:])
		r.entries = r.entries[:n]
	}
	r.entries = append(r.entries, v)
}

// newestFirst returns a copy of the entries, most recent first. It is never
// nil, so an empty list encodes as [] rather than null.
func (r *ring[T]) newestFirst() []T {
	out := make([]T, len(r.entries))
	for i, v := range r.entries {
		out[len(out)-1-i] = v
	}
	return out
}

// History records valid and rejected garage pulses in two separate buffers, so
// a flood of rejected writes can't push out real pulses.
// It lives in memory only: a restart clears it on purpose.
type History struct {
	mu       sync.Mutex
	valid    ring[GarageAction]
	rejected ring[RejectedAction]
	now      func() time.Time
}

func NewHistory(validSize, rejectedSize int) *History {
	return &History{valid: ring[GarageAction]{max: validSize}, rejected: ring[RejectedAction]{max: rejectedSize}, now: time.Now}
}

func (h *History) stamp() time.Time { return h.now().UTC().Truncate(time.Second) }

func (h *History) AddValid(source, client string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.valid.add(GarageAction{Time: h.stamp(), Source: source, Client: client})
}

func (h *History) AddRejected(reason, source, client string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.rejected.add(RejectedAction{Time: h.stamp(), Reason: reason, Source: source, Client: client})
}

// HistorySnapshot is what GET /api/history returns. The limits let the web UI
// tell an empty list from one that is turned off.
type HistorySnapshot struct {
	ValidLimit    int              `json:"validLimit"`
	Valid         []GarageAction   `json:"valid"`
	RejectedLimit int              `json:"rejectedLimit"`
	Rejected      []RejectedAction `json:"rejected"`
}

func (h *History) Snapshot() HistorySnapshot {
	h.mu.Lock()
	defer h.mu.Unlock()
	return HistorySnapshot{
		ValidLimit:    h.valid.max,
		Valid:         h.valid.newestFirst(),
		RejectedLimit: h.rejected.max,
		Rejected:      h.rejected.newestFirst(),
	}
}

// authRejectReason turns an auth.Verify error into a reason for the API.
func authRejectReason(err error) string {
	switch {
	case errors.Is(err, errBadTag):
		return reasonBadTag
	case errors.Is(err, errExpired):
		return reasonExpired
	case errors.Is(err, errNoChallenge):
		return reasonNoChallenge
	case errors.Is(err, errBadLength):
		return reasonBadLength
	case errors.Is(err, errUnknownCommand):
		return reasonUnknownCommand
	default:
		return reasonOther
	}
}

// loadHistorySizes reads how many valid pulses and rejected commands to keep.
// Like the GPIO settings, an invalid value is an error rather than a silent
// fallback to the default.
func loadHistorySizes() (valid, rejected int, err error) {
	if valid, err = historySizeFromEnv(validHistoryEnv); err != nil {
		return 0, 0, err
	}
	if rejected, err = historySizeFromEnv(rejectedHistoryEnv); err != nil {
		return 0, 0, err
	}
	return valid, rejected, nil
}

func historySizeFromEnv(name string) (int, error) {
	v, ok := os.LookupEnv(name)
	if !ok {
		return defaultHistorySize, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return 0, fmt.Errorf("$%s: %q is not a number of entries (0 or more)", name, v)
	}
	return n, nil
}
