package agent

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hubtest"
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

func setup(t *testing.T) (*Agent, *hubtest.Hub, *captured, string) {
	t.Helper()
	h := hubtest.New()
	t.Cleanup(h.Server.Close)
	store, err := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	a := New(store, "droplet.exe")
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
	a := New(store, "droplet.exe")
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
	a := New(store, "droplet.exe")
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
	a2 := New(a.Store, "droplet.exe")
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
