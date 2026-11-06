//go:build tools

// Keeps gomobile's packages in go.mod: build-aar.sh's gomobile bind needs
// golang.org/x/mobile/bind, and CI installs gomobile and gobind at this version.
package iphonelink

import (
	_ "golang.org/x/mobile/bind"
	_ "golang.org/x/mobile/cmd/gobind"
	_ "golang.org/x/mobile/cmd/gomobile"
)
