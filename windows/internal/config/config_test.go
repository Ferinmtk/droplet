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

func TestHubIdentityRoundTripAndCopy(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	s, _ := OpenPath(path)
	err := s.Update(func(c *Config) {
		c.HubURL = "" // LAN-paired, no tailnet
		c.Hub = &Hub{ID: "9b16173d305cd15a", Name: "t15", Fingerprint: "ab", PinSource: PinFromLAN,
			LAN: []string{"192.168.100.20:8443"}, HTTPPort: 8000}
		c.DeviceToken, c.PairPending, c.PairCode = "tok", true, "0421"
	})
	if err != nil {
		t.Fatal(err)
	}
	s2, _ := OpenPath(path)
	c := s2.Get()
	if c.HubURL != "" {
		t.Fatalf("a LAN-paired hub must not get the default URL, got %q", c.HubURL)
	}
	if c.Hub == nil || c.Hub.ID != "9b16173d305cd15a" || c.Hub.LAN[0] != "192.168.100.20:8443" || c.Hub.PinSource != PinFromLAN {
		t.Fatalf("hub identity: %+v", c.Hub)
	}
	if c.Registered() || !c.Pending() || c.PairCode != "0421" {
		t.Fatal("a pending device isn't registered yet")
	}
	c.Hub.LAN[0] = "changed"
	c.Hub.Name = "changed"
	if got := s2.Get().Hub; got.LAN[0] != "192.168.100.20:8443" || got.Name != "t15" {
		t.Fatal("Get must not share the hub identity with the store")
	}
	if c.RemoteURL() != "" {
		t.Fatalf("remote URL: %q", c.RemoteURL())
	}
	c.Hub.Tailnet = "https://t15.example.ts.net"
	if c.RemoteURL() != "https://t15.example.ts.net" {
		t.Fatalf("remote URL should fall back to the hub's tailnet: %q", c.RemoteURL())
	}
}

func TestOldConfigLoads(t *testing.T) {
	// a config from before local-first: a hub URL and a token, no identity
	path := filepath.Join(t.TempDir(), "config.json")
	os.WriteFile(path, []byte(`{"hub_url":"https://t15.tail7375fe.ts.net","device_id":"d1","device_token":"tok"}`), 0o600)
	s, err := OpenPath(path)
	if err != nil {
		t.Fatal(err)
	}
	c := s.Get()
	if !c.Registered() || c.Hub != nil || c.RemoteURL() != "https://t15.tail7375fe.ts.net" {
		t.Fatalf("old config: %+v", c)
	}
}
