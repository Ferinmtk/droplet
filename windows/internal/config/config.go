// Package config holds the companion's settings and state, stored as JSON
// in %APPDATA%\droplet\config.json (or the OS equivalent elsewhere).
package config

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

// DefaultHub is the hub this companion was written for.
const DefaultHub = "https://t15.tail7375fe.ts.net"

// Config is everything persisted between runs. The device token is the
// device's identity on the hub (the droplet_device cookie), so the file is
// written owner-only.
type Config struct {
	HubURL      string `json:"hub_url"`
	DeviceID    string `json:"device_id,omitempty"`
	DeviceName  string `json:"device_name,omitempty"`
	DeviceToken string `json:"device_token,omitempty"`
	// session cookie from a PIN login, when the hub asks for one
	Session string `json:"session,omitempty"`

	DownloadDir    string `json:"download_dir"`
	AutoDownload   bool   `json:"auto_download"`
	NotifyFiles    bool   `json:"notify_files"`
	NotifyMessages bool   `json:"notify_messages"`
	RingSound      bool   `json:"ring_sound"`
	Autostart      bool   `json:"autostart"`
	Paused         bool   `json:"paused"`

	// Remote control (docs/remote.md): what other devices may do to this
	// PC over the live connection. RemotePaused switches all of it off at
	// once from the tray.
	RemoteInput      bool `json:"remote_input"`
	RemoteMedia      bool `json:"remote_media"`
	RemoteLock       bool `json:"remote_lock"`
	RemoteScreenshot bool `json:"remote_screenshot"`
	ClipboardSync    bool `json:"clipboard_sync"`
	RemotePaused     bool `json:"remote_paused"`

	// newest chat message timestamp already shown, per sender device id
	ChatSeen map[string]float64 `json:"chat_seen,omitempty"`
	// inbox items already announced ("name|mtime"), so a restart doesn't repeat them
	InboxSeen []string `json:"inbox_seen,omitempty"`
	// secret carried by droplet: links in notifications, so a web page
	// can't trigger them through the URL scheme
	ActionKey string `json:"action_key,omitempty"`
}

// Registered reports whether this install has named itself on the hub.
func (c Config) Registered() bool { return c.DeviceToken != "" }

// Defaults returns a fresh config for a first run.
func Defaults() *Config {
	return &Config{
		HubURL:         DefaultHub,
		DownloadDir:    DefaultDownloadDir(),
		AutoDownload:   true,
		NotifyFiles:    true,
		NotifyMessages: true,
		RingSound:      true,
		// remote control is what the live connection is for, and the tray
		// shows when it's in use; clipboard sync is off until asked for,
		// because it sends everything copied (passwords too) to every device
		RemoteInput:      true,
		RemoteMedia:      true,
		RemoteLock:       true,
		RemoteScreenshot: true,
		ClipboardSync:    false,
		ChatSeen:         map[string]float64{},
		ActionKey:        randomKey(),
	}
}

// DefaultDownloadDir is %USERPROFILE%\Downloads\droplet (~/Downloads/droplet elsewhere).
func DefaultDownloadDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "droplet-downloads"
	}
	return filepath.Join(home, "Downloads", "droplet")
}

func randomKey() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// Dir is where config.json lives. DROPLET_CONFIG_DIR overrides it (tests, portable use).
func Dir() (string, error) {
	if d := os.Getenv("DROPLET_CONFIG_DIR"); d != "" {
		return d, nil
	}
	base := os.Getenv("APPDATA")
	if base == "" {
		var err error
		if base, err = os.UserConfigDir(); err != nil {
			return "", err
		}
	}
	return filepath.Join(base, "droplet"), nil
}

// Store loads and saves one config file and serialises access to it.
type Store struct {
	Path string
	mu   sync.Mutex
	cfg  *Config
}

// Open loads the config at the default location, or starts from defaults.
func Open() (*Store, error) {
	dir, err := Dir()
	if err != nil {
		return nil, err
	}
	return OpenPath(filepath.Join(dir, "config.json"))
}

// OpenPath loads the config file at path; a missing file means defaults.
func OpenPath(path string) (*Store, error) {
	s := &Store{Path: path}
	cfg, err := load(path)
	if err != nil {
		return nil, err
	}
	s.cfg = cfg
	return s, nil
}

func load(path string) (*Config, error) {
	cfg := Defaults()
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return cfg, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, cfg); err != nil {
		return nil, err
	}
	if cfg.HubURL == "" {
		cfg.HubURL = DefaultHub
	}
	if cfg.DownloadDir == "" {
		cfg.DownloadDir = DefaultDownloadDir()
	}
	if cfg.ChatSeen == nil {
		cfg.ChatSeen = map[string]float64{}
	}
	if cfg.ActionKey == "" {
		cfg.ActionKey = randomKey()
	}
	return cfg, nil
}

// Get returns a copy of the current config.
func (s *Store) Get() Config {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cfg.clone()
}

// Exists reports whether a config file has been saved yet.
func (s *Store) Exists() bool {
	_, err := os.Stat(s.Path)
	return err == nil
}

// Update applies fn to the config and saves it.
func (s *Store) Update(fn func(c *Config)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.cfg.clone()
	fn(&next)
	if err := save(s.Path, &next); err != nil {
		return err
	}
	s.cfg = &next
	return nil
}

// Reload re-reads the file (another process may have changed it).
func (s *Store) Reload() error {
	cfg, err := load(s.Path)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.cfg = cfg
	s.mu.Unlock()
	return nil
}

func (c Config) clone() Config {
	out := c
	out.ChatSeen = make(map[string]float64, len(c.ChatSeen))
	for k, v := range c.ChatSeen {
		out.ChatSeen[k] = v
	}
	out.InboxSeen = append([]string(nil), c.InboxSeen...)
	return out
}

func save(path string, c *Config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	// write-then-rename so a crash mid-save can't leave a torn file
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
