# home-ble-control

Garage door + relay controller for a Raspberry Pi:

- **GPIO 14** (header pin 8): garage door relay, energized for a 500 ms pulse.
- **GPIO 15** (header pin 10): relay, switched on or off.

You can control it three ways:

1. **Web UI**: `http://<pi>:8080`. It also shows the pairing QR code.
2. **REST API**: same port; see below.
3. **Bluetooth LE**: the Android app in `android/`. Every command is authenticated with the shared AES key and protected against replay.

The only configuration is the AES key.

## Server (`server/`)

### Build

```sh
cd server
make test     # unit tests (CMAC test vectors, replay protection, GATT handlers)
make pi64     # garage-ctl-arm64: Pi 3/4/5 on a 64-bit OS
make pi32     # garage-ctl-armv6: Pi Zero/1 or any 32-bit OS
```

The output is a single static binary. On the Pi you don't need libgpiod, BlueZ or any other package:

- GPIO goes through the kernel's `/dev/gpiochip*` character device.
- Bluetooth goes through the kernel's HCI socket, via the pure-Go stack [go-ble](https://github.com/go-ble/ble).

### Run

```sh
scp server/garage-ctl-arm64 pi:~/garage-ctl
ssh pi 'sudo ./garage-ctl'
```

The key is looked up in this order:

| Source | Example |
|---|---|
| `-key <path>` | `sudo ./garage-ctl -key /etc/garage/key.txt` |
| `GARAGE_KEY` env var | `sudo GARAGE_KEY=00112233… ./garage-ctl` |
| `./key.txt` (default) | created automatically on first run |

The key is hex: 32, 48 or 64 characters (AES-128, AES-192 or AES-256). If `./key.txt` doesn't exist, a random AES-256 key is generated and saved with mode 0600.

Other flags: `-listen :8080`, plus `-mock-gpio` and `-no-ble` for development on a PC.

### Raspberry Pi prerequisites (Ubuntu or Raspberry Pi OS)

1. **Free GPIO 14 and 15 from the serial port.** They are the UART TX/RX pins. If the serial console stays on, boot messages toggle the garage relay.
   - In `/boot/firmware/cmdline.txt`, remove `console=serial0,115200`.
   - In `/boot/firmware/config.txt`, set `enable_uart=0`.
   - Reboot. On Raspberry Pi OS, `sudo raspi-config` → Interface → Serial Port → No / No does the same.
   - Don't add `dtoverlay=disable-bt`: onboard Bluetooth uses the other UART.
2. **Check that Bluetooth exists:** `ls /sys/class/bluetooth` should list `hci0`.
3. **Raspberry Pi OS only:** `sudo systemctl disable --now bluetooth`. This binary takes the adapter for itself, so `bluetoothd` must not hold it. Ubuntu Server doesn't run `bluetoothd`.
4. **Run as root**, or give the binary the capabilities it needs:
   `sudo setcap cap_net_admin,cap_net_raw+ep garage-ctl`. That covers Bluetooth only; GPIO also needs access to `/dev/gpiochip*`.

Relays are assumed **active-high** (pin HIGH = relay on). If yours trigger on LOW, set `activeLow = true` in `server/gpio.go`.

### systemd unit

`/etc/systemd/system/garage-ctl.service`:

```ini
[Unit]
Description=Garage controller
After=network.target

[Service]
WorkingDirectory=/opt/garage-ctl
ExecStart=/opt/garage-ctl/garage-ctl
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Then:

```sh
sudo mkdir -p /opt/garage-ctl
sudo cp garage-ctl /opt/garage-ctl/
sudo systemctl enable --now garage-ctl
```

`key.txt` is created in `/opt/garage-ctl` on first start.

### REST API

The web UI and REST API are **unauthenticated**: anyone who can reach the port can use them, and `/api/qr.png` hands out the key. Keep port 8080 on your LAN and never port-forward it.

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/state` | | `{"relay":false,"garageBusy":false}` |
| POST | `/api/garage/pulse` | | `{"ok":true}`, or `409` if a pulse is already running |
| POST | `/api/relay` | `{"on":true}` | `{"relay":true}` |
| GET | `/api/pairing` | | `{"v":1,"key":"…","addr":"B8:27:EB:…","name":"Garage"}` |
| GET | `/api/qr.png` | | the pairing JSON as a QR code |

```sh
curl -X POST http://pi:8080/api/garage/pulse
curl -X POST http://pi:8080/api/relay -d '{"on":true}'
```

## Android app (`android/`)

Requires Android 12 or newer. To build and install:

```sh
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app, tap **Scan pairing QR** and scan the code on the web UI. It needs the Bluetooth (nearby devices) and camera permissions. After pairing:

- **Garage door** sends one pulse.
- **On** / **Off** set the relay.

The app connects when it opens and disconnects when you leave it. The key is stored in app-private storage and is excluded from backups and device transfers. Re-pair on a new phone.

## BLE protocol

Service `5f6d0001-8c2b-4e3a-9a41-2b7c0e9d4a10`:

| Characteristic | UUID suffix | Access | Value |
|---|---|---|---|
| challenge | `…0002…` | read | 16 fresh random bytes on every read |
| command | `…0003…` | write (with response) | `cmd(1) \| arg(1) \| tag(16)` |
| state | `…0004…` | read | `relay(1) \| garageBusy(1)` |

- `tag = AES-CMAC(key, "HBC1" \| challenge \| cmd \| arg)` (RFC 4493).
- Commands: `0x01 00` pulses the garage; `0x02 00/01` sets the relay off or on.

Each challenge belongs to the connection that read it, lasts 30 s and is consumed by the first write, valid or not. A recorded command can't be replayed, and a tampered one fails the tag check.

Rejected writes return an ATT error:

| Code | Meaning |
|---|---|
| `0x80` | authentication failed |
| `0x81` | garage pulse already running |
| `0x82` | GPIO error |
