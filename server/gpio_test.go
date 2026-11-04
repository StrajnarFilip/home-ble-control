package main

import (
	"os"
	"testing"
)

// setPinEnv sets the pin variables for one test; "unset" removes a variable.
func setPinEnv(t *testing.T, garage, relay string) {
	t.Helper()
	for name, v := range map[string]string{garagePinEnv: garage, relayPinEnv: relay} {
		t.Setenv(name, v) // Registers restoring the original value.
		if v == "unset" {
			os.Unsetenv(name)
		}
	}
}

func TestLoadPins(t *testing.T) {
	tests := []struct {
		name, garage, relay string
		want                Pins
	}{
		{"defaults", "unset", "unset", Pins{Garage: 17, Relay: 27}},
		{"both set", "5", "6", Pins{Garage: 5, Relay: 6}},
		{"garage only", "22", "unset", Pins{Garage: 22, Relay: 27}},
		{"relay only", "unset", "22", Pins{Garage: 17, Relay: 22}},
		{"zero is a valid pin", "0", "1", Pins{Garage: 0, Relay: 1}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			setPinEnv(t, tt.garage, tt.relay)
			got, err := loadPins()
			if err != nil {
				t.Fatal(err)
			}
			if got != tt.want {
				t.Fatalf("got %+v, want %+v", got, tt.want)
			}
		})
	}
}

func TestLoadPinsRejectsInvalid(t *testing.T) {
	tests := []struct{ name, garage, relay string }{
		{"garage not a number", "GPIO17", "unset"},
		{"relay not a number", "unset", "abc"},
		{"negative", "-1", "unset"},
		{"empty", "", "unset"},
		{"same pin", "22", "22"},
		{"same as other default", "27", "unset"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			setPinEnv(t, tt.garage, tt.relay)
			if got, err := loadPins(); err == nil {
				t.Fatalf("got %+v, want error", got)
			}
		})
	}
}
