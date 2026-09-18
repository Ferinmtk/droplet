//go:build !windows

package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/settings"
)

// runApp, off Windows, runs the companion headless: the same polling,
// downloads and settings page, with notifications printed to the console.
// It's for developing and testing the companion against a real hub.
func runApp() {
	store, err := openStore()
	if err != nil {
		fmt.Fprintln(os.Stderr, "droplet:", err)
		os.Exit(1)
	}
	exe, _ := os.Executable()
	a := agent.New(store, exe)
	a.OnStatus = func(s agent.Status) { fmt.Fprintln(os.Stderr, "[status]", s.Tooltip()) }
	sess := newSession(a, a.RemoteParams, a.SetRemoteStatus)
	a.OnRemoteChange = sess.Reload
	srv, err := settings.Start(a)
	if err != nil {
		fmt.Fprintln(os.Stderr, "droplet:", err)
		os.Exit(1)
	}
	defer srv.Close()
	srv.Publish()
	defer settings.Unpublish()
	fmt.Fprintln(os.Stderr, "settings:", srv.URL())

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	liveDone := make(chan struct{})
	go func() { sess.Run(ctx); close(liveDone) }()
	a.Run(ctx)
	<-liveDone
}
