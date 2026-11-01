package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"sync"

	"github.com/go-ble/ble"
	"github.com/go-ble/ble/linux"
	"github.com/go-ble/ble/linux/adv"
	"github.com/go-ble/ble/linux/hci"
	"github.com/go-ble/ble/linux/hci/cmd"
	"github.com/go-ble/ble/linux/hci/evt"
	"golang.org/x/sys/unix"
)

// GATT layout shared with the Android app (see android/.../BleClient.kt).
var (
	serviceUUID   = ble.MustParse("5f6d0001-8c2b-4e3a-9a41-2b7c0e9d4a10")
	challengeUUID = ble.MustParse("5f6d0002-8c2b-4e3a-9a41-2b7c0e9d4a10") // read: fresh 16-byte challenge
	commandUUID   = ble.MustParse("5f6d0003-8c2b-4e3a-9a41-2b7c0e9d4a10") // write: cmd|arg|tag
	stateUUID     = ble.MustParse("5f6d0004-8c2b-4e3a-9a41-2b7c0e9d4a10") // read/notify: relay(1)|garageBusy(1)
)

const bleName = "Garage"

// Application ATT error codes returned on a rejected command write.
const (
	attErrAuth ble.ATTError = 0x80 // missing/expired challenge or bad tag
	attErrBusy ble.ATTError = 0x81 // garage pulse already running
	attErrGPIO ble.ATTError = 0x82 // hardware error
)

type bleServer struct {
	dev     *linux.Device
	auth    *Authenticator
	ctrl    *Controller
	watched sync.Map // ble.Conn -> struct{}; disconnect watchers started
}

// startBLE takes exclusive control of the first HCI adapter through the kernel's
// HCI user channel (no BlueZ needed), registers the GATT service and starts
// advertising. It returns the adapter address.
func startBLE(ctx context.Context, auth *Authenticator, ctrl *Controller) (*bleServer, string, error) {
	s := &bleServer{auth: auth, ctrl: ctrl}
	// The controller stops advertising when a phone connects. go-ble would leave it off
	// until that phone disconnects, hiding the Pi from every other phone, so advertising
	// is managed here instead and switched back on after every connect and disconnect.
	// The handlers run on go-ble's event loop, which must not wait for a command reply.
	dev, err := linux.NewDeviceWithName(bleName,
		ble.OptConnectHandler(func(evt.LEConnectionComplete) { go s.resumeAdvertising(ctx, "connect") }),
		ble.OptDisconnectHandler(func(evt.DisconnectionComplete) { go s.resumeAdvertising(ctx, "disconnect") }),
	)
	if err != nil {
		if errors.Is(err, unix.EBUSY) || strings.Contains(err.Error(), "busy") {
			err = fmt.Errorf("%w (adapter in use: stop bluetoothd with `sudo systemctl disable --now bluetooth`)", err)
		}
		if errors.Is(err, unix.EPERM) || strings.Contains(err.Error(), "not permitted") {
			err = fmt.Errorf("%w (run as root or grant CAP_NET_ADMIN,CAP_NET_RAW)", err)
		}
		return nil, "", err
	}
	s.dev = dev

	svc := ble.NewService(serviceUUID)
	svc.NewCharacteristic(challengeUUID).HandleRead(ble.ReadHandlerFunc(s.readChallenge))
	cmdChar := svc.NewCharacteristic(commandUUID)
	cmdChar.HandleWrite(ble.WriteHandlerFunc(s.writeCommand))
	cmdChar.Property &^= ble.CharWriteNR // write-with-response only, so errors reach the client
	stateChar := svc.NewCharacteristic(stateUUID)
	stateChar.HandleRead(ble.ReadHandlerFunc(s.readState))
	stateChar.HandleNotify(ble.NotifyHandlerFunc(s.notifyState))
	if err := dev.AddService(svc); err != nil {
		dev.Stop()
		return nil, "", fmt.Errorf("add GATT service: %w", err)
	}

	if err := s.startAdvertising(); err != nil {
		dev.Stop()
		return nil, "", fmt.Errorf("advertise: %w", err)
	}
	go func() {
		<-ctx.Done()
		_ = s.setAdvertising(false)
	}()
	addr := strings.ToUpper(dev.HCI.Addr().String())
	log.Printf("ble: advertising %q on %s", bleName, addr)
	return s, addr, nil
}

// startAdvertising advertises the name and service UUID. Flags (3 bytes), the
// 128-bit UUID (18) and the name (8) fit in one 31-byte advertising packet.
func (s *bleServer) startAdvertising() error {
	ad, err := adv.NewPacket(
		adv.Flags(adv.FlagGeneralDiscoverable|adv.FlagLEOnly),
		adv.AllUUID(serviceUUID),
		adv.CompleteName(bleName),
	)
	if err != nil {
		return err
	}
	if err := s.dev.HCI.SetAdvertisement(ad.Bytes(), nil); err != nil {
		return err
	}
	return s.setAdvertising(true)
}

// setAdvertising sends the enable command directly rather than through
// go-ble's Advertise, so go-ble doesn't switch advertising off on each connect.
func (s *bleServer) setAdvertising(on bool) error {
	var enable uint8
	if on {
		enable = 1
	}
	return s.dev.HCI.Send(&cmd.LESetAdvertiseEnable{AdvertisingEnable: enable}, nil)
}

func (s *bleServer) resumeAdvertising(ctx context.Context, after string) {
	if ctx.Err() != nil {
		return
	}
	err := s.setAdvertising(true)
	switch {
	case err == nil:
	case errors.Is(err, hci.ErrDisallowed) && after == "disconnect":
		// Still advertising (older controllers reject enabling it twice).
	case errors.Is(err, hci.ErrDisallowed):
		log.Printf("ble: adapter can't advertise while a phone is connected (%v); other phones must wait for it to disconnect", err)
	default:
		log.Printf("ble: resume advertising after %s: %v", after, err)
	}
}

func (s *bleServer) Stop() {
	s.dev.Stop()
}

// watch drops a connection's pending challenge once it disconnects.
func (s *bleServer) watch(c ble.Conn) {
	if _, loaded := s.watched.LoadOrStore(c, struct{}{}); loaded {
		return
	}
	log.Printf("ble: client %s connected", c.RemoteAddr())
	go func() {
		<-c.Disconnected()
		s.auth.Forget(c)
		s.watched.Delete(c)
		log.Printf("ble: client %s disconnected", c.RemoteAddr())
	}()
}

func (s *bleServer) readChallenge(req ble.Request, rsp ble.ResponseWriter) {
	c := req.Conn()
	s.watch(c)
	ch, err := s.auth.Issue(c)
	if err != nil {
		rsp.SetStatus(ble.ErrUnlikely)
		return
	}
	if _, err := rsp.Write(ch[:]); err != nil {
		rsp.SetStatus(ble.ErrUnlikely)
	}
}

func stateValue(st State) []byte {
	return []byte{byte(b2i(st.Relay)), byte(b2i(st.GarageBusy))}
}

func (s *bleServer) readState(req ble.Request, rsp ble.ResponseWriter) {
	if _, err := rsp.Write(stateValue(s.ctrl.State())); err != nil {
		rsp.SetStatus(ble.ErrUnlikely)
	}
}

// notifyState runs while a phone is subscribed to the state characteristic. It sends
// the current state straight away and then every change, whichever client (BLE or
// HTTP) caused it, so all connected phones stay current without polling.
func (s *bleServer) notifyState(req ble.Request, n ble.Notifier) {
	changed, stop := s.ctrl.Watch()
	defer stop()
	for {
		if _, err := n.Write(stateValue(s.ctrl.State())); err != nil {
			return
		}
		select {
		case <-n.Context().Done():
			return
		case <-changed:
		}
	}
}

func (s *bleServer) writeCommand(req ble.Request, rsp ble.ResponseWriter) {
	c := req.Conn()
	s.watch(c)
	cmd, arg, err := s.auth.Verify(c, req.Data())
	if err != nil {
		log.Printf("ble: rejected command from %s: %v", c.RemoteAddr(), err)
		switch {
		case errors.Is(err, errBadLength):
			rsp.SetStatus(ble.ErrInvalAttrValueLen)
		case errors.Is(err, errUnknownCommand):
			rsp.SetStatus(ble.ErrReqNotSupp)
		default:
			rsp.SetStatus(attErrAuth)
		}
		return
	}
	switch cmd {
	case cmdPulseGarage:
		err = s.ctrl.PulseGarage()
	case cmdSetRelay:
		err = s.ctrl.SetRelay(arg == 1)
	}
	switch {
	case errors.Is(err, errBusy):
		rsp.SetStatus(attErrBusy)
	case err != nil:
		log.Printf("ble: command failed: %v", err)
		rsp.SetStatus(attErrGPIO)
	default:
		log.Printf("ble: command 0x%02x arg %d from %s ok", cmd, arg, c.RemoteAddr())
	}
}
