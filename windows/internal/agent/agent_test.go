package agent

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hubtest"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
)

type captured struct {
	mu sync.Mutex
	n  []platform.Notification
}

func (c *captured) take() []platform.Notification {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := c.n
	c.n = nil
	return out
}

// newAgent is an agent whose LAN discovery finds nothing, so tests never
// touch the real network.
func newAgent(store *config.Store) *Agent {
	a := New(store, "droplet.exe")
	a.Routes.Deps.Browse = func(context.Context, time.Duration, func(lan.Hub) bool) ([]lan.Hub, error) { return nil, nil }
	return a
}

func setup(t *testing.T) (*Agent, *hubtest.Hub, *captured, string) {
	t.Helper()
	h := hubtest.New()
	t.Cleanup(h.Server.Close)
	store, err := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	a := newAgent(store)
	cap := &captured{}
	notify = func(n platform.Notification) { cap.mu.Lock(); cap.n = append(cap.n, n); cap.mu.Unlock() }
	t.Cleanup(func() { notify = platform.Notify })
	dl := filepath.Join(t.TempDir(), "Downloads", "droplet")
	err = a.Configure(context.Background(), Settings{
		HubURL: h.Server.URL, Name: "maryanne", DownloadDir: dl,
		AutoDownload: true, NotifyFiles: true, NotifyMessages: true, RingSound: false,
	})
	if err != nil {
		t.Fatal(err)
	}
	return a, h, cap, dl
}

func TestConfigureNameClash(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	h.AddDevice("maryanne")
	store, _ := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	a := newAgent(store)
	err := a.Configure(context.Background(), Settings{HubURL: h.Server.URL, Name: "Maryanne", DownloadDir: t.TempDir()})
	fe, ok := err.(*FieldError)
	if !ok || fe.Field != "name" || !strings.Contains(fe.Msg, "already a device") {
		t.Fatalf("expected a name field error, got %v", err)
	}
	if store.Get().Registered() {
		t.Fatal("nothing should be saved on a clash")
	}
	p := a.ProbeHub(context.Background(), h.Server.URL, "")
	if !p.OK || p.Registered || len(p.Taken) != 1 || p.Taken[0] != "maryanne" {
		t.Fatalf("probe: %+v", p)
	}
}

func TestConfigurePIN(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	h.PIN = "1234"
	store, _ := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	a := newAgent(store)
	s := Settings{HubURL: h.Server.URL, Name: "maryanne", DownloadDir: t.TempDir()}
	if fe, ok := a.Configure(context.Background(), s).(*FieldError); !ok || fe.Field != "pin" {
		t.Fatal("expected a PIN field error")
	}
	if p := a.ProbeHub(context.Background(), h.Server.URL, ""); !p.PINRequired {
		t.Fatal("probe should report the PIN")
	}
	s.PIN = "1234"
	if err := a.Configure(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	if c := store.Get(); c.Session == "" || !c.Registered() {
		t.Fatalf("session and token should be saved: %+v", c)
	}
}

func TestTickDownloadsAndAnnouncesOnce(t *testing.T) {
	a, h, cap, dl := setup(t)
	me := h.Devices[len(h.Devices)-1]
	phone := h.AddDevice("slim")
	os.MkdirAll(dl, 0o755)
	os.WriteFile(filepath.Join(dl, "report.pdf"), []byte("older"), 0o644)
	h.Deliver(me, "slim", "report.pdf", []byte("%PDF-new"))

	ctx := context.Background()
	if err := a.tick(ctx); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join(dl, "report (1).pdf"))
	if err != nil || string(got) != "%PDF-new" {
		t.Fatalf("download: %q %v", got, err)
	}
	if len(h.Inbox[me.ID]) != 0 {
		t.Fatal("downloaded file should be deleted from the hub inbox")
	}
	ns := cap.take()
	var fileToasts []platform.Notification
	for _, n := range ns {
		if strings.Contains(n.Title, "sent") {
			fileToasts = append(fileToasts, n)
		}
	}
	if len(fileToasts) != 1 || fileToasts[0].Title != "slim sent report.pdf" || fileToasts[0].Click.Kind != platform.ActShowFile {
		t.Fatalf("toasts: %+v", ns)
	}

	// chat: announced once, even across a restart (new agent, same config)
	h.Say(phone, me, "dinner at 7?")
	a.tick(ctx)
	ns = cap.take()
	if len(ns) != 1 || ns[0].Title != "slim" || ns[0].Body != "dinner at 7?" {
		t.Fatalf("chat toast: %+v", ns)
	}
	a.tick(ctx)
	if ns := cap.take(); len(ns) != 0 {
		t.Fatalf("no repeats: %+v", ns)
	}
	a2 := newAgent(a.Store)
	h.Mu.Lock()
	delete(h.Read, me.ID) // e.g. the hub lost its read markers
	h.Mu.Unlock()
	a2.tick(ctx)
	if ns := cap.take(); len(ns) != 0 {
		t.Fatalf("restart must not repeat old messages: %+v", ns)
	}
	st := a.Status()
	if !st.Connected || !st.Configured || st.Tooltip() != "droplet — connected to 127" {
		t.Logf("tooltip: %s", st.Tooltip())
	}
	if !st.Connected {
		t.Fatal("should be connected")
	}
}

func TestPausedAndNoAutoDownload(t *testing.T) {
	a, h, cap, dl := setup(t)
	me := h.Devices[len(h.Devices)-1]
	a.Store.Update(func(c *config.Config) { c.AutoDownload = false })
	h.Deliver(me, "slim", "x.txt", []byte("x"))
	a.tick(context.Background())
	if _, err := os.Stat(filepath.Join(dl, "x.txt")); err == nil {
		t.Fatal("auto-download is off")
	}
	ns := cap.take()
	if len(ns) != 1 || ns[0].Click.Kind != platform.ActOpenURL || !strings.HasSuffix(ns[0].Click.Arg, "/#inbox") {
		t.Fatalf("toast should point at the inbox: %+v", ns)
	}
	a.SetPaused(true)
	h.Deliver(me, "slim", "y.txt", []byte("y"))
	a.tick(context.Background())
	if ns := cap.take(); len(ns) != 0 {
		t.Fatalf("paused: %+v", ns)
	}
}

func TestRingLifecycle(t *testing.T) {
	a, h, cap, _ := setup(t)
	me := h.Devices[len(h.Devices)-1]
	ctx := context.Background()
	a.tick(ctx) // old hub: ring unsupported, no error
	if a.ringSupported {
		t.Fatal("should notice the hub can't ring")
	}
	h.Mu.Lock()
	h.Ring = true
	h.ActiveRg[me.ID] = &hubtest.Ring{ID: "r1", From: "Home", TS: 1}
	h.Mu.Unlock()
	a.Reset()
	a.tick(ctx)
	if !a.Status().Ringing || a.Status().RingFrom != "Home" {
		t.Fatalf("should be ringing: %+v", a.Status())
	}
	ns := cap.take()
	if len(ns) == 0 || !ns[len(ns)-1].Urgent || ns[len(ns)-1].Buttons[0].Kind != platform.ActStopRing {
		t.Fatalf("ring toast: %+v", ns)
	}
	a.StopRing()
	if a.Status().Ringing {
		t.Fatal("stopped")
	}
	h.Mu.Lock()
	still := h.ActiveRg[me.ID]
	h.Mu.Unlock()
	if still != nil {
		t.Fatal("stop should reach the hub")
	}
	// rung again, then stopped from elsewhere (the hub says null)
	h.Mu.Lock()
	h.ActiveRg[me.ID] = &hubtest.Ring{ID: "r2", From: "slim", TS: 2}
	h.Mu.Unlock()
	a.tick(ctx)
	if !a.Status().Ringing {
		t.Fatal("second ring")
	}
	h.Mu.Lock()
	delete(h.ActiveRg, me.ID)
	h.Mu.Unlock()
	a.tick(ctx)
	if a.Status().Ringing {
		t.Fatal("should stop when the hub clears the ring")
	}
}

func TestRemovedDevice(t *testing.T) {
	a, h, cap, _ := setup(t)
	h.Mu.Lock()
	h.Devices = nil
	h.Mu.Unlock()
	a.tick(context.Background())
	a.tick(context.Background())
	ns := cap.take()
	if len(ns) != 1 || !strings.Contains(ns[0].Title, "removed") {
		t.Fatalf("warn once: %+v", ns)
	}
	if a.Status().Configured {
		t.Fatal("not configured any more")
	}
}

func TestExpandPath(t *testing.T) {
	t.Setenv("USERPROFILE", "/home/m")
	if got := expandPath(`%USERPROFILE%/Downloads/droplet`); got != filepath.Clean("/home/m/Downloads/droplet") {
		t.Fatal(got)
	}
	if got := expandPath(`"/tmp/x"`); got != "/tmp/x" {
		t.Fatal(got)
	}
}

func TestLinkWithCode(t *testing.T) {
	a, h, _, _ := setup(t) // registered as its own device, "maryanne"
	browser := h.AddDevice("maryanne-pc")
	old := a.Store.Get()

	if _, err := a.Link(context.Background(), h.Server.URL, "12", ""); err == nil {
		t.Fatal("a short code should be refused")
	}
	if _, err := a.Link(context.Background(), h.Server.URL, "000000", ""); err == nil ||
		!strings.Contains(err.Error(), "wrong") {
		t.Fatalf("a wrong code: %v", err)
	}
	code := h.LinkCode(browser)
	res, err := a.Link(context.Background(), h.Server.URL, code[:3]+" "+code[3:], "")
	if err != nil {
		t.Fatal(err)
	}
	cfg := a.Store.Get()
	if cfg.DeviceID != browser.ID || cfg.DeviceName != "maryanne-pc" || cfg.DeviceToken == old.DeviceToken || cfg.DeviceToken == browser.Token {
		t.Fatalf("config after link: %+v", cfg)
	}
	if res.Replaced == nil || res.Replaced.ID != old.DeviceID {
		t.Fatalf("should report the old device: %+v", res)
	}
	// the app now is that device: the hub lists it as self
	c, _ := a.Client()
	files, err := c.Files(context.Background())
	if err != nil || files.Self() == nil || files.Self().ID != browser.ID {
		t.Fatalf("self after link: %+v %v", files, err)
	}
	if err := a.RemoveOld(context.Background(), res.Replaced.ID); err != nil {
		t.Fatal(err)
	}
	if h.Removed[0] != old.DeviceID {
		t.Fatal("old device not removed")
	}
	// a code only works once
	if _, err := a.Link(context.Background(), h.Server.URL, code, ""); err == nil {
		t.Fatal("reused code accepted")
	}
	// the live connection's parameters follow
	p := a.RemoteParams()
	if p.Token != cfg.DeviceToken || !p.Caps["input"] || p.Caps["clipboard"] || p.Paused {
		t.Fatalf("params %+v", p)
	}
}

func TestRemoteSwitches(t *testing.T) {
	a, _, _, _ := setup(t)
	changed := 0
	a.OnRemoteChange = func() { changed++ }
	if err := a.SetRemote(RemoteSettings{Media: true, Clipboard: true}); err != nil {
		t.Fatal(err)
	}
	if err := a.SetRemotePaused(true); err != nil {
		t.Fatal(err)
	}
	p := a.RemoteParams()
	if p.Caps["input"] || !p.Caps["media"] || !p.Caps["clipboard"] || !p.Paused || changed != 2 {
		t.Fatalf("params %+v, %d changes", p, changed)
	}
	if got := a.CurrentRemote(); got != (RemoteSettings{Media: true, Clipboard: true, Paused: true}) {
		t.Fatalf("current %+v", got)
	}
}

// sendToCalls records what the agent asks of Explorer's Send To folder.
type sendToCalls struct {
	mu    sync.Mutex
	calls [][]platform.Dest // nil: remove them all
}

func (s *sendToCalls) take() [][]platform.Dest {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.calls
	s.calls = nil
	return out
}

func captureSendTo(t *testing.T) *sendToCalls {
	rec := &sendToCalls{}
	syncSendTo = func(exe string, dests []platform.Dest) error {
		rec.mu.Lock()
		defer rec.mu.Unlock()
		if exe == "" {
			dests = nil
		}
		rec.calls = append(rec.calls, dests)
		return nil
	}
	t.Cleanup(func() { syncSendTo = platform.SyncSendTo })
	return rec
}

func TestSendToNeedsConsent(t *testing.T) {
	rec := captureSendTo(t)
	a, h, _, dl := setup(t)
	h.AddDevice("phone")
	ctx := context.Background()
	if c := a.Store.Get(); c.SendTo || c.Autostart {
		t.Fatalf("a new setup must leave Send To and autostart off: %+v", c)
	}
	rec.take()

	// off: polling only ever clears the folder, once
	a.tick(ctx)
	a.tick(ctx)
	if got := rec.take(); len(got) != 1 || got[0] != nil {
		t.Fatalf("with Send To off, want one clear, got %v", got)
	}

	// turned on in Settings: the next poll adds the hub and each device
	s := a.CurrentSettings()
	s.SendTo = true
	if err := a.Configure(ctx, s); err != nil {
		t.Fatal(err)
	}
	if !a.Store.Get().SendTo || !a.CurrentSettings().SendTo {
		t.Fatal("Send To not saved")
	}
	a.tick(ctx)
	got := rec.take()
	if len(got) != 1 || len(got[0]) != 2 || got[0][0].ID != "hub" || got[0][1].Name != "phone" {
		t.Fatalf("want hub + phone, got %v", got)
	}
	a.tick(ctx)
	if got := rec.take(); len(got) != 0 {
		t.Fatalf("unchanged devices shouldn't rewrite the shortcuts: %v", got)
	}

	// turned off again: removed at once, without waiting for the hub
	s.SendTo = false
	if err := a.Configure(ctx, s); err != nil {
		t.Fatal(err)
	}
	if got := rec.take(); len(got) != 1 || got[0] != nil {
		t.Fatalf("turning Send To off should remove the entries at once, got %v", got)
	}
	_ = dl
}
