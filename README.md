# home-ble-control

Garage door + relay controller for a Raspberry Pi:

- **GPIO 14** (header pin 8): garage door relay, energized for a 500 ms pulse.
- **GPIO 15** (header pin 10): relay, switched on or off.

You can control it three ways:

1. **Web UI**: `http://<pi>:8080`. It also shows the pairing QR code.
2. **REST API**: same port; see below.
3. **Bluetooth LE**: the Android app in `android/` and its home-screen widget. Every command is authenticated with the shared AES key and protected against replay. Several phones can be connected at once.

The only configuration is the AES key.

## Quickstart: fresh Ubuntu on a Raspberry Pi

On a freshly flashed Ubuntu Server Pi, log in over SSH and paste this whole block. It builds the server on the Pi, installs it as a systemd service and applies the [prerequisites](#raspberry-pi-prerequisites-ubuntu-or-raspberry-pi-os). Copy everything from `(` to `)`: the block stops at the first error, only reboots if everything succeeded, and is safe to paste again.

```sh
(
set -eo pipefail

# Tools: git, plus Go from go.dev (Ubuntu's golang package is older than go.mod needs)
sudo apt-get -o DPkg::Lock::Timeout=300 update
sudo apt-get -o DPkg::Lock::Timeout=300 install -y git curl
GO_VERSION=$(curl -fsSL 'https://go.dev/VERSION?m=text' | sed -n 1p)
GO_ARCH=$(dpkg --print-architecture | sed 's/armhf/armv6l/')
sudo rm -rf /usr/local/go
curl -fsSL "https://go.dev/dl/$GO_VERSION.linux-$GO_ARCH.tar.gz" | sudo tar -C /usr/local -xz

# Build, install, and enable the service (it starts after the reboot)
[ -d ~/home-ble-control ] || git clone https://github.com/StrajnarFilip/home-ble-control.git ~/home-ble-control
cd ~/home-ble-control/server
git pull --ff-only
CGO_ENABLED=0 /usr/local/go/bin/go build -trimpath -ldflags='-s -w' -o garage-ctl .
sudo install -D -m 0755 garage-ctl /opt/garage-ctl/garage-ctl
sudo install -m 0644 garage-ctl.service /etc/systemd/system/garage-ctl.service
sudo systemctl daemon-reload
sudo systemctl enable garage-ctl

# Free GPIO 14/15 from the serial console, and keep bluetoothd (if any) off the adapter
sudo sed -i 's/console=serial0,[0-9]* *//' /boot/firmware/cmdline.txt
sudo sed -i 's/^enable_uart=.*/enable_uart=0/' /boot/firmware/config.txt
grep -q '^enable_uart=' /boot/firmware/config.txt || echo 'enable_uart=0' | sudo tee -a /boot/firmware/config.txt >/dev/null
sudo systemctl disable --now bluetooth 2>/dev/null || true

sudo reboot
)
```

After the reboot:

```sh
systemctl status garage-ctl     # active (running)
journalctl -u garage-ctl -f     # look for "gpio: using …" and "ble: advertising …"
hostname -I                     # the Pi's IP address
```

Open `http://<pi-ip>:8080` and scan the pairing QR code with the [Android app](#android-app-android).

To update later:

```sh
cd ~/home-ble-control && git pull && cd server &&
CGO_ENABLED=0 /usr/local/go/bin/go build -trimpath -ldflags='-s -w' -o garage-ctl . &&
sudo install -m 0755 garage-ctl /opt/garage-ctl/garage-ctl &&
sudo install -m 0644 garage-ctl.service /etc/systemd/system/garage-ctl.service &&
sudo systemctl daemon-reload && sudo systemctl restart garage-ctl
```

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

The unit is [`server/garage-ctl.service`](server/garage-ctl.service):

```ini
[Unit]
Description=Garage controller
After=network.target
# Keep restarting no matter how often it fails.
StartLimitIntervalSec=0

[Service]
WorkingDirectory=/opt/garage-ctl
ExecStart=/opt/garage-ctl/garage-ctl
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

`Restart=always` restarts the server 3 s after any exit, clean or not. `StartLimitIntervalSec=0` stops systemd from giving up after repeated failures.

The quickstart installs it for you. To install it by hand, run this from `server/`, with the binary built as `garage-ctl`:

```sh
sudo install -D -m 0755 garage-ctl /opt/garage-ctl/garage-ctl
sudo install -m 0644 garage-ctl.service /etc/systemd/system/garage-ctl.service
sudo systemctl daemon-reload
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

Open the app, tap **Scan pairing QR** and scan the code on the web UI. It needs the Bluetooth (nearby devices) and camera permissions. To pair without the camera, paste the key into the **Key** field instead: copy it from *Pairing details* on the web UI or from `key.txt`. The field also accepts the JSON from `/api/pairing`, which includes the Bluetooth address, so the app doesn't have to scan for the garage. After pairing:

- **Garage door** sends one pulse.
- **On** / **Off** set the relay.

The **Garage** home-screen widget has the same garage door button plus a relay toggle. For less space, the **Garage door** and **Relay** widgets are separate 1x1 tiles.

The app is in English and Slovenian. It follows the phone's language; on Android 13+ you can also choose one for this app alone in *Settings › Apps › Garage › Language*.

To make every tap instant, a foreground service keeps the connection open whenever the garage is in range. When it isn't, Android reconnects in the background within a second or two of the garage coming back into range. The service starts again after a reboot or an app update. Android lists it under active apps. Its silent "Connected to the garage" notification only appears if you allow the app's notifications. The service keeps a short connection interval, so it uses some battery, about as much as a smartwatch link. Tap **Forget** to stop it.

Relay changes made by anyone (another phone, the web UI, the REST API) show up in the app and the widget straight away.

The key is stored in app-private storage and is excluded from backups and device transfers. Re-pair on a new phone.

## BLE protocol

Service `5f6d0001-8c2b-4e3a-9a41-2b7c0e9d4a10`:

| Characteristic | UUID suffix | Access | Value |
|---|---|---|---|
| challenge | `…0002…` | read | 16 fresh random bytes on every read |
| command | `…0003…` | write (with response) | `cmd(1) \| arg(1) \| tag(16)` |
| state | `…0004…` | read, notify | `relay(1) \| garageBusy(1)`; on subscribe and on every change |

- `tag = AES-CMAC(key, "HBC1" \| challenge \| cmd \| arg)` (RFC 4493).
- Commands: `0x01 00` pulses the garage; `0x02 00/01` sets the relay off or on.

Each challenge belongs to the connection that read it, lasts 30 s and is consumed by the first write, valid or not. A recorded command can't be replayed, and a tampered one fails the tag check.

The server goes on advertising while phones are connected, so further phones can connect. The adapter's connection limit still applies (usually several phones). If an adapter can't advertise while connected, the log says so, and other phones then wait until the connected one leaves.

Rejected writes return an ATT error:

| Code | Meaning |
|---|---|
| `0x80` | authentication failed |
| `0x81` | garage pulse already running |
| `0x82` | GPIO error |
