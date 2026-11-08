package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/go-ble/ble"
)

func TestRingKeepsLastMaxNewestFirst(t *testing.T) {
	r := ring[int]{max: 3}
	if got := r.newestFirst(); got == nil || len(got) != 0 {
		t.Fatalf("empty ring = %#v, want empty non-nil slice", got)
	}
	r.add(1)
	r.add(2)
	if got := r.newestFirst(); !reflect.DeepEqual(got, []int{2, 1}) {
		t.Fatalf("before full: %v", got)
	}
	for i := 3; i <= 1000; i++ {
		r.add(i)
	}
	if got := r.newestFirst(); !reflect.DeepEqual(got, []int{1000, 999, 998}) {
		t.Fatalf("after 1000 adds: %v, want [1000 999 998]", got)
	}
	if cap(r.entries) > 4 {
		t.Fatalf("backing array grew to %d for max 3", cap(r.entries))
	}
}

func TestRingNewestFirstIsACopy(t *testing.T) {
	r := ring[int]{max: 2}
	r.add(1)
	r.newestFirst()[0] = 99
	if r.entries[0] != 1 {
		t.Fatal("caller modified the stored entries")
	}
}

func TestRingZeroKeepsNothing(t *testing.T) {
	r := ring[int]{max: 0}
	r.add(1)
	if len(r.newestFirst()) != 0 {
		t.Fatalf("max 0 kept %v", r.entries)
	}
}

func TestHistoryBuffersAreSeparate(t *testing.T) {
	h := NewHistory(2, 1)
	at := time.Date(2026, 11, 8, 14, 3, 12, 987654321, time.FixedZone("CET", 3600))
	h.now = func() time.Time { return at }

	h.AddValid(sourceWeb, "192.0.2.7")
	for range 5 { // A flood of rejected writes must not push out the pulse.
		h.AddRejected(reasonBadTag, sourceBLE, "")
	}
	h.AddRejected(reasonBusy, sourceWeb, "192.0.2.8")

	utc := time.Date(2026, 11, 8, 13, 3, 12, 0, time.UTC) // UTC, to the second.
	want := HistorySnapshot{
		ValidLimit:    2,
		Valid:         []GarageAction{{Time: utc, Source: sourceWeb, Client: "192.0.2.7"}},
		RejectedLimit: 1,
		Rejected:      []RejectedAction{{Time: utc, Reason: reasonBusy, Source: sourceWeb, Client: "192.0.2.8"}},
	}
	if got := h.Snapshot(); !reflect.DeepEqual(got, want) {
		t.Fatalf("snapshot = %+v, want %+v", got, want)
	}
}

func TestAuthRejectReason(t *testing.T) {
	for err, want := range map[error]string{
		errBadTag:                   "bad-tag",
		errExpired:                  "expired",
		errNoChallenge:              "no-challenge",
		errBadLength:                "bad-length",
		errUnknownCommand:           "unknown-command",
		errors.New("anything else"): "other",
	} {
		if got := authRejectReason(err); got != want {
			t.Errorf("authRejectReason(%v) = %q, want %q", err, got, want)
		}
	}
}

// setHistoryEnv sets both size variables for one test; "unset" removes one.
func setHistoryEnv(t *testing.T, valid, rejected string) {
	t.Helper()
	for name, v := range map[string]string{validHistoryEnv: valid, rejectedHistoryEnv: rejected} {
		t.Setenv(name, v) // Registers restoring the original value.
		if v == "unset" {
			os.Unsetenv(name)
		}
	}
}

func TestLoadHistorySizes(t *testing.T) {
	tests := []struct {
		valid, rejected     string
		wantValid, wantRejd int
	}{
		{"unset", "unset", 20, 20},
		{"5", "unset", 5, 20},
		{"unset", "100", 20, 100},
		{"0", "0", 0, 0},
	}
	for _, tt := range tests {
		t.Run(tt.valid+"/"+tt.rejected, func(t *testing.T) {
			setHistoryEnv(t, tt.valid, tt.rejected)
			v, r, err := loadHistorySizes()
			if err != nil {
				t.Fatal(err)
			}
			if v != tt.wantValid || r != tt.wantRejd {
				t.Fatalf("got %d/%d, want %d/%d", v, r, tt.wantValid, tt.wantRejd)
			}
		})
	}
}

func TestLoadHistorySizesRejectsInvalid(t *testing.T) {
	for _, v := range []string{"", "-1", "twenty", "2.5"} {
		for _, which := range []string{"valid", "rejected"} {
			t.Run(which+"="+v, func(t *testing.T) {
				if which == "valid" {
					setHistoryEnv(t, v, "unset")
				} else {
					setHistoryEnv(t, "unset", v)
				}
				if a, b, err := loadHistorySizes(); err == nil {
					t.Fatalf("got %d/%d, want error", a, b)
				}
			})
		}
	}
}

type failingLines struct{ recordingLines }

func (*failingLines) SetGarage(bool) error { return errors.New("gpio broken") }

func TestControllerRecordsValidAndRejectedPulses(t *testing.T) {
	h := NewHistory(5, 5)
	c := NewController(&recordingLines{}, h)
	if err := c.PulseGarage(sourceWeb, "192.0.2.7"); err != nil {
		t.Fatal(err)
	}
	if err := c.PulseGarage(sourceBLE, ""); !errors.Is(err, errBusy) {
		t.Fatalf("second pulse: %v, want errBusy", err)
	}
	if err := c.SetRelay(true); err != nil {
		t.Fatal(err)
	}
	s := h.Snapshot()
	if len(s.Valid) != 1 || s.Valid[0].Source != sourceWeb || s.Valid[0].Client != "192.0.2.7" {
		t.Fatalf("valid = %+v, want only the first pulse", s.Valid)
	}
	if len(s.Rejected) != 1 || s.Rejected[0].Reason != reasonBusy || s.Rejected[0].Source != sourceBLE {
		t.Fatalf("rejected = %+v, want the busy BLE pulse", s.Rejected)
	}

	broken := NewHistory(5, 5)
	if err := NewController(&failingLines{}, broken).PulseGarage(sourceWeb, "192.0.2.9"); err == nil {
		t.Fatal("pulse with broken GPIO succeeded")
	}
	s = broken.Snapshot()
	want := []RejectedAction{{Time: s.Rejected[0].Time, Reason: reasonGPIO, Source: sourceWeb, Client: "192.0.2.9"}}
	if len(s.Valid) != 0 || !reflect.DeepEqual(s.Rejected, want) {
		t.Fatalf("failed pulse: %+v, want only a gpio-error rejection", s)
	}
}

func TestBLERecordsRejectedAndValid(t *testing.T) {
	s, _ := newTestBLE(t)
	c := newFakeBLEConn()
	challenge := func() [challengeLen]byte {
		ch, _ := gattRead(s, s.readChallenge, c)
		var a [challengeLen]byte
		copy(a[:], ch)
		return a
	}

	forged := signed(s.auth, challenge(), cmdPulseGarage, 0)
	forged[len(forged)-1] ^= 1
	if st := gattWrite(s, c, forged); st != attErrAuth {
		t.Fatalf("forged pulse: %v, want 0x80", st)
	}
	if st := gattWrite(s, c, signed(s.auth, challenge(), cmdPulseGarage, 0)); st != ble.ErrSuccess {
		t.Fatalf("valid pulse: %v", st)
	}
	if st := gattWrite(s, c, signed(s.auth, challenge(), cmdPulseGarage, 0)); st != attErrBusy {
		t.Fatalf("pulse while busy: %v, want 0x81", st)
	}
	gattWrite(s, c, []byte{cmdPulseGarage}) // Wrong length.

	snap := s.history.Snapshot()
	if len(snap.Valid) != 1 || snap.Valid[0].Source != sourceBLE || snap.Valid[0].Client != "" {
		t.Fatalf("valid = %+v, want one BLE pulse", snap.Valid)
	}
	var reasons []string
	for _, r := range snap.Rejected {
		if r.Source != sourceBLE || r.Client != "" {
			t.Fatalf("rejected %+v, want source ble and no client", r)
		}
		reasons = append(reasons, r.Reason)
	}
	if want := []string{reasonBadLength, reasonBusy, reasonBadTag}; !reflect.DeepEqual(reasons, want) {
		t.Fatalf("rejected reasons = %v, want %v", reasons, want)
	}
}

func TestHistoryAPI(t *testing.T) {
	h := NewHistory(defaultHistorySize, 7)
	mux := (&webServer{ctrl: NewController(&recordingLines{}, h), history: h}).routes()

	get := func() HistorySnapshot {
		t.Helper()
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/history", nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("GET /api/history: %d", rec.Code)
		}
		var out HistorySnapshot
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil || out.Valid == nil || out.Rejected == nil {
			t.Fatalf("body %q: %v", rec.Body.String(), err)
		}
		return out
	}

	if got := get(); got.ValidLimit != 20 || got.RejectedLimit != 7 || len(got.Valid) != 0 || len(got.Rejected) != 0 {
		t.Fatalf("fresh history = %+v", got)
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/garage/pulse", nil)) // RemoteAddr 192.0.2.1:1234
	if rec.Code != http.StatusOK {
		t.Fatalf("pulse: %d %s", rec.Code, rec.Body)
	}
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/garage/pulse", nil))
	if rec.Code != http.StatusConflict {
		t.Fatalf("pulse while busy: %d, want 409", rec.Code)
	}
	snap := get()
	if r := snap.Rejected; len(r) != 1 || r[0].Reason != reasonBusy || r[0].Source != sourceWeb || r[0].Client != "192.0.2.1" {
		t.Fatalf("rejected = %+v, want one busy web pulse from 192.0.2.1", r)
	}
	got := snap.Valid
	if len(got) != 1 || got[0].Source != sourceWeb || got[0].Client != "192.0.2.1" {
		t.Fatalf("valid = %+v, want one web pulse from 192.0.2.1", got)
	}
	if got[0].Time.Location() != time.UTC || time.Since(got[0].Time) > time.Minute {
		t.Fatalf("time %v is not a recent UTC time", got[0].Time)
	}
}
