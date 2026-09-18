// Command droplet is the Windows companion for a droplet hub: a tray app
// that receives files, messages and rings in the background, plus a small CLI.
//
//	droplet                      start the tray app (or open Settings if it's running)
//	droplet send --to <dest> <files…>
//	droplet text --to <device> <message…>
//	droplet ring <device|hub>
//	droplet status
//	droplet hubs
//	droplet join [<hub>] --name <name> [--pin <pin>]
//	droplet setup --hub <url> --name <name> [--pin <pin>]
//	droplet link --code <code> [--hub <url> | --lan <hub>] [--pin <pin>]
//	droplet live [--caps input,media,…]
//	droplet settings | stop-ring | uninstall | version
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/settings"
)

// version is set at build time (-ldflags "-X main.version=…").
var version = "dev"

const usage = `droplet — Windows companion for a droplet hub

Usage:
  droplet                               start in the tray (or open Settings if already running)
  droplet send --to <dest> <file>...    send files; dest is a device name or id, or "hub"
  droplet text --to <device> <message>  send a chat message ("hub" saves a text file there)
  droplet ring <device|hub>             make a device (or the hub) ring
  droplet status                        show the hub, how it's reached, and the devices
  droplet hubs                          list the droplet hubs on this network
  droplet join [<hub>] --name <n>       ask a hub on this network to let this PC in, and wait for
                                        the answer [--pin <pin>]; <hub> is a name or id from "hubs"
  droplet setup --hub <url> --name <n>  register this PC over the hub's address (e.g. its tailnet URL)
                                        [--pin <pin>]
  droplet link --code <123456>          join this PC's browser as one device, with the code from
                                        "Set up remote control of this device"
                                        [--hub <url> | --lan <hub>] [--pin <pin>]
  droplet live [--caps input,media]     run only the remote-control connection, with a log (for testing)
  droplet settings                      open the settings page
  droplet stop-ring                     silence this PC
  droplet uninstall                     remove autostart, Send To entries and the Start menu entry
  droplet version
`

func main() {
	args := os.Args[1:]
	if len(args) == 0 {
		runApp()
		return
	}
	// notification buttons arrive as droplet:… links (see platform.ActionURL)
	if strings.HasPrefix(args[0], platform.Scheme+":") {
		handleAction(args[0])
		return
	}
	console := platform.AttachConsole()
	err := runCLI(args, console)
	if err != nil {
		if console {
			fmt.Fprintln(os.Stderr, "droplet:", err)
		} else {
			// launched from Explorer (Send To) with nowhere to print
			platform.Notify(platform.Notification{Title: "droplet", Body: err.Error()})
			platform.FlushNotifications(15 * time.Second)
		}
		os.Exit(1)
	}
}

func openStore() (*config.Store, error) {
	s, err := config.Open()
	if err != nil {
		return nil, fmt.Errorf("reading settings: %w", err)
	}
	return s, nil
}

// client builds a hub client from the saved settings, along the best route
// (the LAN when the hub is there, else the tailnet).
func client(store *config.Store) (*agent.Agent, *hub.Client, error) {
	cfg := store.Get()
	if cfg.Pending() {
		return nil, nil, fmt.Errorf("this PC is waiting to be let in to %s (code %s)", agent.HubName(cfg), cfg.PairCode)
	}
	if !cfg.Registered() {
		return nil, nil, errors.New("this PC isn't set up yet: run droplet (or droplet join) first")
	}
	exe, _ := os.Executable()
	a := agent.New(store, exe)
	c, err := a.Client()
	if err != nil {
		return a, nil, fmt.Errorf("can't reach %s: %w", agent.HubName(cfg), err)
	}
	return a, c, nil
}

func runCLI(args []string, console bool) error {
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "help", "-h", "--help", "/?":
		fmt.Print(usage)
		return nil
	case "version", "--version":
		fmt.Println("droplet", version)
		return nil
	case "send":
		return cmdSend(rest, console)
	case "text":
		return cmdText(rest)
	case "ring":
		return cmdRing(rest)
	case "status":
		return cmdStatus()
	case "hubs":
		return cmdHubs()
	case "join":
		return cmdJoin(rest)
	case "setup":
		return cmdSetup(rest)
	case "link":
		return cmdLink(rest)
	case "live":
		return cmdLive(rest)
	case "settings":
		if u, ok := settings.RunningURL(); ok {
			return platform.OpenURL(u)
		}
		runApp()
		return nil
	case "stop-ring":
		return stopRing()
	case "uninstall":
		if err := platform.Unregister(); err != nil {
			return err
		}
		// and don't put them back on the next start: they need a fresh yes in Settings
		if store, err := openStore(); err == nil && store.Exists() {
			if err := store.Update(func(c *config.Config) { c.Autostart, c.SendTo = false, false }); err == nil {
				_ = postRunningReload()
			}
		}
		dir, _ := config.Dir()
		fmt.Println("Removed autostart, Send To entries, the Start menu entry and the droplet: link handler.")
		fmt.Println("Settings are kept in", dir, "— delete that folder to forget this PC.")
		return nil
	}
	fmt.Fprint(os.Stderr, usage)
	return fmt.Errorf("unknown command %q", cmd)
}

func newFlags(name string) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	return fs
}

// resolve turns a destination typed by a person into a device id.
func resolve(ctx context.Context, c *hub.Client, target string) (string, string, error) {
	if strings.EqualFold(target, "hub") {
		return "hub", "the hub", nil
	}
	files, err := c.Files(ctx)
	if err != nil {
		return "", "", err
	}
	return hub.Resolve(target, files.Devices)
}

func cmdSend(args []string, console bool) error {
	fs := newFlags("send")
	to := fs.String("to", "", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	paths := fs.Args()
	if *to == "" || len(paths) == 0 {
		return errors.New("usage: droplet send --to <device|hub> <file>...")
	}
	for i, p := range paths {
		if abs, err := filepath.Abs(p); err == nil {
			paths[i] = abs
		}
	}
	store, err := openStore()
	if err != nil {
		return err
	}
	a, c, err := client(store)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	id, name, err := resolve(ctx, c, *to)
	cancel()
	if err != nil {
		return err
	}
	var progress hub.Progress
	if console {
		last := time.Time{}
		progress = func(sent, total int64) {
			if time.Since(last) > 200*time.Millisecond || sent == total {
				last = time.Now()
				pct := 100
				if total > 0 {
					pct = int(sent * 100 / total)
				}
				fmt.Fprintf(os.Stderr, "\rsending to %s… %3d%%", name, pct)
			}
		}
	}
	// from Explorer there's no console, so the outcome comes as a toast
	err = a.SendFilesWith(c, id, name, paths, progress, !console)
	if console {
		fmt.Fprintln(os.Stderr)
	}
	platform.FlushNotifications(15 * time.Second) // don't exit before the toast is up
	if err != nil {
		if !console {
			os.Exit(1) // the error toast already said why
		}
		return err
	}
	if console {
		fmt.Printf("sent %d file(s) to %s\n", len(paths), name)
	}
	return nil
}

func cmdText(args []string) error {
	fs := newFlags("text")
	to := fs.String("to", "", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	msg := strings.Join(fs.Args(), " ")
	if *to == "" || strings.TrimSpace(msg) == "" {
		return errors.New(`usage: droplet text --to <device|hub> "message"`)
	}
	store, err := openStore()
	if err != nil {
		return err
	}
	_, c, err := client(store)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	id, name, err := resolve(ctx, c, *to)
	if err != nil {
		return err
	}
	if err := c.SendText(ctx, id, msg); err != nil {
		return err
	}
	fmt.Println("sent to", name)
	return nil
}

func cmdRing(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: droplet ring <device|hub>")
	}
	store, err := openStore()
	if err != nil {
		return err
	}
	_, c, err := client(store)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	id, name, err := resolve(ctx, c, args[0])
	if err != nil {
		return err
	}
	if err := c.RingDevice(ctx, id); err != nil {
		return err
	}
	fmt.Printf("ringing %s (it stops after a minute)\n", name)
	return nil
}

func cmdStatus() error {
	store, err := openStore()
	if err != nil {
		return err
	}
	cfg := store.Get()
	fmt.Println("settings:", store.Path)
	if h := cfg.Hub; h != nil {
		fmt.Printf("hub:      %s (id %s)\n", agent.HubName(cfg), h.ID)
		if h.Fingerprint != "" {
			how := "checked over the tailnet"
			if h.PinSource == config.PinFromLAN {
				how = "trusted when this PC paired on the LAN"
			}
			fmt.Printf("pinned:   %s… (%s)\n", h.Fingerprint[:16], how)
		}
		if len(h.LAN) > 0 {
			fmt.Println("LAN:     ", strings.Join(h.LAN, ", "), "(last seen)")
		}
	}
	if u := cfg.RemoteURL(); u != "" {
		fmt.Println("remote:  ", u)
	}
	if cfg.Pending() {
		fmt.Printf("this PC:  waiting to be let in as %q: allow it on one of your devices (code %s)\n", cfg.DeviceName, cfg.PairCode)
		return nil
	}
	if !cfg.Registered() {
		fmt.Println("this PC: not set up yet (run droplet, or droplet join)")
		return nil
	}
	a, c, err := client(store)
	if err != nil {
		if w := changedWarning(a, store); w != "" {
			fmt.Println(w)
		}
		return err
	}
	r, _ := a.Routes.Current()
	fmt.Printf("route:    %s (%s)\n", r.Label(), r.Base)
	if w := changedWarning(a, store); w != "" {
		fmt.Println(w)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	files, err := c.Files(ctx)
	if errors.Is(err, hub.ErrNotAllowed) {
		fmt.Println("this PC:  not let in (removed from the hub, or declined): pair it again with droplet join")
		return err
	}
	if err != nil {
		return fmt.Errorf("can't reach %s: %w", c.HubName(), err)
	}
	self := files.Self()
	if self == nil {
		fmt.Println("this PC: not known to the hub any more (removed?) — run droplet setup again")
		return errors.New("not registered")
	}
	fmt.Printf("this PC:  %s (id %s), connected\n", self.Name, self.ID)
	fmt.Printf("inbox:    %d file(s) waiting\n", len(files.Inbox))
	fmt.Println("devices:")
	for _, d := range files.Others() {
		state := "offline"
		if d.Online {
			state = "online"
		}
		unread := ""
		if n := files.Unread[d.ID]; n > 0 {
			unread = fmt.Sprintf(", %d unread", n)
		}
		fmt.Printf("  %-24s %s  (%s%s)\n", d.Name, d.ID, state, unread)
	}
	if len(files.Others()) == 0 {
		fmt.Println("  (none yet)")
	}
	_, err = c.ActiveRing(ctx)
	switch {
	case errors.Is(err, hub.ErrRingUnsupported):
		fmt.Println("ringing:  not supported by this hub")
	case err == nil:
		fmt.Println("ringing:  supported")
	}
	return nil
}

func cmdSetup(args []string) error {
	fs := newFlags("setup")
	hubURL := fs.String("hub", "", "")
	name := fs.String("name", "", "")
	pin := fs.String("pin", "", "")
	dir := fs.String("downloads", "", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	store, err := openStore()
	if err != nil {
		return err
	}
	exe, _ := os.Executable()
	a := agent.New(store, exe)
	s := a.CurrentSettings()
	if !store.Exists() {
		s.Autostart = false // the CLI shouldn't quietly add itself to startup
	}
	if *hubURL != "" {
		s.HubURL = *hubURL
	}
	if *name != "" {
		s.Name = *name
	}
	if *dir != "" {
		s.DownloadDir = *dir
	}
	s.PIN = *pin
	if s.Name == "" {
		p := a.ProbeHub(context.Background(), s.HubURL, s.PIN)
		s.Name = p.Name
	}
	if err := a.Configure(context.Background(), s); err != nil {
		return err
	}
	_ = postRunningReload()
	cfg := store.Get()
	fmt.Printf("this PC is %q (id %s) on %s\n", cfg.DeviceName, cfg.DeviceID, agent.HubName(cfg))
	if cfg.Pending() {
		fmt.Printf("waiting to be let in: on one of your devices, allow %q and check the code is %s\n", cfg.DeviceName, cfg.PairCode)
	}
	return nil
}

// changedWarning is a warning line when the hub's LAN identity changed.
func changedWarning(a *agent.Agent, store *config.Store) string {
	if a == nil {
		return ""
	}
	ch := a.Routes.Changed()
	if ch == nil {
		return ""
	}
	name := agent.HubName(store.Get())
	if ch.Verified {
		return "WARNING:  " + name + " has a new LAN certificate (confirmed over the tailnet). Re-pair in Settings to use it on Wi-Fi."
	}
	return "WARNING:  something at " + ch.Addr + " claims to be " + name + " with a different certificate; droplet won't use it. " +
		"If you reset the hub, re-pair in Settings."
}

func stopRing() error {
	// the running tray is the one making noise; ask it first
	if err := settings.PostRunning("api/stop-ring"); err == nil {
		return nil
	}
	store, err := openStore()
	if err != nil {
		return err
	}
	_, c, err := client(store)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return c.StopRing(ctx)
}

// handleAction runs a notification button's droplet: link in this new process.
func handleAction(raw string) {
	store, err := openStore()
	if err != nil {
		return
	}
	cfg := store.Get()
	act, ok := platform.ParseActionURL(raw, cfg.ActionKey)
	if !ok {
		return // not ours (or a web page trying the scheme): ignore
	}
	switch act.Kind {
	case platform.ActStopRing:
		_ = stopRing()
	case platform.ActOpenFolder:
		if inside(act.Arg, cfg.DownloadDir) {
			_ = platform.OpenPath(act.Arg)
		}
	case platform.ActShowFile:
		if inside(act.Arg, cfg.DownloadDir) {
			_ = platform.ShowInFolder(act.Arg)
		}
	case platform.ActOpenFile:
		if inside(act.Arg, cfg.DownloadDir) {
			_ = platform.OpenPath(act.Arg)
		}
	}
}

// inside reports whether p is dir or somewhere below it.
func inside(p, dir string) bool {
	rel, err := filepath.Rel(filepath.Clean(dir), filepath.Clean(p))
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}
