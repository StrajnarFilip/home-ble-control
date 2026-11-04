#!/bin/sh
# Updates this Pi to the latest code: pulls, rebuilds, reinstalls and restarts
# the service. Run it as your normal user; it uses sudo where needed. The key in
# /opt/garage-ctl and any `systemctl edit` overrides are left alone.
set -eu
export PATH="/usr/local/go/bin:$PATH"
cd "$(dirname "$0")"

git pull --ff-only
CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o garage-ctl .
sudo install -m 0755 garage-ctl /opt/garage-ctl/garage-ctl
sudo install -m 0644 garage-ctl.service /etc/systemd/system/garage-ctl.service
sudo systemctl daemon-reload

started=$(date '+%F %T')
sudo systemctl restart garage-ctl
sleep 2 # restart returns before a crash shows; this lands inside RestartSec
sudo journalctl -u garage-ctl --since "$started" --no-pager -o cat
systemctl is-active garage-ctl
