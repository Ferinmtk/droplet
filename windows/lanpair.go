package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/pairing"
)

// cmdHubs lists the hubs on this network.
func cmdHubs() error {
	store, err := openStore()
	if err != nil {
		return err
	}
	exe, _ := os.Executable()
	a := agent.New(store, exe)
	found, err := a.Discover(context.Background())
	if err != nil {
		return err
	}
	if len(found) == 0 {
		fmt.Println("no droplet hubs found on this network")
		return nil
	}
	for _, f := range found {
		note := ""
		switch {
		case f.Changed:
			note = "  [paired, but its certificate changed]"
		case f.Paired:
			note = "  [paired]"
		}
		fmt.Printf("%-16s id %s  %s  cert %s…%s\n", f.Name, f.ID, strings.Join(f.Addrs, ", "), f.Fingerprint, note)
		if f.Tailnet != "" {
			fmt.Printf("%-16s tailnet %s\n", "", f.Tailnet)
		}
	}
	return nil
}

// cmdJoin asks a hub on this network to let this PC in, and waits.
func cmdJoin(args []string) error {
	fs := newFlags("join")
	name := fs.String("name", "", "")
	pin := fs.String("pin", "", "")
	var query string
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		query, args = args[0], args[1:]
	}
	if err := fs.Parse(args); err != nil {
		return err
	}
	if query == "" && fs.NArg() == 1 {
		query = fs.Arg(0)
	}
	store, err := openStore()
	if err != nil {
		return err
	}
	exe, _ := os.Executable()
	a := agent.New(store, exe)
	if *name == "" {
		*name = a.CurrentSettings().Name
	}
	if *name == "" {
		if h, err := os.Hostname(); err == nil {
			*name = strings.ToLower(h)
		}
	}
	found, err := a.Discover(context.Background())
	if err != nil {
		return err
	}
	target, err := agent.FindHub(found, query)
	if err != nil {
		return err
	}
	fmt.Printf("joining %s (id %s) as %q…\n", target.Name, target.ID, *name)
	res, err := a.Join(context.Background(), target.ID, *name, *pin, false)
	if err != nil {
		return err
	}
	_ = postRunningReload()
	if !res.Pending {
		fmt.Printf("done: this PC is %q on %s\n", res.Name, res.Hub)
		return nil
	}
	fmt.Printf("\n  On one of your devices, allow %q, and check the code matches:  %s\n\n", res.Name, res.Code)
	fmt.Println("waiting for an answer (Ctrl+C stops waiting; the request stays open, and droplet keeps checking)")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	ctx, cancel := context.WithTimeout(ctx, 30*time.Minute)
	defer cancel()
	st, err := a.WaitForPairing(ctx, func(st pairing.State, code string) {
		if st == pairing.Pending && code != res.Code && code != "" {
			fmt.Println("the code is now", code)
		}
	})
	_ = postRunningReload()
	switch {
	case st == pairing.Approved:
		fmt.Printf("let in: this PC is %q on %s\n", res.Name, res.Hub)
		return nil
	case st == pairing.Declined:
		return errors.New("the request was declined, or it expired")
	}
	return err
}
