package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"

	"github.com/Ferinmtk/droplet/windows/assets"
	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/settings"
	"github.com/Ferinmtk/droplet/windows/internal/tray"
)

// runApp starts the tray companion. A second launch opens the running
// one's settings page instead of starting another tray icon.
func runApp() {
	if !platform.FirstInstance() {
		if u, ok := settings.RunningURL(); ok {
			platform.OpenURL(u)
		} else {
			platform.MessageBox("droplet", "droplet is already running — look for the drop in the notification area.", false)
		}
		return
	}
	dir, err := config.Dir()
	if err != nil {
		fatal(err)
	}
	setupLog(dir)
	log.Printf("droplet %s starting", version)

	store, err := openStore()
	if err != nil {
		fatal(err)
	}
	cfg := store.Get()
	platform.SetActionKey(cfg.ActionKey)
	exe, err := os.Executable()
	if err != nil {
		fatal(err)
	}
	if p, err := filepath.EvalSymlinks(exe); err == nil {
		exe = p
	}

	// what toasts need (a name, an icon, the droplet: link handler); all per-user, cheap to redo
	icon := filepath.Join(dir, "droplet.png")
	if err := os.WriteFile(icon, assets.PNG, 0o644); err != nil {
		log.Printf("icon: %v", err)
		icon = ""
	}
	if err := platform.Register(exe, icon); err != nil {
		log.Printf("register: %v", err)
	}
	if err := platform.EnsureStartMenu(exe); err != nil {
		log.Printf("start menu: %v", err)
	}
	if cfg.Autostart {
		// rewrite it, in case droplet.exe has moved since
		if err := platform.SetAutostart(exe, true); err != nil {
			log.Printf("autostart: %v", err)
		}
	}

	a := agent.New(store, exe)
	srv, err := settings.Start(a)
	if err != nil {
		fatal(fmt.Errorf("settings page: %w", err))
	}
	if err := srv.Publish(); err != nil {
		log.Printf("publish: %v", err)
	}
	openSettings := func() {
		if err := platform.OpenURL(srv.URL()); err != nil {
			log.Printf("open settings: %v", err)
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	tray.Run(tray.Options{
		Agent:        a,
		OpenSettings: openSettings,
		OnReady: func() {
			go func() { a.Run(ctx); close(done) }()
			if !cfg.Registered() {
				openSettings() // first run: straight to setup
			}
		},
		OnQuit: func() {
			cancel()
			<-done // silences a ring in progress
			srv.Close()
			settings.Unpublish()
			log.Printf("droplet stopped")
		},
	})
}

// setupLog sends the log to %APPDATA%\droplet\droplet.log, restarted when it gets big.
func setupLog(dir string) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	p := filepath.Join(dir, "droplet.log")
	if st, err := os.Stat(p); err == nil && st.Size() > 1<<20 {
		os.Rename(p, p+".old")
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	log.SetOutput(f)
	log.SetFlags(log.LstdFlags)
}

func fatal(err error) {
	log.Printf("fatal: %v", err)
	platform.MessageBox("droplet", "droplet couldn't start:\n\n"+err.Error(), true)
	os.Exit(1)
}
