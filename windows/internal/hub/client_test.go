package hub_test

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/hubtest"
)

var ctx = context.Background()

func register(t *testing.T, h *hubtest.Hub, name string) *hub.Client {
	t.Helper()
	c, err := hub.New(h.Server.URL, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Register(ctx, name); err != nil {
		t.Fatal(err)
	}
	return c
}

func TestRegisterAndMe(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	c, _ := hub.New(h.Server.URL+"/", "", "")
	me, err := c.Me(ctx)
	if err != nil || me.Device != nil || me.Suggested == nil || *me.Suggested != "maryanne" {
		t.Fatalf("unregistered me: %+v %v", me, err)
	}
	d, err := c.Register(ctx, "maryanne")
	if err != nil || d.Name != "maryanne" || c.Token == "" {
		t.Fatalf("register: %+v %v token=%q", d, err, c.Token)
	}
	// the cookie persists the identity: a new client with the token is the same device
	c2, _ := hub.New(h.Server.URL, c.Token, "")
	me, err = c2.Me(ctx)
	if err != nil || me.Device == nil || me.Device.ID != d.ID {
		t.Fatalf("me with token: %+v %v", me, err)
	}
	// rename keeps the token
	if _, err := c2.Register(ctx, "maryanne-pc"); err != nil || c2.Token != c.Token {
		t.Fatalf("rename: %v", err)
	}
}

func TestNameClash(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	h.AddDevice("Maryanne")
	c, _ := hub.New(h.Server.URL, "", "")
	_, err := c.Register(ctx, "maryanne")
	var nt *hub.NameTakenError
	if !errors.As(err, &nt) || nt.Msg == "" {
		t.Fatalf("expected a 409 NameTakenError, got %v", err)
	}
}

func TestPIN(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	h.PIN = "4321"
	c, _ := hub.New(h.Server.URL, "", "")
	if _, err := c.Me(ctx); !errors.Is(err, hub.ErrPINRequired) {
		t.Fatalf("expected ErrPINRequired, got %v", err)
	}
	if err := c.Login(ctx, "0000"); !errors.Is(err, hub.ErrWrongPIN) {
		t.Fatalf("expected ErrWrongPIN, got %v", err)
	}
	if err := c.Login(ctx, "4321"); err != nil || c.Session == "" {
		t.Fatalf("login: %v", err)
	}
	if _, err := c.Me(ctx); err != nil {
		t.Fatalf("after login: %v", err)
	}
}

func TestFilesUploadDownloadDelete(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	phone := h.AddDevice("slim")
	c := register(t, h, "maryanne")

	dir := t.TempDir()
	a := filepath.Join(dir, "a.txt")
	b := filepath.Join(dir, "b b.bin")
	os.WriteFile(a, []byte("alpha"), 0o644)
	os.WriteFile(b, bytes.Repeat([]byte{7}, 300000), 0o644)

	var last, total int64
	saved, err := c.Upload(ctx, phone.ID, []string{a, b}, func(s, t int64) { last, total = s, t })
	if err != nil || len(saved) != 2 {
		t.Fatalf("upload: %v %v", saved, err)
	}
	if last != total || total != 300005 {
		t.Fatalf("progress ended at %d/%d", last, total)
	}
	if string(h.Inbox[phone.ID]["a.txt"].Data) != "alpha" || h.Inbox[phone.ID]["a.txt"].From != "maryanne" {
		t.Fatal("file didn't land in slim's inbox from maryanne")
	}
	if _, err := c.Upload(ctx, "hub", []string{a}, nil); err != nil || string(h.Received["a.txt"]) != "alpha" {
		t.Fatalf("upload to hub: %v", err)
	}
	if _, err := c.Upload(ctx, "nope", []string{a}, nil); err == nil {
		t.Fatal("upload to a missing device should fail")
	}
	if _, err := c.Upload(ctx, "hub", []string{dir}, nil); err == nil {
		t.Fatal("folders should be refused")
	}

	me := h.Devices[len(h.Devices)-1]
	h.Deliver(me, "slim", "report 1.pdf", []byte("%PDF"))
	f, err := c.Files(ctx)
	if err != nil || len(f.Inbox) != 1 || f.Inbox[0].From != "slim" || f.Self() == nil || len(f.Others()) != 1 {
		t.Fatalf("files: %+v %v", f, err)
	}
	var buf bytes.Buffer
	if _, err := c.Download(ctx, "report 1.pdf", &buf); err != nil || buf.String() != "%PDF" {
		t.Fatalf("download: %q %v", buf.String(), err)
	}
	if err := c.DeleteInbox(ctx, "report 1.pdf"); err != nil {
		t.Fatal(err)
	}
	if err := c.DeleteInbox(ctx, "report 1.pdf"); !errors.Is(err, hub.ErrNotFound) {
		t.Fatalf("second delete: %v", err)
	}
}

func TestTextAndChat(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	phone := h.AddDevice("slim")
	c := register(t, h, "maryanne")
	if err := c.SendText(ctx, phone.ID, "hello slim"); err != nil {
		t.Fatal(err)
	}
	if err := c.SendText(ctx, "hub", "note to self"); err != nil || len(h.Texts) != 1 {
		t.Fatalf("text to hub: %v", err)
	}
	me := h.Devices[len(h.Devices)-1]
	h.Say(phone, me, "hi back")
	f, _ := c.Files(ctx)
	if f.Unread[phone.ID] != 1 {
		t.Fatalf("unread: %v", f.Unread)
	}
	msgs, err := c.Chat(ctx, phone.ID)
	if err != nil || len(msgs) != 2 || msgs[1].Text != "hi back" || msgs[1].From != phone.ID {
		t.Fatalf("chat: %+v %v", msgs, err)
	}
	f, _ = c.Files(ctx)
	if f.Unread[phone.ID] != 0 {
		t.Fatal("reading the thread marks it read")
	}
	anon, _ := hub.New(h.Server.URL, "", "")
	if err := anon.SendText(ctx, phone.ID, "x"); !errors.Is(err, hub.ErrNotRegistered) {
		t.Fatalf("unregistered chat: %v", err)
	}
}

func TestRing(t *testing.T) {
	h := hubtest.New()
	defer h.Server.Close()
	phone := h.AddDevice("slim")
	c := register(t, h, "maryanne")

	// an older hub: no ring endpoints
	if _, err := c.ActiveRing(ctx); !errors.Is(err, hub.ErrRingUnsupported) {
		t.Fatalf("expected unsupported, got %v", err)
	}
	if err := c.RingDevice(ctx, phone.ID); !errors.Is(err, hub.ErrRingUnsupported) || err.Error() != "ring not supported by this hub" {
		t.Fatalf("ring on old hub: %v", err)
	}

	h.Ring = true
	if r, err := c.ActiveRing(ctx); err != nil || r != nil {
		t.Fatalf("no ring yet: %+v %v", r, err)
	}
	if err := c.RingDevice(ctx, "hub"); err != nil || h.Rang[0] != "hub" {
		t.Fatalf("ring hub: %v", err)
	}
	if err := c.RingDevice(ctx, "missing"); err == nil {
		t.Fatal("ringing a missing device should fail")
	}
	// the phone rings this PC
	pc, _ := hub.New(h.Server.URL, phone.Token, "")
	me := h.Devices[len(h.Devices)-1]
	if err := pc.RingDevice(ctx, me.ID); err != nil {
		t.Fatal(err)
	}
	r, err := c.ActiveRing(ctx)
	if err != nil || r == nil || r.From != "slim" {
		t.Fatalf("active ring: %+v %v", r, err)
	}
	if err := c.StopRing(ctx); err != nil {
		t.Fatal(err)
	}
	if r, _ := c.ActiveRing(ctx); r != nil {
		t.Fatal("ring should be stopped")
	}
}

func TestResolveAndURLs(t *testing.T) {
	devs := []hub.Device{{ID: "a1", Name: "slim"}, {ID: "b2", Name: "maryanne", Self: true}}
	for in, want := range map[string]string{"hub": "hub", "HUB": "hub", "slim": "a1", "SLIM": "a1", "a1": "a1"} {
		if id, _, err := hub.Resolve(in, devs); err != nil || id != want {
			t.Errorf("Resolve(%q) = %q, %v", in, id, err)
		}
	}
	if _, _, err := hub.Resolve("ghost", devs); err == nil {
		t.Error("unknown names should fail")
	}
	for in, want := range map[string]string{
		"t15.tail7375fe.ts.net":            "https://t15.tail7375fe.ts.net",
		" https://t15.tail7375fe.ts.net/ ": "https://t15.tail7375fe.ts.net",
		"http://192.168.1.5:8000":          "http://192.168.1.5:8000",
	} {
		u, err := hub.ParseHubURL(in)
		if err != nil || u.String() != want {
			t.Errorf("ParseHubURL(%q) = %v, %v", in, u, err)
		}
	}
	if _, err := hub.ParseHubURL("ftp://x"); err == nil {
		t.Error("ftp isn't a hub")
	}
	c, _ := hub.New("https://t15.tail7375fe.ts.net", "", "")
	if c.HubName() != "t15" {
		t.Error(c.HubName())
	}
	c, _ = hub.New("http://192.168.1.5:8000", "", "")
	if c.HubName() != "192.168.1.5" {
		t.Error(c.HubName())
	}
}
