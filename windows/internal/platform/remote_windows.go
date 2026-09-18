package platform

import "github.com/Ferinmtk/droplet/windows/internal/remote"

// RemoteBackend is what remote control can do on this PC: SendInput,
// Core Audio, media sessions, LockWorkStation, screen capture and the
// clipboard.
func RemoteBackend() remote.Backend {
	return remote.Backend{
		Input:      &sendInput{},
		Volume:     coreAudio{},
		NowPlaying: nowPlaying{},
		Lock:       LockScreen,
		Capture:    CaptureScreen,
		Clipboard:  syncClipboard{},
	}
}
