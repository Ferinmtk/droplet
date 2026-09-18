package config

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestDefaultsWhenMissing(t *testing.T) {
	s, err := OpenPath(filepath.Join(t.TempDir(), "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	c := s.Get()
	if c.HubURL != DefaultHub || !c.AutoDownload || !c.NotifyFiles || !c.NotifyMessages || !c.RingSound {
		t.Fatalf("unexpected defaults: %+v", c)
	}
	if c.Registered() || s.Exists() {
		t.Fatal("a fresh config should not be registered or saved")
	}
	if len(c.ActionKey) != 32 {
		t.Fatalf("action key %q", c.ActionKey)
	}
}

func TestRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "config.json")
	s, _ := OpenPath(path)
	err := s.Update(func(c *Config) {
		c.HubURL = "https://hub.example"
		c.DeviceID, c.DeviceName, c.DeviceToken = "abc123", "maryanne", "tok"
		c.AutoDownload = false
		c.ChatSeen["dev1"] = 1712345678.25
		c.InboxSeen = []string{"a.txt|1"}
		c.Paused = true
	})
	if err != nil {
		t.Fatal(err)
	}
	s2, err := OpenPath(path)
	if err != nil {
		t.Fatal(err)
	}
	a, b := s.Get(), s2.Get()
	if a.HubURL != b.HubURL || a.DeviceToken != b.DeviceToken || b.AutoDownload || !b.Paused ||
		b.ChatSeen["dev1"] != 1712345678.25 || len(b.InboxSeen) != 1 || a.ActionKey != b.ActionKey {
		t.Fatalf("round trip changed config:\n%+v\n%+v", a, b)
	}
	if !b.Registered() {
		t.Fatal("should be registered")
	}
	if runtime.GOOS != "windows" {
		st, _ := os.Stat(path)
		if st.Mode().Perm() != 0o600 {
			t.Fatalf("config should be owner-only, got %v", st.Mode().Perm())
		}
	}
}

func TestGetReturnsCopy(t *testing.T) {
	s, _ := OpenPath(filepath.Join(t.TempDir(), "c.json"))
	c := s.Get()
	c.ChatSeen["x"] = 1
	c.InboxSeen = append(c.InboxSeen, "y")
	if len(s.Get().ChatSeen) != 0 || len(s.Get().InboxSeen) != 0 {
		t.Fatal("Get must not share maps/slices with the store")
	}
}

func TestBadFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	os.WriteFile(path, []byte("{not json"), 0o600)
	if _, err := OpenPath(path); err == nil {
		t.Fatal("expected an error for a corrupt config")
	}
}
