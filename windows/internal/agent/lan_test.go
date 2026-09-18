package agent

import (
	"context"
	"errors"
	"net/http"
	"net/netip"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/hubtest"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
	"github.com/Ferinmtk/droplet/windows/internal/pairing"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/route"
)

// lanSetup is a fresh PC and a hub on the "LAN": a TLS fake hub that makes
// new devices wait, announced over (fake) mDNS with its real fingerprint.
func lanSetup(t *testing.T) (*Agent, *hubtest.Hub, *captured, *atomic.Int32) {
	t.Helper()
	h := hubtest.NewTLS()
	h.LANGate = true
	t.Cleanup(h.Server.Close)
	store, err := config.OpenPath(filepath.Join(t.TempDir(), "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	a := New(store, "droplet.exe")
	announce(a, h, h.FP)
	cap := &captured{}
	notify = func(n platform.Notification) { cap.mu.Lock(); cap.n = append(cap.n, n); cap.mu.Unlock() }
	t.Cleanup(func() { notify = platform.Notify })
	opened := &atomic.Int32{}
	a.OnNeedPairing = func() { opened.Add(1) }
	return a, h, cap, opened
}

// announce makes the agent's mDNS find h, announcing fingerprint fp.
func announce(a *Agent, h *hubtest.Hub, fp string) {
	host, port, _ := strings.Cut(h.Addr(), ":")
	p, _ := strconv.Atoi(port)
	ann := lan.Hub{Instance: "droplet-a1b2c3", Port: p, Addrs: []netip.Addr{netip.MustParseAddr(host)},
		TXT: lan.TXT{ID: h.ID, Fingerprint: fp, Name: "hubtest", HTTPPort: 8000}}
	a.Routes.Deps.Browse = func(ctx context.Context, wait time.Duration, found func(lan.Hub) bool) ([]lan.Hub, error) {
		if found != nil {
			found(ann)
		}
		return []lan.Hub{ann}, nil
	}
}

func titles(ns []platform.Notification) string {
	var out []string
	for _, n := range ns {
		out = append(out, n.Title)
	}
	return strings.Join(out, " | ")
}

func TestJoinOnLANThenApproved(t *testing.T) {
	a, h, cap, _ := lanSetup(t)
	ctx := context.Background()
	found, err := a.Discover(ctx)
	if err != nil || len(found) != 1 || found[0].ID != h.ID || found[0].Paired {
		t.Fatalf("discover: %+v %v", found, err)
	}
	res, err := a.Join(ctx, h.ID, "maryanne", "", false)
	if err != nil {
		t.Fatal(err)
	}
	dev := h.Find("maryanne")
	if !res.Pending || res.Code == "" || res.Code != dev.Code() {
		t.Fatalf("join: %+v (hub code %s)", res, dev.Code())
	}
	cfg := a.Store.Get()
	if !cfg.Pending() || cfg.Registered() || cfg.PairCode != res.Code || cfg.DeviceToken != dev.Token {
		t.Fatalf("config after join: %+v", cfg)
	}
	if cfg.Hub == nil || cfg.Hub.ID != h.ID || cfg.Hub.Fingerprint != h.FP || cfg.Hub.PinSource != config.PinFromLAN ||
		len(cfg.Hub.LAN) == 0 || cfg.Hub.LAN[0] != h.Addr() {
		t.Fatalf("identity after join: %+v", cfg.Hub)
	}
	if cfg.HubURL != "" {
		t.Fatalf("a LAN-only hub must not keep the default tailnet URL: %q", cfg.HubURL)
	}

	// waiting: the poll asks /api/me, and shows the code
	if err := a.tick(ctx); err != nil {
		t.Fatal(err)
	}
	st := a.Status()
	if !st.Pending || st.PairCode != res.Code || !strings.Contains(st.Tooltip(), res.Code) {
		t.Fatalf("pending status: %+v / %s", st, st.Tooltip())
	}
	if p := a.RemoteParams(); p.Token != "" {
		t.Fatal("no live connection while waiting to be let in")
	}

	h.Approve(dev)
	if err := a.tick(ctx); err != nil {
		t.Fatal(err)
	}
	if !a.Store.Get().Registered() || !strings.Contains(titles(cap.take()), "now part of droplet") {
		t.Fatal("approval not noticed")
	}
	if err := a.tick(ctx); err != nil {
		t.Fatalf("polling after approval: %v", err)
	}
	st = a.Status()
	if !st.Connected || st.Route != "on Wi-Fi" || st.Tooltip() != "droplet — hubtest on Wi-Fi" {
		t.Fatalf("status: %+v / %s", st, st.Tooltip())
	}
	p := a.RemoteParams()
	if p.Token == "" || p.HubURL != "https://"+h.Addr() || p.Transport == nil {
		t.Fatalf("live connection params: %+v", p)
	}
	// a browser can't use the pinned connection: it gets the plain-HTTP LAN address
	if u := a.WebURL("/#inbox"); !strings.HasPrefix(u, "http://127.0.0.1:8000/") {
		t.Fatalf("web URL: %s", u)
	}
	if found, _ := a.Discover(ctx); !found[0].Paired || found[0].Changed {
		t.Fatalf("discover after pairing: %+v", found)
	}
}

func TestJoinDeclined(t *testing.T) {
	a, h, cap, _ := lanSetup(t)
	ctx := context.Background()
	a.Discover(ctx)
	if _, err := a.Join(ctx, h.ID, "maryanne", "", false); err != nil {
		t.Fatal(err)
	}
	a.tick(ctx)
	h.Remove(h.Find("maryanne")) // denied
	if err := a.tick(ctx); err != nil {
		t.Fatal(err)
	}
	cfg := a.Store.Get()
	if cfg.DeviceToken != "" || cfg.Pending() || cfg.Registered() {
		t.Fatalf("a declined request must drop the token: %+v", cfg)
	}
	if !strings.Contains(titles(cap.take()), "wasn't let in") {
		t.Fatal("no notification")
	}
	if cfg.Hub == nil || cfg.Hub.ID != h.ID {
		t.Fatal("the hub's identity is kept, to ask again")
	}
}

func TestWaitForPairing(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	a.Discover(ctx)
	if _, err := a.Join(ctx, h.ID, "maryanne", "", false); err != nil {
		t.Fatal(err)
	}
	go func() {
		time.Sleep(300 * time.Millisecond)
		h.Approve(h.Find("maryanne"))
	}()
	var seen []string
	st, err := a.WaitForPairing(ctx, func(s pairing.State, code string) { seen = append(seen, string(s)) })
	if err != nil || st != pairing.Approved || strings.Join(seen, ",") != "pending,approved" {
		t.Fatalf("wait: %s %v %v", st, err, seen)
	}
}

func TestJoinWithPIN(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	h.PIN = "2468"
	ctx := context.Background()
	a.Discover(ctx)
	if _, err := a.Join(ctx, h.ID, "maryanne", "1111", false); err == nil {
		t.Fatal("a wrong PIN joined")
	} else if fe, ok := err.(*FieldError); !ok || fe.Field != "pin" {
		t.Fatalf("wrong PIN: %v", err)
	}
	res, err := a.Join(ctx, h.ID, "maryanne", "2468", false)
	if err != nil || res.Pending {
		t.Fatalf("with the PIN it's let in at once: %+v %v", res, err)
	}
	if cfg := a.Store.Get(); !cfg.Registered() || cfg.Session == "" {
		t.Fatalf("config: %+v", cfg)
	}
	if err := a.tick(ctx); err != nil || !a.Status().Connected {
		t.Fatalf("poll: %v", err)
	}
}

func TestSignInWithPINWhileWaiting(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	h.PIN = "2468"
	ctx := context.Background()
	a.Discover(ctx)
	if res, err := a.Join(ctx, h.ID, "maryanne", "", false); err != nil || !res.Pending {
		t.Fatalf("join: %+v %v", res, err)
	}
	if err := a.SignInWithPIN(ctx, "0000"); err == nil {
		t.Fatal("wrong PIN accepted")
	}
	if err := a.SignInWithPIN(ctx, "2468"); err != nil {
		t.Fatal(err)
	}
	a.tick(ctx)
	if !a.Store.Get().Registered() {
		t.Fatal("the PIN should have let it in")
	}
}

func TestJoinNameTakenAndCancel(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	h.AddDevice("maryanne")
	ctx := context.Background()
	a.Discover(ctx)
	_, err := a.Join(ctx, h.ID, "Maryanne", "", false)
	if fe, ok := err.(*FieldError); !ok || fe.Field != "name" {
		t.Fatalf("name clash: %v", err)
	}
	if _, err := a.Join(ctx, h.ID, "maryanne-2", "", false); err != nil {
		t.Fatal(err)
	}
	if err := a.CancelJoin(); err != nil || a.Store.Get().DeviceToken != "" {
		t.Fatalf("cancel: %v", err)
	}
}

func TestJoinRefusesACertificateThatIsntAnnounced(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	announce(a, h, strings.Repeat("ab", 32)) // mDNS says one thing, TLS another
	ctx := context.Background()
	a.Discover(ctx)
	_, err := a.Join(ctx, h.ID, "maryanne", "", false)
	if fe, ok := err.(*FieldError); !ok || fe.Field != "hub" || !strings.Contains(fe.Msg, "may not be your hub") {
		t.Fatalf("got %v", err)
	}
	if len(h.Devices) != 0 || a.Store.Get().DeviceToken != "" {
		t.Fatal("registered with a hub whose certificate didn't match")
	}
}

func TestNotAllowedAnymore(t *testing.T) {
	a, h, cap, opened := lanSetup(t)
	ctx := context.Background()
	a.Discover(ctx)
	h.PIN = "2468"
	if _, err := a.Join(ctx, h.ID, "maryanne", "2468", false); err != nil {
		t.Fatal(err)
	}
	h.PIN = ""
	a.Store.Update(func(c *config.Config) { c.Session = "" }) // only the token now
	a.Reset()
	if err := a.tick(ctx); err != nil {
		t.Fatal(err)
	}
	h.Remove(h.Find("maryanne")) // removed under Devices
	if err := a.tick(ctx); !errors.Is(err, hub.ErrNotAllowed) {
		t.Fatalf("want ErrNotAllowed, got %v", err)
	}
	st := a.Status()
	if !st.NotAllowed || !strings.Contains(st.Tooltip(), "doesn't let this PC in") {
		t.Fatalf("status: %+v", st)
	}
	if !strings.Contains(titles(cap.take()), "isn't let in") || opened.Load() != 1 {
		t.Fatal("should notify and open Settings")
	}
	a.tick(ctx)
	if len(cap.take()) != 0 || opened.Load() != 1 {
		t.Fatal("only once")
	}
	// an action refused the same way gets the poll loop to look
	if _, err := a.Client(); err != nil {
		t.Fatal(err)
	}
}

// trustTestCA makes the "tailnet" (https to the fake hub, not pinned)
// verified the normal way, by trusting the test server's CA.
func trustTestCA(a *Agent, h *hubtest.Hub) {
	info := a.Routes.Deps.Info
	a.Routes.Deps.Info = func(ctx context.Context, base string, rt http.RoundTripper) (*hub.Info, error) {
		if rt == nil {
			rt = h.Server.Client().Transport
		}
		return info(ctx, base, rt)
	}
}

func TestMigrationOverTheTailnetThenLAN(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	trustTestCA(a, h)
	h.LANGate = false
	d := h.AddDevice("maryanne")
	// an older config: a URL and a token
	a.Store.Update(func(c *config.Config) {
		c.HubURL, c.DeviceToken, c.DeviceID, c.DeviceName = h.Server.URL, d.Token, d.ID, d.Name
	})
	ctx := context.Background()
	if err := a.tick(ctx); err != nil {
		t.Fatal(err)
	}
	cfg := a.Store.Get()
	if cfg.Hub == nil || cfg.Hub.ID != h.ID || cfg.Hub.Fingerprint != h.FP || cfg.Hub.PinSource != config.PinFromTailnet {
		t.Fatalf("migrated identity: %+v", cfg.Hub)
	}
	if st := a.Status(); !st.Connected || st.Route != "on Wi-Fi" {
		t.Fatalf("after migrating, the LAN should be used: %+v", st)
	}
	if cfg.HubURL != h.Server.URL || !cfg.Registered() {
		t.Fatal("the rest of the config must be kept")
	}
}

func TestIdentityChangedNeverSwitchesSilently(t *testing.T) {
	a, h, cap, opened := lanSetup(t)
	d := h.AddDevice("maryanne")
	h.LANGate = false
	oldFP := strings.Repeat("cd", 32)
	// paired with the hub when it had another certificate; no tailnet
	a.Store.Update(func(c *config.Config) {
		c.HubURL = ""
		c.Hub = &config.Hub{ID: h.ID, Name: "hubtest", Fingerprint: oldFP, PinSource: config.PinFromLAN, LAN: []string{h.Addr()}}
		c.DeviceToken, c.DeviceID, c.DeviceName = d.Token, d.ID, d.Name
	})
	ctx := context.Background()
	err := a.tick(ctx)
	var ue *route.UnreachableError
	if !errors.As(err, &ue) || ue.Changed == nil {
		t.Fatalf("want an identity change, got %v", err)
	}
	st := a.Status()
	if st.Connected || !st.IdentityChanged || !strings.Contains(st.Problem, "identity") {
		t.Fatalf("status: %+v", st)
	}
	if !strings.Contains(titles(cap.take()), "identity changed") || opened.Load() != 1 {
		t.Fatal("should notify and open Settings")
	}
	a.tick(ctx)
	if len(cap.take()) != 0 {
		t.Fatal("notify once per certificate")
	}
	if a.Store.Get().Hub.Fingerprint != oldFP {
		t.Fatal("the pin changed by itself")
	}

	// no tailnet to vouch for the new certificate: re-pairing means joining again
	res, err := a.Repair(ctx)
	if err != nil || res.Done || !res.Join {
		t.Fatalf("repair without the tailnet: %+v %v", res, err)
	}
	// joining again without saying so is refused
	a.Discover(ctx)
	if _, err := a.Join(ctx, h.ID, "maryanne-new", "", false); err == nil {
		t.Fatal("joined a hub with a changed identity without re-pairing")
	}
	jr, err := a.Join(ctx, h.ID, "maryanne-new", "", true)
	if err != nil {
		t.Fatal(err)
	}
	cfg := a.Store.Get()
	if cfg.Hub.Fingerprint != h.FP || cfg.DeviceToken == d.Token || jr.Name != "maryanne-new" {
		t.Fatalf("after re-pairing: %+v", cfg)
	}
	if err := a.tick(ctx); err != nil || !a.Status().Connected || a.Status().IdentityChanged {
		t.Fatalf("after re-pairing: %v %+v", err, a.Status())
	}
}

func TestRepairOverTheTailnet(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	trustTestCA(a, h)
	d := h.AddDevice("maryanne")
	h.LANGate = false
	oldFP := strings.Repeat("cd", 32)
	a.Store.Update(func(c *config.Config) {
		c.HubURL = h.Server.URL // "the tailnet": verified TLS (the test CA)
		c.Hub = &config.Hub{ID: h.ID, Name: "hubtest", Fingerprint: oldFP, PinSource: config.PinFromTailnet, LAN: []string{h.Addr()}}
		c.DeviceToken, c.DeviceID, c.DeviceName = d.Token, d.ID, d.Name
	})
	ctx := context.Background()
	// the pinned LAN route fails, so it goes via the "tailnet", which says
	// the certificate changed: usable, but flagged, and the pin is kept
	r, err := a.Routes.Reselect(ctx)
	if err != nil || r.Kind != route.Remote {
		t.Fatalf("route: %+v %v", r, err)
	}
	if a.Store.Get().Hub.Fingerprint != oldFP {
		t.Fatal("the pin changed by itself")
	}
	if ch := a.Routes.Changed(); ch == nil || !ch.Verified || ch.Got != h.FP {
		t.Fatalf("changed: %+v", ch)
	}
	res, err := a.Repair(ctx)
	if err != nil || !res.Done {
		t.Fatalf("repair: %+v %v", res, err)
	}
	cfg := a.Store.Get()
	if cfg.Hub.Fingerprint != h.FP || cfg.DeviceToken != d.Token {
		t.Fatalf("re-pinned, same device: %+v", cfg)
	}
	if err := a.tick(ctx); err != nil {
		t.Fatal(err)
	}
	if st := a.Status(); st.Route != "on Wi-Fi" || st.IdentityChanged {
		t.Fatalf("after repair: %+v", st)
	}
}

func TestLinkOnLAN(t *testing.T) {
	a, h, _, _ := lanSetup(t)
	pc := h.AddDevice("maryanne") // this PC's browser, already let in
	code := h.LinkCode(pc)
	ctx := context.Background()
	a.Discover(ctx)
	res, err := a.LinkLAN(ctx, h.ID, code, "")
	if err != nil || res.ID != pc.ID {
		t.Fatalf("link: %+v %v", res, err)
	}
	cfg := a.Store.Get()
	if !cfg.Registered() || cfg.Hub == nil || cfg.Hub.ID != h.ID || cfg.HubURL != "" {
		t.Fatalf("config: %+v", cfg)
	}
	if err := a.tick(ctx); err != nil || a.Status().Route != "on Wi-Fi" {
		t.Fatalf("poll: %v %+v", err, a.Status())
	}
}
