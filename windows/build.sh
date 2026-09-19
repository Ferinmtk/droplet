#!/usr/bin/env bash
# Builds droplet.exe (Windows, amd64) from any OS with Go installed.
#   ./build.sh [version]     → dist/droplet.exe
#
# The exe is built to look like what it is, an ordinary application: full
# version information and manifest, symbols kept (no -s -w), and no packer
# such as UPX. Stripped or packed binaries are a classic antivirus red flag
# (see DEFENDER.md).
set -euo pipefail
cd "$(dirname "$0")"
version="${1:-1.0.0}"

# Windows wants four numbers (major.minor.patch.build) in the manifest
IFS=. read -r v1 v2 v3 v4 <<<"${version%%[-+]*}"
quad="${v1:-0}.${v2:-0}.${v3:-0}.${v4:-0}"
if ! [[ $quad =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "build.sh: version should look like 1.2.3, not \"$version\"" >&2
  exit 1
fi

# icon, manifest and version info go in as a .syso resource, which `go build`
# picks up; the manifest's identity carries this build's version too
gen=winres/.build.json # next to winres.json, so its relative paths still work
trap 'rm -f "$gen"' EXIT
sed "s/\"version\": \"[0-9.]*\"/\"version\": \"$quad\"/" winres/winres.json >"$gen"
go run github.com/tc-hib/go-winres@v0.3.3 make --in "$gen" --arch amd64 \
  --file-version "$version" --product-version "$version"

mkdir -p dist
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath \
  -ldflags "-H windowsgui -X main.version=$version" \
  -o dist/droplet.exe .
echo "built dist/droplet.exe ($(du -h dist/droplet.exe | cut -f1))"
