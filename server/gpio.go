package main

import (
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"

	"github.com/warthog618/go-gpiocdev"
)

// Hardware wiring defaults, overridable through the environment.
const (
	garagePinEnv     = "GARAGE_GPIO"
	relayPinEnv      = "RELAY_GPIO"
	activeLowEnv     = "GPIO_ACTIVE_LOW"
	defaultGaragePin = 17   // BCM numbering; header pin 11
	defaultRelayPin  = 27   // BCM numbering; header pin 13
	defaultActiveLow = true // Pin LOW = relay on.
	gpioConsumer     = "garage-ctl"
)

// Pins holds the BCM numbers of the two outputs.
type Pins struct {
	Garage int `json:"garage"`
	Relay  int `json:"relay"`
}

// loadPins reads the pins from the environment, using the defaults for unset
// variables. Invalid values are errors: falling back to a default would drive
// a pin the user didn't choose.
func loadPins() (Pins, error) {
	garage, err := pinFromEnv(garagePinEnv, defaultGaragePin)
	if err != nil {
		return Pins{}, err
	}
	relay, err := pinFromEnv(relayPinEnv, defaultRelayPin)
	if err != nil {
		return Pins{}, err
	}
	if garage == relay {
		return Pins{}, fmt.Errorf("garage ($%s) and relay ($%s) are both GPIO%d", garagePinEnv, relayPinEnv, garage)
	}
	return Pins{Garage: garage, Relay: relay}, nil
}

func pinFromEnv(name string, def int) (int, error) {
	v, ok := os.LookupEnv(name)
	if !ok {
		return def, nil
	}
	pin, err := strconv.Atoi(v)
	if err != nil || pin < 0 {
		return 0, fmt.Errorf("$%s: %q is not a GPIO number", name, v)
	}
	return pin, nil
}

// loadActiveLow reads the relay polarity from the environment. As with the
// pins, an invalid value is an error: guessing could energize the relays.
func loadActiveLow() (bool, error) {
	v, ok := os.LookupEnv(activeLowEnv)
	if !ok {
		return defaultActiveLow, nil
	}
	activeLow, err := strconv.ParseBool(v)
	if err != nil {
		return false, fmt.Errorf("$%s: %q is not true or false", activeLowEnv, v)
	}
	return activeLow, nil
}

// Labels of the gpiochip that drives the 40-pin header on each Pi generation.
var headerChipLabels = []string{
	"pinctrl-rp1",     // Pi 5
	"pinctrl-bcm2711", // Pi 4 / 400 / CM4
	"pinctrl-bcm2835", // Pi 1-3, Zero
}

// findHeaderChip locates the header gpiochip by label (its number differs
// between Pi models and kernel versions), falling back to gpiochip0.
func findHeaderChip() string {
	for _, name := range gpiocdev.Chips() {
		c, err := gpiocdev.NewChip(name)
		if err != nil {
			continue
		}
		label := c.Label
		c.Close()
		for _, want := range headerChipLabels {
			if strings.HasPrefix(label, want) {
				return name
			}
		}
	}
	return "gpiochip0"
}

type gpioLines struct {
	garage, relay *gpiocdev.Line
}

func openGPIO(pins Pins, activeLow bool) (Lines, error) {
	chip := findHeaderChip()
	opts := []gpiocdev.LineReqOption{gpiocdev.AsOutput(0), gpiocdev.WithConsumer(gpioConsumer)}
	if activeLow {
		opts = append(opts, gpiocdev.AsActiveLow)
	}
	g, err := gpiocdev.RequestLine(chip, pins.Garage, opts...)
	if err != nil {
		return nil, fmt.Errorf("request GPIO%d on %s: %w (is another process or kernel driver using it?)", pins.Garage, chip, err)
	}
	r, err := gpiocdev.RequestLine(chip, pins.Relay, opts...)
	if err != nil {
		g.Close()
		return nil, fmt.Errorf("request GPIO%d on %s: %w (is another process or kernel driver using it?)", pins.Relay, chip, err)
	}
	log.Printf("gpio: using %s, garage=GPIO%d relay=GPIO%d activeLow=%v", chip, pins.Garage, pins.Relay, activeLow)
	return &gpioLines{garage: g, relay: r}, nil
}

func (l *gpioLines) SetGarage(active bool) error { return l.garage.SetValue(b2i(active)) }
func (l *gpioLines) SetRelay(active bool) error  { return l.relay.SetValue(b2i(active)) }
func (l *gpioLines) Close() error {
	err1 := l.garage.Close()
	err2 := l.relay.Close()
	if err1 != nil {
		return err1
	}
	return err2
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}

// mockLines logs the pin levels instead of touching hardware (for -mock-gpio on
// a PC).
type mockLines struct {
	pins      Pins
	activeLow bool
}

func (m mockLines) SetGarage(active bool) error {
	log.Printf("mock-gpio: GPIO%d = %d", m.pins.Garage, b2i(active != m.activeLow))
	return nil
}
func (m mockLines) SetRelay(active bool) error {
	log.Printf("mock-gpio: GPIO%d = %d", m.pins.Relay, b2i(active != m.activeLow))
	return nil
}
func (mockLines) Close() error { return nil }
