package agent

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
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
	token, session := "", ""
	if sameHub(u.String(), cfg.HubURL) {
		token, session = cfg.DeviceToken, cfg.Session
	}
	c, _ := hub.New(u.String(), token, session)
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
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
func (a *Agent) Configure(ctx context.Context, s Settings) error {
	cfg := a.Store.Get()
	u, err := hub.ParseHubURL(s.HubURL)
	if err != nil {
		return &FieldError{"hub_url", err.Error()}
	}
	hubURL := u.String()
	name := strings.Join(strings.Fields(s.Name), " ")
	if name == "" {
		return &FieldError{"name", "Give this PC a name, like \"maryanne\"."}
	}
	if len([]rune(name)) > 40 {
		return &FieldError{"name", "Keep the name under 40 characters."}
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

	// a device cookie belongs to one hub; a new hub means a new registration
	token, session := cfg.DeviceToken, cfg.Session
	moved := !sameHub(hubURL, cfg.HubURL)
	if moved {
		token, session = "", ""
	}
	c, _ := hub.New(hubURL, token, session)
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
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

	if err := platform.SetAutostart(a.Exe, s.Autostart); err != nil {
		return &FieldError{"autostart", "Couldn't change start-with-Windows: " + err.Error()}
	}
	err = a.Store.Update(func(n *config.Config) {
		if moved {
			n.InboxSeen, n.ChatSeen = nil, map[string]float64{}
		}
		n.HubURL = hubURL
		n.DeviceToken, n.Session = c.Token, c.Session
		n.DeviceID, n.DeviceName = dev.ID, dev.Name
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
