// Package tray is droplet's notification-area icon and menu (Windows).
package tray

import (
	"fmt"
	"log"
	"os"
	"sync"

	"fyne.io/systray"

	"github.com/Ferinmtk/droplet/windows/assets"
	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
)

// poolSize is how many devices each submenu can list. systray menus can't
// be rebuilt cheaply, so each submenu keeps a pool of hidden items that are
// relabelled as devices come and go.
const poolSize = 24

// Options wires the tray to the rest of the app.
type Options struct {
	Agent        *agent.Agent
	OpenSettings func()
	OnQuit       func()
	// OnReady runs once the menu exists (start polling here, so the first
	// status lands in the menu)
	OnReady func()
}

type pool struct {
	parent *systray.MenuItem
	items  []*systray.MenuItem
	mu     sync.Mutex
	dests  []platform.Dest
}

func newPool(parent *systray.MenuItem, onClick func(d platform.Dest)) *pool {
	p := &pool{parent: parent}
	for i := 0; i < poolSize; i++ {
		it := parent.AddSubMenuItem("", "")
		it.Hide()
		p.items = append(p.items, it)
		go func(i int, it *systray.MenuItem) {
			for range it.ClickedCh {
				p.mu.Lock()
				var d *platform.Dest
				if i < len(p.dests) {
					d = &p.dests[i]
				}
				p.mu.Unlock()
				if d != nil {
					go onClick(*d)
				}
			}
		}(i, it)
	}
	return p
}

func (p *pool) set(dests []platform.Dest, label func(platform.Dest) string) {
	if len(dests) > poolSize {
		dests = dests[:poolSize]
	}
	p.mu.Lock()
	p.dests = dests
	p.mu.Unlock()
	for i, it := range p.items {
		if i < len(dests) {
			it.SetTitle(label(dests[i]))
			it.Show()
		} else {
			it.Hide()
		}
	}
}

// Run shows the tray icon and blocks until Quit. It must be called from main.
func Run(o Options) {
	systray.Run(func() { ready(o) }, func() {})
}

// Quit removes the tray icon and makes Run return.
func Quit() { systray.Quit() }

func ready(o Options) {
	a := o.Agent
	systray.SetTitle("droplet")
	systray.SetTooltip("droplet — starting…")

	header := systray.AddMenuItem("droplet", "")
	header.Disable()
	stopRing := systray.AddMenuItem("🔕 Stop ringing", "Silence this PC")
	stopRing.Hide()
	systray.AddSeparator()
	openHub := systray.AddMenuItem("Open droplet", "Open the hub in your browser")
	sendFiles := systray.AddMenuItem("Send files to", "")
	sendClip := systray.AddMenuItem("Send clipboard to", "Text, an image or copied files")
	ring := systray.AddMenuItem("Ring", "Make a device ring so you can find it")
	systray.AddSeparator()
	downloads := systray.AddMenuItem("Open downloads folder", "")
	pause := systray.AddMenuItemCheckbox("Pause notifications", "Files and messages still arrive; rings still ring", a.Store.Get().Paused)
	pauseRemote := systray.AddMenuItemCheckbox("Pause remote control", "Other devices can't control this PC until you switch this off", a.Store.Get().RemotePaused)
	settingsItem := systray.AddMenuItem("Settings…", "")
	systray.AddSeparator()
	quit := systray.AddMenuItem("Quit droplet", "")

	filesPool := newPool(sendFiles, func(d platform.Dest) {
		paths, err := platform.PickFiles("Send to " + d.Name + " with droplet")
		if err != nil {
			platform.Notify(platform.Notification{Title: "Couldn't open the file picker", Body: err.Error()})
			return
		}
		if len(paths) > 0 {
			a.SendFiles(d.ID, d.Name, paths)
		}
	})
	clipPool := newPool(sendClip, func(d platform.Dest) { a.SendClipboard(d.ID, d.Name) })
	ringPool := newPool(ring, func(d platform.Dest) { a.Ring(d.ID, d.Name) })

	var mu sync.Mutex
	iconState := "" // "on" or "dim": only swap the icon when it changes
	apply := func(s agent.Status) {
		mu.Lock()
		defer mu.Unlock()
		systray.SetTooltip(s.Tooltip())
		header.SetTitle(s.Tooltip())
		icon, state := assets.Icon, "on"
		switch {
		case !s.Connected || !s.Configured:
			icon, state = assets.IconDim, "dim"
		case s.Remote.Active:
			icon, state = assets.IconLive, "live" // remote input just now
		}
		if state != iconState {
			systray.SetIcon(icon)
			iconState = state
		}
		if s.Ringing {
			stopRing.SetTitle(fmt.Sprintf("🔕 Stop ringing (%s)", s.RingFrom))
			stopRing.Show()
		} else {
			stopRing.Hide()
		}
		if s.Paused {
			pause.Check()
		} else {
			pause.Uncheck()
		}
		if a.Store.Get().RemotePaused {
			pauseRemote.Check()
		} else {
			pauseRemote.Uncheck()
		}
		dests := agent.Destinations(s.Devices)
		ringDests := make([]platform.Dest, len(dests))
		copy(ringDests, dests)
		ringDests[0].Name = "The hub"
		plain := func(d platform.Dest) string { return d.Name }
		online := func(d platform.Dest) string {
			for _, dev := range s.Devices {
				if dev.ID == d.ID && !dev.Online {
					return d.Name + "  (offline)"
				}
			}
			return d.Name
		}
		filesPool.set(dests, plain)
		clipPool.set(dests, plain)
		ringPool.set(ringDests, online)
		for _, it := range []*systray.MenuItem{sendFiles, sendClip, ring} {
			if s.Configured {
				it.Enable()
			} else {
				it.Disable()
			}
		}
	}
	a.OnStatus = apply
	apply(a.Status())
	if o.OnReady != nil {
		o.OnReady()
	}

	go func() {
		for {
			select {
			case <-openHub.ClickedCh:
				cfg := a.Store.Get()
				if err := platform.OpenURL(cfg.HubURL); err != nil {
					log.Printf("open hub: %v", err)
				}
			case <-stopRing.ClickedCh:
				go a.StopRing()
			case <-downloads.ClickedCh:
				dir := a.Store.Get().DownloadDir
				ensureDir(dir)
				platform.OpenPath(dir)
			case <-pause.ClickedCh:
				p := !pause.Checked()
				if err := a.SetPaused(p); err != nil {
					log.Printf("pause: %v", err)
				}
				if p {
					pause.Check()
				} else {
					pause.Uncheck()
				}
			case <-pauseRemote.ClickedCh:
				p := !pauseRemote.Checked()
				if err := a.SetRemotePaused(p); err != nil {
					log.Printf("pause remote control: %v", err)
				}
				if p {
					pauseRemote.Check()
				} else {
					pauseRemote.Uncheck()
				}
			case <-settingsItem.ClickedCh:
				o.OpenSettings()
			case <-quit.ClickedCh:
				if o.OnQuit != nil {
					o.OnQuit()
				}
				systray.Quit()
				return
			}
		}
	}()
}

func ensureDir(dir string) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Printf("downloads folder: %v", err)
	}
}
