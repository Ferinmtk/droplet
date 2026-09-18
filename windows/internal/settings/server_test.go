package settings

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/netip"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hubtest"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
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

func TestSettingsLANJoin(t *testing.T) {
	h := hubtest.NewTLS()
	h.LANGate = true
	defer h.Server.Close()
	t.Setenv("DROPLET_CONFIG_DIR", t.TempDir())
	store, _ := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	a := agent.New(store, "droplet.exe")
	host, port, _ := strings.Cut(h.Addr(), ":")
	p, _ := strconv.Atoi(port)
	ann := lan.Hub{Instance: "droplet-a1b2c3", Port: p, Addrs: []netip.Addr{netip.MustParseAddr(host)},
		TXT: lan.TXT{ID: h.ID, Fingerprint: h.FP, Name: "hubtest", HTTPPort: 8000}}
	a.Routes.Deps.Browse = func(ctx context.Context, wait time.Duration, found func(lan.Hub) bool) ([]lan.Hub, error) {
		if found != nil {
			found(ann)
		}
		return []lan.Hub{ann}, nil
	}
	s, err := Start(a)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	get := func(path string) map[string]any {
		r, err := http.Get(s.URL() + path)
		if err != nil {
			t.Fatal(err)
		}
		var out map[string]any
		json.NewDecoder(r.Body).Decode(&out)
		return out
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
	hubs := get("api/discover")["hubs"].([]any)
	if len(hubs) != 1 || hubs[0].(map[string]any)["id"] != h.ID {
		t.Fatalf("discover: %v", hubs)
	}
	// saving with no hub picked and no address says what to do
	code, out := post("api/save", `{"hub_url":"","name":"maryanne"}`)
	if code != 400 || out["field"] != "hub_url" {
		t.Fatalf("save without a hub: %d %v", code, out)
	}
	code, out = post("api/join", `{"hub_id":"`+h.ID+`","name":"maryanne"}`)
	if code != 200 {
		t.Fatalf("join: %d %v", code, out)
	}
	j := out["join"].(map[string]any)
	dev := h.Find("maryanne")
	if j["pending"] != true || j["code"] != dev.Code() || out["hub_url"] != "" {
		t.Fatalf("join: %v", out)
	}
	st := get("api/settings")["hub"].(map[string]any)
	if st["pending"] != true || st["code"] != dev.Code() || !strings.Contains(st["text"].(string), "Waiting") {
		t.Fatalf("hub state: %v", st)
	}
	// the page then saves the rest of its choices, which must keep the request
	dl := filepath.Join(t.TempDir(), "dl")
	code, out = post("api/save", `{"hub_url":"","name":"maryanne","download_dir":"`+dl+`","autostart":false}`)
	if code != 200 || out["pending"] != true {
		t.Fatalf("save while waiting: %d %v", code, out)
	}
	if cfg := store.Get(); !cfg.Pending() || cfg.DeviceToken != dev.Token {
		t.Fatalf("save while waiting changed the request: %+v", cfg)
	}
	code, out = post("api/pairing/cancel", `{}`)
	if code != 200 || store.Get().DeviceToken != "" {
		t.Fatalf("cancel: %d %v", code, out)
	}
}
