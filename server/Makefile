BIN := garage-ctl
GOFLAGS := -trimpath -ldflags="-s -w"

.PHONY: pi64 pi32 local test clean

# Raspberry Pi 3/4/5 running a 64-bit OS (Ubuntu, Raspberry Pi OS arm64)
pi64:
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build $(GOFLAGS) -o $(BIN)-arm64 .

# Pi Zero/1, or any Pi on a 32-bit OS
pi32:
	CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=6 go build $(GOFLAGS) -o $(BIN)-armv6 .

local:
	CGO_ENABLED=0 go build -o $(BIN) .

test:
	go vet ./... && go test ./...

clean:
	rm -f $(BIN) $(BIN)-arm64 $(BIN)-armv6
