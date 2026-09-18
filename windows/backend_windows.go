package main

import (
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/remote"
)

// remoteBackend is the real desktop: SendInput, Core Audio, the clipboard…
func remoteBackend() (remote.Backend, string) {
	return platform.RemoteBackend(), "windows"
}
