package settings

import (
	"encoding/json"
	"io"
	"net/http"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hubtest"
)

func TestSettingsPageFlow(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	h.AddDevice("maryanne") // e.g. this PC's browser got the name first
	t.Setenv("DROPLET_CONFIG_DIR", t.TempDir())
	store, _ := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	a := agent.New(store, "droplet.exe")
	s, err := Start(a)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	resp, err := http.Get(s.URL())
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("page: %v %v", resp, err)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "This PC's name") {
		t.Fatal("page content")
	}
	// the path token is required
	base := strings.TrimSuffix(s.URL(), "/")
	base = base[:strings.LastIndex(base, "/")]
	if r, _ := http.Get(base + "/"); r.StatusCode != 404 {
		t.Fatalf("no token should 404, got %d", r.StatusCode)
	}
	// DNS rebinding: wrong Host header
	req, _ := http.NewRequest("GET", s.URL(), nil)
	req.Host = "evil.example:80"
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != 403 {
		t.Fatalf("wrong host should be refused, got %d", r.StatusCode)
	}

	post := func(path, body string) (int, map[string]any) {
		r, err := http.Post(s.URL()+path, "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		var out map[string]any
		json.NewDecoder(r.Body).Decode(&out)
		return r.StatusCode, out
	}
	code, out := post("api/probe", `{"hub_url":"`+h.Server.URL+`"}`)
	if code != 200 || out["ok"] != true || out["name"] != "maryanne" {
		t.Fatalf("probe: %d %v", code, out)
	}
	dl := filepath.Join(t.TempDir(), "dl")
	code, out = post("api/save", `{"hub_url":"`+h.Server.URL+`","name":"maryanne","download_dir":"`+dl+`","auto_download":true}`)
	if code != 400 || out["field"] != "name" {
		t.Fatalf("clash should be a name error: %d %v", code, out)
	}
	code, out = post("api/save", `{"hub_url":"`+h.Server.URL+`","name":"maryanne-pc","download_dir":"`+dl+`","auto_download":true}`)
	if code != 200 || out["name"] != "maryanne-pc" {
		t.Fatalf("save: %d %v", code, out)
	}
	if !store.Get().Registered() {
		t.Fatal("should be registered")
	}
	// cross-site form posts are refused
	r, _ := http.Post(s.URL()+"api/save", "text/plain", strings.NewReader("{}"))
	if r.StatusCode != 415 {
		t.Fatalf("non-JSON post: %d", r.StatusCode)
	}

	if err := s.Publish(); err != nil {
		t.Fatal(err)
	}
	if u, ok := RunningURL(); !ok || u != s.URL() {
		t.Fatalf("RunningURL: %q %v", u, ok)
	}
	if err := PostRunning("api/stop-ring"); err != nil {
		t.Fatal(err)
	}
	Unpublish()
	if _, ok := RunningURL(); ok {
		t.Fatal("unpublished")
	}
}

func TestSettingsLinkAndRemote(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	browser := h.AddDevice("maryanne") // the PC's browser
	t.Setenv("DROPLET_CONFIG_DIR", t.TempDir())
	store, _ := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	a := agent.New(store, "droplet.exe")
	s, err := Start(a)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	post := func(path, body string) (int, map[string]any) {
		r, err := http.Post(s.URL()+path, "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		var out map[string]any
		json.NewDecoder(r.Body).Decode(&out)
		return r.StatusCode, out
	}
	code, out := post("api/link", `{"hub_url":"`+h.Server.URL+`","code":"999999"}`)
	if code != 400 || out["field"] != "link_code" {
		t.Fatalf("bad code: %d %v", code, out)
	}
	code, out = post("api/link", `{"hub_url":"`+h.Server.URL+`","code":"`+h.LinkCode(browser)+`"}`)
	if code != 200 || out["link"].(map[string]any)["name"] != "maryanne" {
		t.Fatalf("link: %d %v", code, out)
	}
	if cfg := store.Get(); cfg.DeviceID != browser.ID || !cfg.Registered() {
		t.Fatalf("config %+v", cfg)
	}
	code, out = post("api/remote", `{"input":false,"media":true,"lock":true,"screenshot":false,"clipboard":true,"paused":false}`)
	if code != 200 {
		t.Fatalf("remote: %d %v", code, out)
	}
	cfg := store.Get()
	if cfg.RemoteInput || !cfg.RemoteMedia || cfg.RemoteScreenshot || !cfg.ClipboardSync {
		t.Fatalf("switches %+v", cfg)
	}
	r, _ := http.Get(s.URL() + "api/settings")
	var st map[string]any
	json.NewDecoder(r.Body).Decode(&st)
	if st["remote"].(map[string]any)["clipboard"] != true || st["live"] == nil {
		t.Fatalf("settings %v", st)
	}
}
