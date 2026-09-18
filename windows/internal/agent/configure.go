package agent

import (
	"context"
	"errors"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/route"
)

// Probe is what the settings page learns about a hub before saving.
type Probe struct {
	OK          bool     `json:"ok"`
	Error       string   `json:"error,omitempty"`
	PINRequired bool     `json:"pin_required"`
	Registered  bool     `json:"registered"` // this PC is a device on that hub
	Name        string   `json:"name"`       // its name, or the hub's suggestion
	Taken       []string `json:"taken"`      // names already used by other devices
}

// ProbeHub checks a hub URL: is it reachable, does it want a PIN, what
// should this PC be called, and which names are taken.
func (a *Agent) ProbeHub(ctx context.Context, hubURL, pin string) Probe {
	cfg := a.Store.Get()
	u, err := hub.ParseHubURL(hubURL)
	if err != nil {
		return Probe{Error: err.Error()}
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	token, session := "", ""
	if sameHub(u.String(), cfg.HubURL) {
		token, session = cfg.DeviceToken, cfg.Session
	} else if cfg.Hub != nil && cfg.DeviceToken != "" {
		// the paired hub's tailnet URL, typed in after pairing on the LAN?
		if id := a.identityFromURL(ctx, u.String()); id != nil && id.ID == cfg.Hub.ID {
			token = cfg.DeviceToken
		}
	}
	c, _ := hub.New(u.String(), token, session)
	if pin != "" {
		if err := c.Login(ctx, pin); err != nil {
			return Probe{PINRequired: true, Error: friendly(err, c)}
		}
	}
	me, err := c.Me(ctx)
	if errors.Is(err, hub.ErrPINRequired) {
		return Probe{PINRequired: true, Error: "This hub asks for a PIN."}
	}
	if err != nil {
		return Probe{Error: friendly(err, c)}
	}
	p := Probe{OK: true}
	switch {
	case me.Device != nil:
		p.Registered, p.Name = true, me.Device.Name
	case me.Suggested != nil:
		p.Name = *me.Suggested
	}
	if host, err := os.Hostname(); err == nil && (p.Name == "" || p.Name == "Windows PC" || p.Name == "This device") {
		// the hub can only suggest the tailnet name over the tailnet; the PC knows its own
		p.Name = strings.ToLower(host)
	}
	if files, err := c.Files(ctx); err == nil {
		for _, d := range files.Devices {
			if !d.Self {
				p.Taken = append(p.Taken, d.Name)
			}
		}
	}
	return p
}

func sameHub(a, b string) bool {
	return strings.TrimRight(strings.ToLower(a), "/") == strings.TrimRight(strings.ToLower(b), "/")
}

// friendly turns connection errors into advice.
func friendly(err error, c *hub.Client) string {
	var nt *hub.NameTakenError
	switch {
	case errors.As(err, &nt):
		return nt.Msg
	case errors.Is(err, hub.ErrWrongPIN):
		return "That PIN isn't right."
	case errors.Is(err, hub.ErrPINRequired):
		return "This hub asks for a PIN."
	case errors.Is(err, context.DeadlineExceeded):
		return "No answer from " + c.Base.Host + ". Is the hub on, and is Tailscale connected on this PC?"
	}
	msg := err.Error()
	if strings.Contains(msg, "no such host") || strings.Contains(msg, "connection refused") ||
		strings.Contains(msg, "actively refused") || strings.Contains(msg, "dial tcp") {
		return "Can't reach " + c.Base.Host + ". Is the hub running, and is Tailscale connected on this PC?"
	}
	return msg
}

// Settings is what the settings page saves.
type Settings struct {
	HubURL         string `json:"hub_url"`
	Name           string `json:"name"`
	PIN            string `json:"pin"`
	DownloadDir    string `json:"download_dir"`
	AutoDownload   bool   `json:"auto_download"`
	NotifyFiles    bool   `json:"notify_files"`
	NotifyMessages bool   `json:"notify_messages"`
	RingSound      bool   `json:"ring_sound"`
	Autostart      bool   `json:"autostart"`
}

// FieldError points the settings page at the field to fix.
type FieldError struct {
	Field string
	Msg   string
}

func (e *FieldError) Error() string { return e.Msg }

// CurrentSettings are the saved settings, for filling in the page.
func (a *Agent) CurrentSettings() Settings {
	c := a.Store.Get()
	return Settings{
		HubURL: c.HubURL, Name: c.DeviceName, DownloadDir: c.DownloadDir,
		AutoDownload: c.AutoDownload, NotifyFiles: c.NotifyFiles, NotifyMessages: c.NotifyMessages,
		RingSound: c.RingSound, Autostart: c.Autostart,
	}
}

var percentVar = regexp.MustCompile(`%([A-Za-z0-9_()]+)%`)

// expandPath understands %USERPROFILE%-style and $HOME-style variables and ~.
func expandPath(p string) string {
	p = strings.TrimSpace(strings.Trim(strings.TrimSpace(p), `"`))
	p = percentVar.ReplaceAllStringFunc(p, func(m string) string {
		if v, ok := os.LookupEnv(m[1 : len(m)-1]); ok {
			return v
		}
		return m
	})
	p = os.ExpandEnv(p)
	if p == "~" || strings.HasPrefix(p, "~/") || strings.HasPrefix(p, `~\`) {
		if home, err := os.UserHomeDir(); err == nil {
			p = filepath.Join(home, p[1:])
		}
	}
	return filepath.Clean(p)
}

// Configure validates and saves settings: it signs in with the PIN if one
// is given, registers or renames this PC on the hub, and applies autostart.
//
// A hub URL that's new (or a PC not set up yet) is used directly, as typed:
// that's how a PC joins over the tailnet. Otherwise the hub is reached along
// the current route, the LAN or the tailnet, whichever works; a PC paired on
// the LAN may have no URL at all.
func (a *Agent) Configure(ctx context.Context, s Settings) error {
	cfg := a.Store.Get()
	hubURL := ""
	if strings.TrimSpace(s.HubURL) != "" {
		u, err := hub.ParseHubURL(s.HubURL)
		if err != nil {
			return &FieldError{"hub_url", err.Error()}
		}
		hubURL = u.String()
	}
	if hubURL == "" && cfg.DeviceToken == "" {
		return &FieldError{"hub_url", "Choose your hub under \"Hubs on this network\", or enter its Tailscale address."}
	}
	name, err := cleanDeviceName(s.Name)
	if err != nil {
		return err
	}
	dir := expandPath(s.DownloadDir)
	if s.DownloadDir == "" {
		dir = config.DefaultDownloadDir()
	}
	if !filepath.IsAbs(dir) {
		return &FieldError{"download_dir", "Use a full path, like C:\\Users\\you\\Downloads\\droplet."}
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return &FieldError{"download_dir", "Can't use that folder: " + err.Error()}
	}

	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	// a device cookie belongs to one hub; a new hub means a new registration
	moved := hubURL != "" && !sameHub(hubURL, cfg.HubURL)
	var ident *config.Hub
	if moved && cfg.Hub != nil && cfg.DeviceToken != "" {
		// maybe it's the paired hub's own tailnet URL, added now
		if id := a.identityFromURL(ctx, hubURL); id != nil && id.ID == cfg.Hub.ID {
			moved = false
			ident = mergeURLIdentity(*cfg.Hub, *id, hubURL)
		}
	}
	viaURL := hubURL != "" && (moved || cfg.DeviceToken == "")
	var c *hub.Client
	if viaURL {
		token, session := cfg.DeviceToken, cfg.Session
		if moved {
			token, session = "", ""
		}
		c, _ = hub.New(hubURL, token, session)
	} else {
		c, _, err = a.clientFor(ctx)
		if err != nil {
			return &FieldError{"hub_url", "Can't reach " + HubName(cfg) + " right now: " + describe(err, HubName(cfg)) + "."}
		}
	}
	if s.PIN != "" {
		if err := c.Login(ctx, s.PIN); err != nil {
			return &FieldError{"pin", friendly(err, c)}
		}
	}
	me, err := c.Me(ctx)
	if errors.Is(err, hub.ErrPINRequired) {
		return &FieldError{"pin", "This hub asks for a PIN. Enter it and save again."}
	}
	if err != nil {
		return &FieldError{"hub_url", friendly(err, c)}
	}
	var dev *hub.Device
	if me.Device == nil {
		c.Token = "" // unknown to the hub (removed, or never registered)
	}
	if me.Device == nil || me.Device.Name != name {
		dev, err = c.Register(ctx, name)
		var nt *hub.NameTakenError
		if errors.As(err, &nt) {
			return &FieldError{"name", "\"" + name + "\" is already a device on this hub — perhaps this PC's " +
				"browser. Pick another name, or remove the old one under Devices in droplet first."}
		}
		if err != nil {
			return &FieldError{"name", friendly(err, c)}
		}
	} else {
		dev = me.Device
	}
	// a rename answers without the pending flag; /api/me had it
	pending, code := dev.Pending, dev.Code
	if me.Device != nil && me.Device.Pending && c.Token != "" {
		pending, code = true, me.Device.Code
	}
	if viaURL {
		ident = a.identityFromURL(ctx, hubURL)
	}

	if err := platform.SetAutostart(a.Exe, s.Autostart); err != nil {
		return &FieldError{"autostart", "Couldn't change start-with-Windows: " + err.Error()}
	}
	err = a.Store.Update(func(n *config.Config) {
		if moved {
			n.InboxSeen, n.ChatSeen = nil, map[string]float64{}
		}
		if hubURL != "" && (viaURL || ident != nil) {
			n.HubURL = hubURL
		}
		if ident != nil || moved {
			n.Hub = ident // nil: a hub that can't say who it is; the URL is used as it is
		}
		n.DeviceToken, n.Session = c.Token, c.Session
		n.DeviceID, n.DeviceName = dev.ID, dev.Name
		n.PairPending, n.PairCode = pending, code
		n.DownloadDir = dir
		n.AutoDownload, n.NotifyFiles, n.NotifyMessages = s.AutoDownload, s.NotifyFiles, s.NotifyMessages
		n.RingSound, n.Autostart = s.RingSound, s.Autostart
	})
	if err != nil {
		return err
	}
	a.mu.Lock()
	a.lastDests = nil // rebuild Send To for the (maybe new) hub
	a.mu.Unlock()
	a.Reset()
	return nil
}

// mergeURLIdentity adds what a paired hub says over its (verified) URL to
// its stored identity. The pin stays: a different fingerprint is an
// identity change, which only Re-pair accepts.
func mergeURLIdentity(stored, fromURL config.Hub, hubURL string) *config.Hub {
	h := stored
	h.LAN = append([]string(nil), stored.LAN...)
	h.Tailnet = hubURL
	if fromURL.Name != "" {
		h.Name = fromURL.Name
	}
	switch {
	case h.Fingerprint == "":
		h.Fingerprint, h.PinSource = fromURL.Fingerprint, fromURL.PinSource
	case h.Fingerprint == fromURL.Fingerprint:
		h.PinSource = config.PinFromTailnet // now vouched for by verified TLS too
	}
	return &h
}

// identityFromURL asks a hub URL who the hub is. Only an https answer is
// trusted with the certificate fingerprint; anything else returns nil and
// the URL is used as it is (the identity may be found later, see migrate).
func (a *Agent) identityFromURL(ctx context.Context, hubURL string) *config.Hub {
	if !route.IsHTTPS(hubURL) {
		return nil
	}
	info, err := a.Routes.Deps.Info(ctx, hubURL, nil)
	if err != nil {
		if !errors.Is(err, hub.ErrNotFound) {
			log.Printf("hub identity from %s: %v", hubURL, err)
		}
		return nil
	}
	h := route.FromInfo(info, config.PinFromTailnet, hubURL)
	return &h
}
