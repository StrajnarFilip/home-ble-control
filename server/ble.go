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
	"golang.org/x/sys/unix"
)

// GATT layout shared with the Android app (see android/.../BleClient.kt).
var (
	serviceUUID   = ble.MustParse("5f6d0001-8c2b-4e3a-9a41-2b7c0e9d4a10")
	challengeUUID = ble.MustParse("5f6d0002-8c2b-4e3a-9a41-2b7c0e9d4a10") // read: fresh 16-byte challenge
	commandUUID   = ble.MustParse("5f6d0003-8c2b-4e3a-9a41-2b7c0e9d4a10") // write: cmd|arg|tag
	stateUUID     = ble.MustParse("5f6d0004-8c2b-4e3a-9a41-2b7c0e9d4a10") // read: relay(1)|garageBusy(1)
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
	dev, err := linux.NewDeviceWithName(bleName)
	if err != nil {
		if errors.Is(err, unix.EBUSY) || strings.Contains(err.Error(), "busy") {
			err = fmt.Errorf("%w (adapter in use: stop bluetoothd with `sudo systemctl disable --now bluetooth`)", err)
		}
		if errors.Is(err, unix.EPERM) || strings.Contains(err.Error(), "not permitted") {
			err = fmt.Errorf("%w (run as root or grant CAP_NET_ADMIN,CAP_NET_RAW)", err)
		}
		return nil, "", err
	}
	s := &bleServer{dev: dev, auth: auth, ctrl: ctrl}

	svc := ble.NewService(serviceUUID)
	svc.NewCharacteristic(challengeUUID).HandleRead(ble.ReadHandlerFunc(s.readChallenge))
	cmdChar := svc.NewCharacteristic(commandUUID)
	cmdChar.HandleWrite(ble.WriteHandlerFunc(s.writeCommand))
	cmdChar.Property &^= ble.CharWriteNR // write-with-response only, so errors reach the client
	svc.NewCharacteristic(stateUUID).HandleRead(ble.ReadHandlerFunc(s.readState))
	if err := dev.AddService(svc); err != nil {
		dev.Stop()
		return nil, "", fmt.Errorf("add GATT service: %w", err)
	}

	addr := strings.ToUpper(dev.HCI.Addr().String())
	go func() {
		// go-ble re-enables advertising itself after each disconnect.
		err := dev.AdvertiseNameAndServices(ctx, bleName, serviceUUID)
		if err != nil && !errors.Is(err, context.Canceled) {
			log.Printf("ble: advertising stopped: %v", err)
		}
	}()
	log.Printf("ble: advertising %q on %s", bleName, addr)
	return s, addr, nil
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

func (s *bleServer) readState(req ble.Request, rsp ble.ResponseWriter) {
	st := s.ctrl.State()
	if _, err := rsp.Write([]byte{byte(b2i(st.Relay)), byte(b2i(st.GarageBusy))}); err != nil {
		rsp.SetStatus(ble.ErrUnlikely)
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
