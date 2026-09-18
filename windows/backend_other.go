//go:build !windows

package main

import (
	"fmt"
	"os"

	"github.com/Ferinmtk/droplet/windows/internal/remote"
)

// remoteBackend, off Windows, is a stand-in that acts on nothing: it
// records every call as a JSON line (to $DROPLET_FAKE_LOG, or stderr), and
// uses the file $DROPLET_FAKE_CLIP as the clipboard when set. It exists to
// test the protocol end to end against a real hub.
func remoteBackend() (remote.Backend, string) {
	f := &remote.Fake{Log: os.Stderr, ClipFile: os.Getenv("DROPLET_FAKE_CLIP")}
	if p := os.Getenv("DROPLET_FAKE_LOG"); p != "" {
		w, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
		if err != nil {
			fmt.Fprintln(os.Stderr, "droplet: fake backend log:", err)
		} else {
			f.Log = w
		}
	}
	return f.Backend(), "linux"
}
