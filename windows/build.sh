#!/usr/bin/env bash
# Builds droplet.exe (Windows, amd64) from any OS with Go installed.
#   ./build.sh [version]     → dist/droplet.exe
set -euo pipefail
cd "$(dirname "$0")"
version="${1:-1.0.0}"

# icon, manifest and version info go in as a .syso resource, which `go build` picks up
go run github.com/tc-hib/go-winres@v0.3.3 make --arch amd64 \
  --file-version "$version" --product-version "$version"

mkdir -p dist
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath \
  -ldflags "-s -w -H windowsgui -X main.version=$version" \
  -o dist/droplet.exe .
echo "built dist/droplet.exe ($(du -h dist/droplet.exe | cut -f1))"
