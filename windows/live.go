package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/remote"
	"github.com/Ferinmtk/droplet/windows/internal/settings"
)

// newSession makes the live connection for an agent's settings.
func newSession(a *agent.Agent, params func() remote.Params, onStatus func(remote.Status)) *remote.Session {
	backend, plat := remoteBackend()
	return remote.New(remote.Options{
		Params:   params,
		Backend:  backend,
		App:      "droplet-windows/" + version,
		Platform: plat,
		Notify: func(title, body string) {
			platform.Notify(platform.Notification{Title: title, Body: body, Tag: "remote"})
		},
		OnStatus: onStatus,
	})
}

// cmdLive runs only the live connection, in the foreground, logging to the
// console: for testing remote control without the tray.
func cmdLive(args []string) error {
	fs := newFlags("live")
	capsFlag := fs.String("caps", "", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	log.SetOutput(os.Stderr) // the console attached after the log package started
	store, err := openStore()
	if err != nil {
		return err
	}
	cfg := store.Get()
	if !cfg.Registered() {
		return errors.New("this PC isn't set up yet: run droplet link --code <code> (or droplet setup) first")
	}
	exe, _ := os.Executable()
	a := agent.New(store, exe)
	params := a.RemoteParams
	if *capsFlag != "" {
		// exactly these, whatever the settings say (and not paused)
		want := map[string]bool{}
		for _, c := range strings.Split(*capsFlag, ",") {
			c = strings.TrimSpace(c)
			if c == "" {
				continue
			}
			if !contains(remote.AllCaps, c) {
				return fmt.Errorf("unknown capability %q (have: %s)", c, strings.Join(remote.AllCaps, ", "))
			}
			want[c] = true
		}
		params = func() remote.Params {
			p := a.RemoteParams()
			p.Caps, p.Paused = want, false
			return p
		}
	}
	last := ""
	sess := newSession(a, params, func(st remote.Status) {
		line := "offline"
		switch {
		case len(st.Offered) == 0:
			line = "nothing to offer (every capability is off, or remote control is paused)"
		case st.Live && st.Controller != "":
			line = "live, offering " + strings.Join(st.Offered, ", ") + "; controlled by " + st.Controller
		case st.Live:
			line = "live, offering " + strings.Join(st.Offered, ", ")
		case st.Problem != "":
			line = "not connected: " + st.Problem
		}
		if line != last {
			last = line
			log.Printf("status: %s", line)
		}
	})
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log.Printf("live: %s as %q, Ctrl+C to stop", cfg.HubURL, cfg.DeviceName)
	sess.Run(ctx)
	platform.FlushNotifications(5e9)
	return nil
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// cmdLink joins this app to an existing device with a six-digit code.
func cmdLink(args []string) error {
	fs := newFlags("link")
	code := fs.String("code", "", "")
	hubURL := fs.String("hub", "", "")
	pin := fs.String("pin", "", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *code == "" && fs.NArg() == 1 {
		*code = fs.Arg(0)
	}
	if *code == "" {
		return errors.New("usage: droplet link --code 123456 [--hub <url>] [--pin <pin>]")
	}
	store, err := openStore()
	if err != nil {
		return err
	}
	exe, _ := os.Executable()
	a := agent.New(store, exe)
	if *hubURL == "" {
		*hubURL = store.Get().HubURL
	}
	res, err := a.Link(context.Background(), *hubURL, *code, *pin)
	if err != nil {
		return err
	}
	// a running tray keeps its own copy of the settings: have it re-read them
	_ = postRunningReload()
	fmt.Printf("linked: this app is now part of %q (id %s) on %s\n", res.Name, res.ID, store.Get().HubURL)
	if res.Replaced != nil {
		fmt.Printf("its old entry %q (id %s) is still on the hub; remove it under Devices in droplet if you like\n",
			res.Replaced.Name, res.Replaced.ID)
	}
	return nil
}

func postRunningReload() error { return settings.PostRunning("api/reload") }
