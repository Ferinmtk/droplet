// Package agent is the companion's brain: it polls the hub, downloads and
// announces what arrives, rings, sends, and applies settings. It's platform
// neutral; the tray (Windows) or a console (elsewhere) sits on top of it.
package agent

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/download"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/poll"
	"github.com/Ferinmtk/droplet/windows/internal/remote"
	"github.com/Ferinmtk/droplet/windows/internal/sound"
)

const (
	pollEvery     = 5 * time.Second
	pollRinging   = 1500 * time.Millisecond
	maxBackoff    = time.Minute
	ringLimit     = 60 * time.Second
	ringRecheck   = 5 * time.Minute // how often to re-ask a hub without ringing
	bigUploadSize = 20 << 20        // above this, a "sending…" toast comes first
)

// notify shows a toast; tests swap it to capture notifications.
var notify = platform.Notify

// Status is what the tray shows.
type Status struct {
	Configured bool   // registered with a hub
	Polled     bool   // at least one poll has finished
	Connected  bool   // last poll worked
	HubName    string // e.g. "t15"
	HubURL     string
	Problem    string // why not connected, in words
	Devices    []hub.Device
	Ringing    bool
	RingFrom   string
	Paused     bool
	// Remote is the live connection (remote control), when there is one.
	Remote remote.Status
}

// Tooltip is the one-line state for the tray icon.
func (s Status) Tooltip() string {
	switch {
	case !s.Configured:
		return "droplet — not set up yet (open Settings)"
	case s.Ringing:
		return "droplet — " + s.RingFrom + " is ringing this PC"
	case !s.Polled:
		return "droplet — connecting to " + s.HubName + "…"
	case s.Connected && s.Remote.Controller != "":
		return "droplet — being controlled by " + s.Remote.Controller
	case s.Connected && s.Paused:
		return "droplet — connected to " + s.HubName + " (notifications paused)"
	case s.Connected:
		return "droplet — connected to " + s.HubName
	case s.Problem != "":
		return "droplet — " + s.Problem
	default:
		return "droplet — can't reach " + s.HubName
	}
}

// Agent runs the companion. Create with New, then Run.
type Agent struct {
	Store *config.Store
	Exe   string // path of droplet.exe, for shortcuts and autostart

	// OnStatus is called (from the poll goroutine) whenever Status changes.
	OnStatus func(Status)
	// OnRemoteChange is called when anything the live connection depends
	// on changes (a new hub or token, a remote-control switch), so it can
	// reconnect. Set it before Run.
	OnRemoteChange func()

	mu            sync.Mutex
	status        Status
	client        *hub.Client
	wake          chan struct{}
	ringID        string // ring currently sounding
	ringStarted   time.Time
	stoppedRingID string // ring we silenced; ignore it until the hub forgets it
	ringChecked   time.Time
	ringSupported bool
	removedWarned bool
	lastDests     []hub.Device
	wav           []byte
}

// New makes an agent over a config store.
func New(store *config.Store, exe string) *Agent {
	return &Agent{Store: store, Exe: exe, wake: make(chan struct{}, 1), ringSupported: true, wav: sound.RingWAV()}
}

// Client returns a hub client for the current config.
func (a *Agent) Client() (*hub.Client, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.client != nil {
		return a.client, nil
	}
	cfg := a.Store.Get()
	c, err := hub.New(cfg.HubURL, cfg.DeviceToken, cfg.Session)
	if err != nil {
		return nil, err
	}
	a.client = c
	return c, nil
}

// Status returns the current status.
func (a *Agent) Status() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.status
}

func (a *Agent) setStatus(fn func(s *Status)) {
	a.mu.Lock()
	before := fmt.Sprint(a.status)
	fn(&a.status)
	after := a.status
	changed := fmt.Sprint(after) != before
	a.mu.Unlock()
	if changed && a.OnStatus != nil {
		a.OnStatus(after)
	}
}

// Poke makes the poll loop run now (after settings change, a send, ...).
func (a *Agent) Poke() {
	select {
	case a.wake <- struct{}{}:
	default:
	}
}

// Reset drops the cached client so the next poll uses the new config.
func (a *Agent) Reset() {
	a.mu.Lock()
	a.client = nil
	a.removedWarned = false
	a.ringSupported = true
	a.ringChecked = time.Time{}
	a.status.Polled, a.status.Connected = false, false
	a.mu.Unlock()
	a.Poke()
	a.remoteChanged()
}

func (a *Agent) remoteChanged() {
	if a.OnRemoteChange != nil {
		a.OnRemoteChange()
	}
}

// Run polls until ctx ends.
func (a *Agent) Run(ctx context.Context) {
	backoff := pollEvery
	for {
		wait := pollEvery
		if err := a.tick(ctx); err != nil {
			wait = backoff
			if backoff *= 2; backoff > maxBackoff {
				backoff = maxBackoff
			}
		} else {
			backoff = pollEvery
		}
		if a.Status().Ringing {
			wait = pollRinging
		}
		t := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			t.Stop()
			a.silence()
			return
		case <-a.wake:
			t.Stop()
		case <-t.C:
		}
	}
}

// tick is one poll of the hub.
func (a *Agent) tick(ctx context.Context) error {
	cfg := a.Store.Get()
	c, err := a.Client()
	if err != nil {
		a.setStatus(func(s *Status) { s.Configured = false; s.Problem = err.Error() })
		return nil
	}
	a.setStatus(func(s *Status) {
		s.Configured = cfg.Registered()
		s.HubName = c.HubName()
		s.HubURL = cfg.HubURL
		s.Paused = cfg.Paused
	})
	if !cfg.Registered() {
		return nil // nothing to poll until Settings is saved
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	files, err := c.Files(ctx)
	if err != nil {
		a.setStatus(func(s *Status) { s.Polled, s.Connected, s.Problem = true, false, describe(err, c.HubName()) })
		return err
	}
	if files.Self() == nil {
		// the hub no longer knows this device (removed from the Devices list)
		a.setStatus(func(s *Status) {
			s.Polled, s.Connected = true, true
			s.Problem = ""
			s.Configured = false
			s.Devices = files.Devices
		})
		a.mu.Lock()
		warn := !a.removedWarned
		a.removedWarned = true
		a.mu.Unlock()
		if warn {
			notify(platform.Notification{
				Title: "This PC was removed from droplet",
				Body:  "Open droplet's Settings to add it again.",
				Tag:   "removed",
			})
		}
		return nil
	}
	a.setStatus(func(s *Status) {
		s.Polled, s.Connected, s.Problem, s.Configured = true, true, "", true
		s.Devices = files.Devices
	})
	a.syncDestinations(files.Devices)

	a.handleInbox(ctx, c, cfg, files)
	a.handleChat(ctx, c, cfg, files)
	a.handleRing(ctx, c, cfg)
	return nil
}

// describe turns a poll error into tooltip words.
func describe(err error, hubName string) string {
	switch {
	case errors.Is(err, hub.ErrPINRequired):
		return hubName + " wants a PIN (open Settings)"
	case errors.Is(err, context.DeadlineExceeded):
		return "can't reach " + hubName + " (timed out)"
	}
	return "can't reach " + hubName
}

// syncDestinations refreshes Explorer's Send To entries when the device list changes.
func (a *Agent) syncDestinations(devices []hub.Device) {
	a.mu.Lock()
	same := poll.SameDevices(a.lastDests, devices)
	if !same {
		a.lastDests = append([]hub.Device(nil), devices...)
	}
	a.mu.Unlock()
	if same {
		return
	}
	if err := platform.SyncSendTo(a.Exe, Destinations(devices)); err != nil {
		log.Printf("send-to shortcuts: %v", err)
	}
}

// Destinations are where this PC can send: the hub, then every other device.
func Destinations(devices []hub.Device) []platform.Dest {
	out := []platform.Dest{{ID: "hub", Name: "Hub"}}
	for _, d := range devices {
		if !d.Self {
			out = append(out, platform.Dest{ID: d.ID, Name: d.Name})
		}
	}
	return out
}

// --- inbox ----------------------------------------------------------------------

func (a *Agent) handleInbox(ctx context.Context, c *hub.Client, cfg config.Config, files *hub.Files) {
	now := time.Now()
	fresh, seen := poll.Inbox(files.Inbox, cfg.InboxSeen, now)

	saved := map[string]string{} // inbox key -> local path
	if cfg.AutoDownload {
		for _, f := range files.Inbox {
			if !poll.Complete(f, now) {
				continue
			}
			p, err := download.Save(ctx, c.Download, cfg.DownloadDir, f.Name)
			if err != nil {
				log.Printf("download %s: %v", f.Name, err)
				continue
			}
			saved[poll.InboxKey(f)] = p
			// only once it's safely on disk
			if err := c.DeleteInbox(ctx, f.Name); err != nil && !errors.Is(err, hub.ErrNotFound) {
				log.Printf("delete %s from hub inbox: %v", f.Name, err)
			}
		}
	}

	if !equalStrings(seen, cfg.InboxSeen) {
		if err := a.Store.Update(func(c *config.Config) { c.InboxSeen = seen }); err != nil {
			log.Printf("save config: %v", err)
		}
	}
	if len(fresh) == 0 || cfg.Paused || !cfg.NotifyFiles {
		return
	}
	// one toast per sender
	bySender := map[string][]hub.File{}
	var order []string
	for _, f := range fresh {
		from := f.From
		if from == "" {
			from = "Someone"
		}
		if _, ok := bySender[from]; !ok {
			order = append(order, from)
		}
		bySender[from] = append(bySender[from], f)
	}
	for _, from := range order {
		notify(fileToast(from, bySender[from], saved, cfg, c))
	}
}

func fileToast(from string, fs []hub.File, saved map[string]string, cfg config.Config, c *hub.Client) platform.Notification {
	n := platform.Notification{Tag: "files-" + fmt.Sprint(time.Now().UnixNano())}
	if len(fs) == 1 {
		n.Title = from + " sent " + fs[0].Name
	} else {
		var names []string
		for i, f := range fs {
			if i == 3 {
				names = append(names, fmt.Sprintf("+%d more", len(fs)-3))
				break
			}
			names = append(names, f.Name)
		}
		n.Title = fmt.Sprintf("%s sent %d files", from, len(fs))
		n.Body = strings.Join(names, ", ")
	}
	var paths []string
	for _, f := range fs {
		if p, ok := saved[poll.InboxKey(f)]; ok {
			paths = append(paths, p)
		}
	}
	switch {
	case len(paths) == 1 && len(fs) == 1:
		n.Body = fmt.Sprintf("%s · saved to %s", humanSize(fs[0].Size), shortDir(cfg.DownloadDir))
		n.Click = &platform.Action{Kind: platform.ActShowFile, Arg: paths[0]}
		n.Buttons = []platform.Action{
			{Label: "Open", Kind: platform.ActOpenFile, Arg: paths[0]},
			{Label: "Show in folder", Kind: platform.ActShowFile, Arg: paths[0]},
		}
	case len(paths) > 0:
		n.Body += "\nSaved to " + shortDir(cfg.DownloadDir)
		n.Click = &platform.Action{Kind: platform.ActOpenFolder, Arg: cfg.DownloadDir}
		n.Buttons = []platform.Action{{Label: "Open folder", Kind: platform.ActOpenFolder, Arg: cfg.DownloadDir}}
	default:
		if n.Body == "" {
			n.Body = humanSize(fs[0].Size)
		}
		n.Body += " · in your droplet inbox"
		n.Click = &platform.Action{Kind: platform.ActOpenURL, Arg: c.URL("/#inbox")}
	}
	return n
}

func shortDir(dir string) string {
	if home, err := os.UserHomeDir(); err == nil {
		if rel, err := filepath.Rel(home, dir); err == nil && !strings.HasPrefix(rel, "..") {
			return rel
		}
	}
	return dir
}

func humanSize(n int64) string {
	units := []string{"B", "KB", "MB", "GB", "TB"}
	f := float64(n)
	i := 0
	for f >= 1024 && i < len(units)-1 {
		f /= 1024
		i++
	}
	if i == 0 {
		return fmt.Sprintf("%d B", n)
	}
	return fmt.Sprintf("%.1f %s", f, units[i])
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// --- chat -------------------------------------------------------------------------

var bareURL = regexp.MustCompile(`^https?://\S+$`)

func (a *Agent) handleChat(ctx context.Context, c *hub.Client, cfg config.Config, files *hub.Files) {
	// reading a thread marks it read on the hub, so leave the unread badges
	// alone for the web app when the tray isn't going to show them anyway
	if cfg.Paused || !cfg.NotifyMessages {
		return
	}
	names := map[string]string{}
	for _, d := range files.Devices {
		names[d.ID] = d.Name
	}
	for from, count := range files.Unread {
		if count <= 0 {
			continue
		}
		thread, err := c.Chat(ctx, from)
		if err != nil {
			log.Printf("chat with %s: %v", from, err)
			continue
		}
		fresh, seen := poll.Messages(thread, from, count, cfg.ChatSeen[from])
		if seen != cfg.ChatSeen[from] {
			if err := a.Store.Update(func(c *config.Config) { c.ChatSeen[from] = seen }); err != nil {
				log.Printf("save config: %v", err)
			}
		}
		if len(fresh) == 0 {
			continue
		}
		name := names[from]
		if name == "" {
			name = "Someone"
		}
		last := fresh[len(fresh)-1]
		body := last.Text
		if len(fresh) > 1 {
			body = fmt.Sprintf("%s\n(+%d earlier)", body, len(fresh)-1)
		}
		chat := platform.Action{Label: "Open chat", Kind: platform.ActOpenURL, Arg: c.URL("/#chat-" + from)}
		n := platform.Notification{Title: name, Body: body, Tag: "chat-" + from, Click: &chat}
		if u := strings.TrimSpace(last.Text); bareURL.MatchString(u) {
			// a bare link opens straight away, as it does on the phone
			n.Click = &platform.Action{Kind: platform.ActOpenURL, Arg: u}
			n.Buttons = []platform.Action{{Label: "Open link", Kind: platform.ActOpenURL, Arg: u}, chat}
		}
		notify(n)
	}
}

// --- ringing ----------------------------------------------------------------------

func (a *Agent) handleRing(ctx context.Context, c *hub.Client, cfg config.Config) {
	a.mu.Lock()
	skip := !a.ringSupported && time.Since(a.ringChecked) < ringRecheck
	a.mu.Unlock()
	if skip {
		return
	}
	r, err := c.ActiveRing(ctx)
	a.mu.Lock()
	a.ringChecked = time.Now()
	a.ringSupported = !errors.Is(err, hub.ErrRingUnsupported)
	a.mu.Unlock()
	if err != nil {
		if a.ringSupported {
			log.Printf("ring check: %v", err)
		}
		return
	}

	a.mu.Lock()
	current, started, stopped := a.ringID, a.ringStarted, a.stoppedRingID
	a.mu.Unlock()

	switch {
	case current != "" && (r == nil || r.ID != current):
		a.silence() // stopped elsewhere (or replaced; picked up next poll)
	case current != "" && time.Since(started) > ringLimit:
		a.StopRing() // rings give up after a minute, like a phone
	case current == "" && r != nil && r.ID != stopped:
		a.startRing(r, cfg)
	}
	if r == nil {
		a.mu.Lock()
		a.stoppedRingID = ""
		a.mu.Unlock()
	}
}

func (a *Agent) startRing(r *hub.Ring, cfg config.Config) {
	from := r.From
	if from == "" {
		from = "Someone"
	}
	a.mu.Lock()
	a.ringID, a.ringStarted = r.ID, time.Now()
	a.mu.Unlock()
	if cfg.RingSound {
		if err := platform.StartRingSound(a.wav); err != nil {
			log.Printf("ring sound: %v", err)
		}
	}
	notify(platform.Notification{
		Title:   "📣 " + from + " is ringing this PC",
		Body:    "Found it? Press Stop.",
		Tag:     "ring",
		Urgent:  true,
		Click:   &platform.Action{Kind: platform.ActStopRing},
		Buttons: []platform.Action{{Label: "Stop", Kind: platform.ActStopRing}},
	})
	a.setStatus(func(s *Status) { s.Ringing, s.RingFrom = true, from })
}

// silence stops the sound and the ringing state locally.
func (a *Agent) silence() {
	a.mu.Lock()
	was := a.ringID
	a.ringID = ""
	a.mu.Unlock()
	if was == "" {
		return
	}
	platform.StopRingSound()
	platform.ClearNotification("ring")
	a.setStatus(func(s *Status) { s.Ringing, s.RingFrom = false, "" })
}

// StopRing silences this PC and tells the hub the ring is answered.
func (a *Agent) StopRing() {
	a.mu.Lock()
	if a.ringID != "" {
		a.stoppedRingID = a.ringID
	}
	a.mu.Unlock()
	a.silence()
	c, err := a.Client()
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := c.StopRing(ctx); err != nil && !errors.Is(err, hub.ErrRingUnsupported) {
		log.Printf("stop ring: %v", err)
	}
}

// --- user actions ---------------------------------------------------------------------

// SetPaused turns file and message notifications off or on.
func (a *Agent) SetPaused(p bool) error {
	err := a.Store.Update(func(c *config.Config) { c.Paused = p })
	a.setStatus(func(s *Status) { s.Paused = p })
	return err
}

// Ring rings a device (by id) or the hub ("hub"), reporting the outcome as a toast.
func (a *Agent) Ring(target, name string) {
	c, err := a.Client()
	if err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		err = c.RingDevice(ctx, target)
	}
	if err != nil {
		notify(platform.Notification{Title: "Couldn't ring " + name, Body: err.Error(), Tag: "ring-out"})
		return
	}
	notify(platform.Notification{Title: "Ringing " + name, Body: "It stops by itself after a minute.", Tag: "ring-out"})
}

// SendFiles uploads files to a destination, with toasts for progress and the result.
func (a *Agent) SendFiles(to, name string, paths []string) error {
	c, err := a.Client()
	if err != nil {
		return err
	}
	return SendFiles(c, to, name, paths, nil, true)
}

// SendFiles is the upload used by the tray, Explorer's Send To and the CLI.
// progress, if set, also gets byte counts (for a console); toasts reports
// progress and the outcome as notifications.
func SendFiles(c *hub.Client, to, name string, paths []string, progress hub.Progress, toasts bool) error {
	toast := func(n platform.Notification) {
		if toasts {
			notify(n)
		}
	}
	var total int64
	for _, p := range paths {
		if st, err := os.Stat(p); err == nil {
			total += st.Size()
		}
	}
	what := filepath.Base(paths[0])
	if len(paths) > 1 {
		what = fmt.Sprintf("%d files", len(paths))
	}
	tag := fmt.Sprintf("send-%d", time.Now().UnixNano())
	if total > bigUploadSize {
		toast(platform.Notification{Title: "Sending " + what + " to " + name + "…", Body: humanSize(total), Tag: tag})
	}
	_, err := c.Upload(context.Background(), to, paths, progress)
	if err != nil {
		toast(platform.Notification{Title: "Couldn't send " + what + " to " + name, Body: err.Error(), Tag: tag})
		return err
	}
	n := platform.Notification{Title: "Sent " + what + " to " + name, Body: humanSize(total), Tag: tag}
	if to == "hub" {
		n.Click = &platform.Action{Kind: platform.ActOpenURL, Arg: c.URL("/")}
	}
	toast(n)
	return nil
}

// SendClipboard sends whatever's on the clipboard: copied files, an image, or text.
func (a *Agent) SendClipboard(to, name string) error {
	clip, err := platform.ReadClipboard()
	if err == nil && clip.Text == "" && len(clip.Files) == 0 && clip.PNG == nil {
		err = errors.New("the clipboard has no text, image or files")
	}
	if err != nil {
		notify(platform.Notification{Title: "Nothing sent", Body: err.Error(), Tag: "clip"})
		return err
	}
	c, err := a.Client()
	if err != nil {
		return err
	}
	switch {
	case len(clip.Files) > 0:
		return SendFiles(c, to, name, clip.Files, nil, true)
	case clip.PNG != nil:
		dir, err := os.MkdirTemp("", "droplet-clip-")
		if err != nil {
			return err
		}
		defer os.RemoveAll(dir)
		p := filepath.Join(dir, "clipboard-"+time.Now().Format("20060102-150405")+".png")
		if err := os.WriteFile(p, clip.PNG, 0o600); err != nil {
			return err
		}
		return SendFiles(c, to, name, []string{p}, nil, true)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := c.SendText(ctx, to, clip.Text); err != nil {
		notify(platform.Notification{Title: "Couldn't send the clipboard to " + name, Body: err.Error(), Tag: "clip"})
		return err
	}
	preview := strings.Join(strings.Fields(clip.Text), " ")
	notify(platform.Notification{Title: "Sent the clipboard to " + name, Body: preview, Tag: "clip"})
	return nil
}
