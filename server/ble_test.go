package main

import (
	"bytes"
	"context"
	"sync"
	"testing"
	"time"

	"github.com/go-ble/ble"
)

// Exercises the real GATT handlers with go-ble's request/response types; no radio needed.

type fakeBLEConn struct {
	ctx  context.Context
	done chan struct{}
}

func newFakeBLEConn() *fakeBLEConn {
	return &fakeBLEConn{ctx: context.Background(), done: make(chan struct{})}
}

func (c *fakeBLEConn) Read([]byte) (int, error)       { return 0, nil }
func (c *fakeBLEConn) Write(b []byte) (int, error)    { return len(b), nil }
func (c *fakeBLEConn) Close() error                   { return nil }
func (c *fakeBLEConn) Context() context.Context       { return c.ctx }
func (c *fakeBLEConn) SetContext(ctx context.Context) { c.ctx = ctx }
func (c *fakeBLEConn) LocalAddr() ble.Addr            { return ble.NewAddr("00:00:00:00:00:01") }
func (c *fakeBLEConn) RemoteAddr() ble.Addr           { return ble.NewAddr("00:00:00:00:00:02") }
func (c *fakeBLEConn) RxMTU() int                     { return ble.DefaultMTU }
func (c *fakeBLEConn) SetRxMTU(int)                   {}
func (c *fakeBLEConn) TxMTU() int                     { return ble.DefaultMTU }
func (c *fakeBLEConn) SetTxMTU(int)                   {}
func (c *fakeBLEConn) ReadRSSI() int                  { return 0 }
func (c *fakeBLEConn) Disconnected() <-chan struct{}  { return c.done }

type recordingLines struct {
	mu     sync.Mutex
	garage []bool
	relay  []bool
}

func (r *recordingLines) SetGarage(a bool) error {
	r.mu.Lock()
	r.garage = append(r.garage, a)
	r.mu.Unlock()
	return nil
}
func (r *recordingLines) SetRelay(a bool) error {
	r.mu.Lock()
	r.relay = append(r.relay, a)
	r.mu.Unlock()
	return nil
}
func (r *recordingLines) Close() error { return nil }

func newTestBLE(t *testing.T) (*bleServer, *recordingLines) {
	auth, _ := newTestAuth(t)
	lines := &recordingLines{}
	return &bleServer{auth: auth, ctrl: NewController(lines)}, lines
}

func gattRead(s *bleServer, h func(ble.Request, ble.ResponseWriter), c ble.Conn) ([]byte, ble.ATTError) {
	// The ATT server sizes this buffer to the MTU (ATT_MTU 23 -> 22 bytes of value).
	buf := bytes.NewBuffer(make([]byte, 0, ble.DefaultMTU-1))
	rsp := ble.NewResponseWriter(buf)
	h(ble.NewRequest(c, nil, 0), rsp)
	return buf.Bytes(), rsp.Status()
}

func gattWrite(s *bleServer, c ble.Conn, data []byte) ble.ATTError {
	rsp := ble.NewResponseWriter(nil)
	s.writeCommand(ble.NewRequest(c, data, 0), rsp)
	return rsp.Status()
}

func TestBLEFlow(t *testing.T) {
	s, lines := newTestBLE(t)
	c := newFakeBLEConn()

	ch, st := gattRead(s, s.readChallenge, c)
	if st != ble.ErrSuccess || len(ch) != challengeLen {
		t.Fatalf("challenge read: status %v len %d", st, len(ch))
	}
	var chArr [challengeLen]byte
	copy(chArr[:], ch)
	msg := signed(s.auth, chArr, cmdSetRelay, 1)

	if st := gattWrite(s, c, msg); st != ble.ErrSuccess {
		t.Fatalf("valid relay-on rejected: %v", st)
	}
	if state, _ := gattRead(s, s.readState, c); !bytes.Equal(state, []byte{1, 0}) {
		t.Fatalf("state = %v, want [1 0]", state)
	}
	if len(lines.relay) != 1 || !lines.relay[0] {
		t.Fatalf("relay line not driven: %v", lines.relay)
	}

	// Replay of the exact same write.
	if st := gattWrite(s, c, msg); st != attErrAuth {
		t.Fatalf("replay: status %v, want 0x80", st)
	}
	// Wrong length.
	if st := gattWrite(s, c, msg[:5]); st != ble.ErrInvalAttrValueLen {
		t.Fatalf("short write: status %v", st)
	}
	if len(lines.relay) != 1 {
		t.Fatalf("rejected writes touched GPIO: %v", lines.relay)
	}
}

func TestBLEGarageBusy(t *testing.T) {
	s, lines := newTestBLE(t)
	c := newFakeBLEConn()
	pulse := func() ble.ATTError {
		ch, _ := gattRead(s, s.readChallenge, c)
		var a [challengeLen]byte
		copy(a[:], ch)
		return gattWrite(s, c, signed(s.auth, a, cmdPulseGarage, 0))
	}
	if st := pulse(); st != ble.ErrSuccess {
		t.Fatalf("pulse: %v", st)
	}
	if st := pulse(); st != attErrBusy {
		t.Fatalf("second pulse during first: %v, want 0x81", st)
	}
	time.Sleep(garagePulse + 200*time.Millisecond)
	lines.mu.Lock()
	defer lines.mu.Unlock()
	if len(lines.garage) != 2 || !lines.garage[0] || lines.garage[1] {
		t.Fatalf("garage line sequence %v, want [true false]", lines.garage)
	}
}

func TestBLEDisconnectForgetsChallenge(t *testing.T) {
	s, _ := newTestBLE(t)
	c := newFakeBLEConn()
	gattRead(s, s.readChallenge, c)
	close(c.done)
	deadline := time.Now().Add(time.Second)
	for {
		s.auth.mu.Lock()
		n := len(s.auth.pending)
		s.auth.mu.Unlock()
		if n == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("pending challenge not dropped on disconnect")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
