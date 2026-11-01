package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"strings"
)

const (
	keyEnv         = "GARAGE_KEY"
	defaultKeyPath = "key.txt"
)

func parseKeyHex(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	k, err := hex.DecodeString(s)
	if err != nil {
		return nil, fmt.Errorf("key is not valid hex: %w", err)
	}
	switch len(k) {
	case 16, 24, 32:
		return k, nil
	}
	return nil, fmt.Errorf("key must be 16, 24 or 32 bytes (32/48/64 hex chars), got %d bytes", len(k))
}

// loadKey resolves the key: explicit -key file > $GARAGE_KEY > ./key.txt.
// If the default key.txt does not exist, a new random AES-256 key is created.
func loadKey(path string, explicit bool) (key []byte, source string, err error) {
	if !explicit {
		if v, ok := os.LookupEnv(keyEnv); ok {
			k, err := parseKeyHex(v)
			if err != nil {
				return nil, "", fmt.Errorf("$%s: %w", keyEnv, err)
			}
			return k, "$" + keyEnv, nil
		}
	}
	b, err := os.ReadFile(path)
	if err == nil {
		k, err := parseKeyHex(string(b))
		if err != nil {
			return nil, "", fmt.Errorf("%s: %w", path, err)
		}
		return k, path, nil
	}
	if !errors.Is(err, fs.ErrNotExist) || explicit {
		return nil, "", err
	}
	k := make([]byte, 32)
	if _, err := rand.Read(k); err != nil {
		return nil, "", err
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return nil, "", fmt.Errorf("create %s: %w", path, err)
	}
	if _, err := fmt.Fprintln(f, hex.EncodeToString(k)); err != nil {
		f.Close()
		return nil, "", err
	}
	if err := f.Close(); err != nil {
		return nil, "", err
	}
	return k, path + " (newly generated)", nil
}
