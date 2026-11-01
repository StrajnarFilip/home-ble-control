package main

import (
	"encoding/hex"
	"errors"
	"testing"
	"time"
)

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// RFC 4493 section 4 test vectors (AES-128).
func TestCMACRFC4493(t *testing.T) {
	key := mustHex(t, "2b7e151628aed2a6abf7158809cf4f3c")
	msg := mustHex(t, "6bc1bee22e409f96e93d7e117393172a"+
		"ae2d8a571e03ac9c9eb76fac45af8e51"+
		"30c81c46a35ce411e5fbc1191a0a52ef"+
		"f69f2445df4f9b17ad2b417be66c3710")
	cases := []struct {
		n    int
		want string
	}{
		{0, "bb1d6929e95937287fa37d129b756746"},
		{16, "070a16b46b4d4144f79bdd9dd04a287c"},
		{40, "dfa66747de9ae63030ca32611497c827"},
		{64, "51f0bebf7e3b9d92fc49741779363cfe"},
	}
	c, err := newCMAC(key)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(c.k1[:]); got != "fbeed618357133667c85e08f7236a8de" {
		t.Errorf("K1 = %s", got)
	}
	if got := hex.EncodeToString(c.k2[:]); got != "f7ddac306ae266ccf90bc11ee46d513b" {
		t.Errorf("K2 = %s", got)
	}
	for _, tc := range cases {
		got := c.Sum(msg[:tc.n])
		if hex.EncodeToString(got[:]) != tc.want {
			t.Errorf("len %d: got %x want %s", tc.n, got, tc.want)
		}
	}
}

// NIST SP 800-38B AES-256 example (64-byte message).
func TestCMACAES256(t *testing.T) {
	key := mustHex(t, "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
	msg := mustHex(t, "6bc1bee22e409f96e93d7e117393172a"+
		"ae2d8a571e03ac9c9eb76fac45af8e51"+
		"30c81c46a35ce411e5fbc1191a0a52ef"+
		"f69f2445df4f9b17ad2b417be66c3710")
	c, err := newCMAC(key)
	if err != nil {
		t.Fatal(err)
	}
	for n, want := range map[int]string{
		0:  "028962f61b7bf89efc6b551f4667d983",
		16: "28a7023f452e8f82bd4bf28d8c37c35c",
		64: "e1992190549f6ed5696a2c056c315410",
	} {
		got := c.Sum(msg[:n])
		if hex.EncodeToString(got[:]) != want {
			t.Errorf("len %d: got %x want %s", n, got, want)
		}
	}
}

// Protocol vector, computed independently with:
//
//	printf 'HBC1\x00\x01...\x0f\x02\x01' | openssl mac -cipher AES-256-CBC -macopt hexkey:0001..1f CMAC
//
// The Android unit test (CmacTest.kt) checks the same value.
func TestCommandTagVector(t *testing.T) {
	c, err := newCMAC(mustHex(t, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"))
	if err != nil {
		t.Fatal(err)
	}
	got := c.commandTag(mustHex(t, "000102030405060708090a0b0c0d0e0f"), cmdSetRelay, 1)
	if want := "5b26e246a530b9e275b3333d48291397"; hex.EncodeToString(got[:]) != want {
		t.Fatalf("got %x want %s", got, want)
	}
}

type fakeConn struct{ id int }

func newTestAuth(t *testing.T) (*Authenticator, *time.Time) {
	t.Helper()
	a, err := NewAuthenticator(mustHex(t, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1_700_000_000, 0)
	a.now = func() time.Time { return now }
	return a, &now
}

func signed(a *Authenticator, ch [challengeLen]byte, cmd, arg byte) []byte {
	tag := a.mac.commandTag(ch[:], cmd, arg)
	return append([]byte{cmd, arg}, tag[:]...)
}

func TestVerifyValidOnceThenReplayRejected(t *testing.T) {
	a, _ := newTestAuth(t)
	c := &fakeConn{1}
	ch, _ := a.Issue(c)
	msg := signed(a, ch, cmdSetRelay, 1)

	cmd, arg, err := a.Verify(c, msg)
	if err != nil || cmd != cmdSetRelay || arg != 1 {
		t.Fatalf("valid command rejected: %v", err)
	}
	if _, _, err := a.Verify(c, msg); !errors.Is(err, errNoChallenge) {
		t.Fatalf("replay accepted or wrong error: %v", err)
	}
}

func TestVerifyReplayAgainstNewChallengeRejected(t *testing.T) {
	a, _ := newTestAuth(t)
	c := &fakeConn{1}
	ch, _ := a.Issue(c)
	old := signed(a, ch, cmdPulseGarage, 0)
	if _, _, err := a.Verify(c, old); err != nil {
		t.Fatal(err)
	}
	a.Issue(c) // attacker (or client) fetches a new challenge
	if _, _, err := a.Verify(c, old); !errors.Is(err, errBadTag) {
		t.Fatalf("old message accepted with new challenge: %v", err)
	}
}

func TestVerifyExpired(t *testing.T) {
	a, now := newTestAuth(t)
	c := &fakeConn{1}
	ch, _ := a.Issue(c)
	*now = now.Add(challengeTTL + time.Second)
	if _, _, err := a.Verify(c, signed(a, ch, cmdPulseGarage, 0)); !errors.Is(err, errExpired) {
		t.Fatalf("expired challenge accepted: %v", err)
	}
}

func TestVerifyOtherConnection(t *testing.T) {
	a, _ := newTestAuth(t)
	c1, c2 := &fakeConn{1}, &fakeConn{2}
	ch, _ := a.Issue(c1)
	if _, _, err := a.Verify(c2, signed(a, ch, cmdPulseGarage, 0)); !errors.Is(err, errNoChallenge) {
		t.Fatalf("challenge from another connection accepted: %v", err)
	}
}

func TestVerifyTampered(t *testing.T) {
	a, _ := newTestAuth(t)
	c := &fakeConn{1}
	for name, mutate := range map[string]func([]byte){
		"cmd": func(m []byte) { m[0] = cmdPulseGarage },
		"arg": func(m []byte) { m[1] = 0 },
		"tag": func(m []byte) { m[5] ^= 1 },
	} {
		ch, _ := a.Issue(c)
		msg := signed(a, ch, cmdSetRelay, 1)
		mutate(msg)
		if _, _, err := a.Verify(c, msg); !errors.Is(err, errBadTag) {
			t.Errorf("%s tamper: got %v", name, err)
		}
		// The challenge is consumed even by a failed attempt.
		if _, _, err := a.Verify(c, signed(a, ch, cmdSetRelay, 1)); !errors.Is(err, errNoChallenge) {
			t.Errorf("%s: challenge reusable after failed attempt: %v", name, err)
		}
	}
}

func TestVerifyBadLengthAndUnknownCommand(t *testing.T) {
	a, _ := newTestAuth(t)
	c := &fakeConn{1}
	ch, _ := a.Issue(c)
	if _, _, err := a.Verify(c, signed(a, ch, cmdSetRelay, 1)[:10]); !errors.Is(err, errBadLength) {
		t.Fatalf("short payload: %v", err)
	}
	ch, _ = a.Issue(c)
	if _, _, err := a.Verify(c, signed(a, ch, 0x7f, 0)); !errors.Is(err, errUnknownCommand) {
		t.Fatalf("unknown command: %v", err)
	}
	ch, _ = a.Issue(c)
	if _, _, err := a.Verify(c, signed(a, ch, cmdSetRelay, 2)); !errors.Is(err, errUnknownCommand) {
		t.Fatalf("bad relay arg: %v", err)
	}
}

func TestIssueReplacesAndPrunes(t *testing.T) {
	a, now := newTestAuth(t)
	c1, c2 := &fakeConn{1}, &fakeConn{2}
	first, _ := a.Issue(c1)
	second, _ := a.Issue(c1)
	if first == second {
		t.Fatal("challenges not random")
	}
	if _, _, err := a.Verify(c1, signed(a, first, cmdPulseGarage, 0)); !errors.Is(err, errBadTag) {
		t.Fatalf("superseded challenge accepted: %v", err)
	}
	a.Issue(c1)
	*now = now.Add(challengeTTL + time.Second)
	a.Issue(c2)
	if len(a.pending) != 1 {
		t.Fatalf("expired challenges not pruned: %d pending", len(a.pending))
	}
}
