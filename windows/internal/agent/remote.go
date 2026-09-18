package agent

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/remote"
)

// RemoteSettings are the remote-control switches on the settings page.
type RemoteSettings struct {
	Input      bool `json:"input"`
	Media      bool `json:"media"`
	Lock       bool `json:"lock"`
	Screenshot bool `json:"screenshot"`
	Clipboard  bool `json:"clipboard"`
	Paused     bool `json:"paused"`
}

// CurrentRemote returns the saved remote-control switches.
func (a *Agent) CurrentRemote() RemoteSettings {
	c := a.Store.Get()
	return RemoteSettings{Input: c.RemoteInput, Media: c.RemoteMedia, Lock: c.RemoteLock,
		Screenshot: c.RemoteScreenshot, Clipboard: c.ClipboardSync, Paused: c.RemotePaused}
}

// SetRemote saves the remote-control switches and reconnects with them.
func (a *Agent) SetRemote(r RemoteSettings) error {
	err := a.Store.Update(func(c *config.Config) {
		c.RemoteInput, c.RemoteMedia, c.RemoteLock = r.Input, r.Media, r.Lock
		c.RemoteScreenshot, c.ClipboardSync, c.RemotePaused = r.Screenshot, r.Clipboard, r.Paused
	})
	a.remoteChanged()
	return err
}

// SetRemotePaused switches remote control off or on as a whole (the tray's
// "Pause remote control").
func (a *Agent) SetRemotePaused(p bool) error {
	err := a.Store.Update(func(c *config.Config) { c.RemotePaused = p })
	a.remoteChanged()
	return err
}

// RemoteParams is what the live connection needs: the saved settings, and
// the current route. With no route yet (or while waiting to be let in)
// there's nothing to connect to; a new route reloads the connection.
func (a *Agent) RemoteParams() remote.Params {
	c := a.Store.Get()
	token := c.DeviceToken
	if !c.Registered() {
		token = ""
	}
	p := remote.Params{
		Token: token, Session: c.Session, Name: c.DeviceName,
		Paused: c.RemotePaused,
		Caps: map[string]bool{
			remote.CapInput:      c.RemoteInput,
			remote.CapMedia:      c.RemoteMedia,
			remote.CapLock:       c.RemoteLock,
			remote.CapScreenshot: c.RemoteScreenshot,
			remote.CapClipboard:  c.ClipboardSync,
		},
	}
	if r, ok := a.Routes.Current(); ok {
		p.HubURL, p.Transport = r.Base, r.Transport()
	}
	return p
}

// SetRemoteStatus records the live connection's state for the tray.
func (a *Agent) SetRemoteStatus(r remote.Status) {
	a.setStatus(func(s *Status) { s.Remote = r })
}

// LinkResult is what linking did.
type LinkResult struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// Replaced is the device this app used to be, when it was a separate
	// one on the same hub (say "maryanne-tray"); it's still listed there.
	Replaced *hub.Device `json:"replaced,omitempty"`
}

// LinkClient is how this app labels itself to the hub when linking.
func LinkClient() string {
	host, _ := os.Hostname()
	if host == "" {
		return "droplet for Windows"
	}
	return "droplet for Windows on " + strings.ToLower(host)
}

// Link makes this app part of an existing device (normally this PC's
// browser), using the six-digit code droplet shows under "Set up remote
// control of this device". Files, messages and rings for that device then
// arrive here too, and the PC is listed once.
//
// hubURL is the hub to link on; empty (or the hub already set up) means the
// current route, LAN or tailnet. For a hub found on the LAN, see LinkLAN.
func (a *Agent) Link(ctx context.Context, hubURL, code, pinCode string) (*LinkResult, error) {
	cfg := a.Store.Get()
	digits, err := linkDigits(code)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	current := strings.TrimSpace(hubURL) == ""
	if !current {
		u, err := hub.ParseHubURL(hubURL)
		if err != nil {
			return nil, &FieldError{"hub_url", err.Error()}
		}
		hubURL = u.String()
		current = sameHub(hubURL, cfg.HubURL) && cfg.Hub != nil
	}
	if current {
		if cfg.Hub == nil && cfg.RemoteURL() == "" {
			return nil, &FieldError{"hub_url", "Choose your hub first."}
		}
		r, err := a.Routes.Ensure(ctx)
		if err != nil {
			return nil, &FieldError{"link_code", "Can't reach " + HubName(cfg) + ": " + describe(err, HubName(cfg)) + "."}
		}
		session := cfg.Session
		c, err := r.Client("", session)
		if err != nil {
			return nil, err
		}
		return a.link(ctx, c, digits, pinCode, cfg.Hub, "")
	}
	session := ""
	if sameHub(hubURL, cfg.HubURL) {
		session = cfg.Session
	}
	c, _ := hub.New(hubURL, "", session)
	return a.link(ctx, c, digits, pinCode, a.identityFromURL(ctx, hubURL), hubURL)
}

// linkDigits checks a six-digit link code.
func linkDigits(code string) (string, error) {
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, code)
	if len(digits) != 6 {
		return "", &FieldError{"link_code", "The code is six digits, like 123456."}
	}
	return digits, nil
}

// link trades the code along c and saves the result. ident is the hub's
// identity (nil if unknown); hubURL is the URL c uses, when it's one typed
// in rather than a route.
func (a *Agent) link(ctx context.Context, c *hub.Client, digits, pinCode string, ident *config.Hub, hubURL string) (*LinkResult, error) {
	cfg := a.Store.Get()
	if pinCode != "" {
		if err := c.Login(ctx, pinCode); err != nil {
			return nil, &FieldError{"pin", friendly(err, c)}
		}
	}
	linked, err := c.Link(ctx, digits, LinkClient())
	switch {
	case errors.Is(err, hub.ErrPINRequired):
		return nil, &FieldError{"pin", "This hub asks for a PIN. Enter it and link again."}
	case errors.Is(err, hub.ErrBadLinkCode):
		return nil, &FieldError{"link_code", "That code is wrong, used or expired. Make a new one in droplet."}
	case err != nil:
		return nil, &FieldError{"link_code", friendly(err, c)}
	}
	var moved bool
	switch {
	case ident != nil && cfg.Hub != nil:
		moved = ident.ID != cfg.Hub.ID
	case hubURL != "":
		moved = !sameHub(hubURL, cfg.HubURL)
	default:
		moved = cfg.Hub == nil && ident != nil
	}
	res := &LinkResult{ID: linked.ID, Name: linked.Name}
	if !moved && cfg.DeviceToken != "" && cfg.DeviceID != "" && cfg.DeviceID != linked.ID {
		res.Replaced = &hub.Device{ID: cfg.DeviceID, Name: cfg.DeviceName}
	}
	err = a.Store.Update(func(n *config.Config) {
		if moved || n.DeviceID != linked.ID {
			// a different device: its inbox and chats are new to us
			n.InboxSeen, n.ChatSeen = nil, map[string]float64{}
		}
		switch {
		case hubURL != "":
			n.HubURL = hubURL
		case moved && ident != nil:
			n.HubURL = ident.Tailnet
		}
		if ident != nil || moved {
			n.Hub = ident
		}
		n.DeviceToken, n.Session = linked.Token, c.Session
		n.DeviceID, n.DeviceName = linked.ID, linked.Name
		n.PairPending, n.PairCode = false, ""
	})
	if err != nil {
		return nil, fmt.Errorf("saving settings: %w", err)
	}
	a.mu.Lock()
	a.lastDests = nil
	a.mu.Unlock()
	a.Reset()
	return res, nil
}

// RemoveOld removes the device this app was before linking.
func (a *Agent) RemoveOld(ctx context.Context, id string) error {
	cfg := a.Store.Get()
	if id == "" || id == cfg.DeviceID {
		return errors.New("that's this PC")
	}
	c, err := a.Client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	return c.RemoveDevice(ctx, id)
}
