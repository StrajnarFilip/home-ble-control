package main

import (
	"fmt"
	"log"
	"strings"

	"github.com/warthog618/go-gpiocdev"
)

// Hardware wiring. Change activeLow if your relay boards trigger on LOW.
const (
	garagePin    = 14 // BCM numbering; header pin 8
	relayPin     = 15 // BCM numbering; header pin 10
	activeLow    = false
	gpioConsumer = "garage-ctl"
)

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

func openGPIO() (Lines, error) {
	chip := findHeaderChip()
	opts := []gpiocdev.LineReqOption{gpiocdev.AsOutput(0), gpiocdev.WithConsumer(gpioConsumer)}
	if activeLow {
		opts = append(opts, gpiocdev.AsActiveLow)
	}
	g, err := gpiocdev.RequestLine(chip, garagePin, opts...)
	if err != nil {
		return nil, fmt.Errorf("request GPIO%d on %s: %w (is the serial console/UART still enabled on GPIO14/15?)", garagePin, chip, err)
	}
	r, err := gpiocdev.RequestLine(chip, relayPin, opts...)
	if err != nil {
		g.Close()
		return nil, fmt.Errorf("request GPIO%d on %s: %w (is the serial console/UART still enabled on GPIO14/15?)", relayPin, chip, err)
	}
	log.Printf("gpio: using %s, garage=GPIO%d relay=GPIO%d activeLow=%v", chip, garagePin, relayPin, activeLow)
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

// mockLines logs instead of touching hardware (for -mock-gpio on a PC).
type mockLines struct{}

func (mockLines) SetGarage(active bool) error {
	log.Printf("mock-gpio: GPIO%d = %d", garagePin, b2i(active))
	return nil
}
func (mockLines) SetRelay(active bool) error {
	log.Printf("mock-gpio: GPIO%d = %d", relayPin, b2i(active))
	return nil
}
func (mockLines) Close() error { return nil }
