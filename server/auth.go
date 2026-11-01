package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"sync"
	"time"
)

// BLE command authentication.
//
// Protocol (per BLE connection):
//  1. Client reads the challenge characteristic -> server returns 16 fresh random
//     bytes and remembers them for that connection (30 s, single use).
//  2. Client writes the command characteristic: cmd(1) | arg(1) | tag(16), where
//     tag = AES-CMAC(key, "HBC1" | challenge | cmd | arg).
//  3. Server consumes the pending challenge (any attempt, valid or not, uses it up),
//     checks the tag in constant time and only then executes the command.
//
// A captured command is useless later: its challenge is gone after the first
// attempt, and a new connection always gets a new challenge.

const (
	challengeLen = 16
	tagLen       = 16
	commandLen   = 2 + tagLen
	challengeTTL = 30 * time.Second

	cmdPulseGarage byte = 0x01
	cmdSetRelay    byte = 0x02
)

var macDomain = []byte("HBC1")

var (
	errBadLength      = errors.New("command has wrong length")
	errNoChallenge    = errors.New("no pending challenge (read the challenge first)")
	errExpired        = errors.New("challenge expired")
	errBadTag         = errors.New("authentication tag mismatch")
	errUnknownCommand = errors.New("unknown command")
)

// cmac implements AES-CMAC (RFC 4493 / NIST SP 800-38B).
type cmac struct {
	block  cipher.Block
	k1, k2 [16]byte
}

func newCMAC(key []byte) (*cmac, error) {
	b, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	var l [16]byte
	b.Encrypt(l[:], l[:])
	c := &cmac{block: b}
	c.k1 = cmacDouble(l)
	c.k2 = cmacDouble(c.k1)
	return c, nil
}

// cmacDouble multiplies by x in GF(2^128) (left shift, conditional xor 0x87).
func cmacDouble(in [16]byte) [16]byte {
	var out [16]byte
	for i := 0; i < 15; i++ {
		out[i] = in[i]<<1 | in[i+1]>>7
	}
	out[15] = in[15]<<1 ^ (0x87 & -(in[0] >> 7))
	return out
}

func (c *cmac) Sum(msg []byte) [16]byte {
	n := (len(msg) + 15) / 16
	complete := n > 0 && len(msg)%16 == 0
	if n == 0 {
		n = 1
	}
	var last [16]byte
	tail := msg[(n-1)*16:]
	copy(last[:], tail)
	sub := &c.k1
	if !complete {
		last[len(tail)] = 0x80
		sub = &c.k2
	}
	for i := range last {
		last[i] ^= sub[i]
	}
	var x [16]byte
	for i := 0; i < n-1; i++ {
		blk := msg[i*16 : (i+1)*16]
		for j := range x {
			x[j] ^= blk[j]
		}
		c.block.Encrypt(x[:], x[:])
	}
	for j := range x {
		x[j] ^= last[j]
	}
	c.block.Encrypt(x[:], x[:])
	return x
}

// commandTag computes the tag a client must send for (challenge, cmd, arg).
func (c *cmac) commandTag(challenge []byte, cmd, arg byte) [16]byte {
	m := make([]byte, 0, len(macDomain)+challengeLen+2)
	m = append(m, macDomain...)
	m = append(m, challenge...)
	m = append(m, cmd, arg)
	return c.Sum(m)
}

type pendingChallenge struct {
	value  [challengeLen]byte
	issued time.Time
}

// Authenticator tracks one pending challenge per connection. Connection keys
// must be comparable (go-ble passes *hci.Conn pointers).
type Authenticator struct {
	mac     *cmac
	mu      sync.Mutex
	pending map[any]pendingChallenge
	now     func() time.Time
}

func NewAuthenticator(key []byte) (*Authenticator, error) {
	m, err := newCMAC(key)
	if err != nil {
		return nil, err
	}
	return &Authenticator{mac: m, pending: map[any]pendingChallenge{}, now: time.Now}, nil
}

// Issue creates a fresh challenge for conn, replacing any earlier one.
func (a *Authenticator) Issue(conn any) ([challengeLen]byte, error) {
	var p pendingChallenge
	if _, err := rand.Read(p.value[:]); err != nil {
		return p.value, err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	p.issued = a.now()
	a.pruneLocked()
	a.pending[conn] = p
	return p.value, nil
}

// Forget drops any pending challenge for conn (call on disconnect).
func (a *Authenticator) Forget(conn any) {
	a.mu.Lock()
	delete(a.pending, conn)
	a.mu.Unlock()
}

// Verify checks a command payload against conn's pending challenge and
// consumes that challenge regardless of the outcome.
func (a *Authenticator) Verify(conn any, payload []byte) (cmd, arg byte, err error) {
	if len(payload) != commandLen {
		return 0, 0, errBadLength
	}
	a.mu.Lock()
	p, ok := a.pending[conn]
	delete(a.pending, conn)
	now := a.now()
	a.mu.Unlock()

	if !ok {
		return 0, 0, errNoChallenge
	}
	if now.Sub(p.issued) > challengeTTL {
		return 0, 0, errExpired
	}
	cmd, arg = payload[0], payload[1]
	want := a.mac.commandTag(p.value[:], cmd, arg)
	if subtle.ConstantTimeCompare(want[:], payload[2:]) != 1 {
		return 0, 0, errBadTag
	}
	switch {
	case cmd == cmdPulseGarage && arg == 0:
	case cmd == cmdSetRelay && (arg == 0 || arg == 1):
	default:
		return 0, 0, errUnknownCommand
	}
	return cmd, arg, nil
}

func (a *Authenticator) pruneLocked() {
	now := a.now()
	for k, p := range a.pending {
		if now.Sub(p.issued) > challengeTTL {
			delete(a.pending, k)
		}
	}
}
