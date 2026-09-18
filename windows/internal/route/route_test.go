package route

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
	"github.com/Ferinmtk/droplet/windows/internal/pin"
)

const (
	hubID    = "9b16173d305cd15a"
	otherID  = "0123456789abcdef"
	tailURL  = "https://t15.tail7375fe.ts.net"
	httpPort = 8000
)

var (
	fpA = strings.Repeat("a1", 32) // the pinned certificate
	fpB = strings.Repeat("b2", 32) // a regenerated one
)

func info(id, fp string, addrs ...string) *hub.Info {
	i := &hub.Info{ID: id, Name: "t15"}
	if fp != "" {
		i.Fingerprint = &fp
	}
	port := 8443
	i.LAN.HTTPSPort = &port
	i.LAN.HTTPPort = httpPort
	i.LAN.Addresses = addrs
	t := tailURL
	i.Tailnet = &t
	return i
}

// endpoint is how a fake address behaves.
type endpoint struct {
	info  *hub.Info
	err   error
	delay time.Duration
}

// fakeNet stands in for the network: addresses answer (or don't) as told,
// and mDNS returns the listed hubs.
type fakeNet struct {
	mu        sync.Mutex
	endpoints map[string]endpoint // base URL -> behaviour
	hubs      []lan.Hub
	browseErr error
	calls     []string
	browses   atomic.Int32
	t         *testing.T
}

func newFake(t *testing.T) *fakeNet {
	return &fakeNet{endpoints: map[string]endpoint{}, t: t}
}

func (f *fakeNet) set(base string, e endpoint) {
	f.mu.Lock()
	f.endpoints[base] = e
	f.mu.Unlock()
}

func (f *fakeNet) setHubs(h ...lan.Hub) {
	f.mu.Lock()
	f.hubs = h
	f.mu.Unlock()
}

func (f *fakeNet) called(base string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if c == base {
			return true
		}
	}
	return false
}

func (f *fakeNet) deps() Deps {
	return Deps{
		Browse: func(ctx context.Context, wait time.Duration, found func(lan.Hub) bool) ([]lan.Hub, error) {
			f.browses.Add(1)
			f.mu.Lock()
			hubs, err := append([]lan.Hub(nil), f.hubs...), f.browseErr
			f.mu.Unlock()
			if err != nil {
				return nil, err
			}
			for _, h := range hubs {
				if found != nil && found(h) {
					return hubs, nil
				}
			}
			return hubs, nil
		},
		Info: func(ctx context.Context, base string, rt http.RoundTripper) (*hub.Info, error) {
			f.mu.Lock()
			f.calls = append(f.calls, base)
			e, ok := f.endpoints[base]
			f.mu.Unlock()
			lanAddr := !strings.Contains(base, ".ts.net")
			if lanAddr && strings.HasPrefix(base, "https://") && rt == nil {
				f.t.Errorf("%s was asked without the pinned transport", base)
			}
			if !lanAddr && rt != nil {
				f.t.Errorf("%s (the tailnet) was asked with a custom transport", base)
			}
			if !ok {
				return nil, fmt.Errorf("dial tcp %s: connection refused", base)
			}
			if e.delay > 0 {
				select {
				case <-time.After(e.delay):
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			return e.info, e.err
		},
	}
}

func announce(id, fp, ip string) lan.Hub {
	return lan.Hub{Instance: "droplet-" + id[:6], Port: 8443, Addrs: []netip.Addr{netip.MustParseAddr(ip)},
		TXT: lan.TXT{ID: id, Fingerprint: fp, Name: "t15", HTTPPort: httpPort, Tailnet: tailURL}}
}

var fast = Options{LANTimeout: 300 * time.Millisecond, BrowseWait: 300 * time.Millisecond,
	RemoteTimeout: time.Second, MaxLastAddrs: 3}

func ident(lanAddrs ...string) Identity {
	return Identity{ID: hubID, Fingerprint: fpA, LAN: lanAddrs, Remote: tailURL}
}

func TestSelectLANFromMDNS(t *testing.T) {
	f := newFake(t)
	f.setHubs(announce(hubID, fpA, "192.168.1.5"))
	f.set("https://192.168.1.5:8443", endpoint{info: info(hubID, fpA, "192.168.1.5")})
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	res, err := Select(context.Background(), ident(), f.deps(), fast)
	if err != nil {
		t.Fatal(err)
	}
	if res.Route.Kind != LAN || res.Route.Addr != "192.168.1.5:8443" || res.Route.Pin != fpA || res.Route.Label() != "on Wi-Fi" {
		t.Fatalf("route: %+v", res.Route)
	}
	if res.Route.Transport() == nil {
		t.Fatal("a LAN route must have the pinned transport")
	}
}

func TestSelectLastAddressWithoutMDNS(t *testing.T) {
	f := newFake(t)
	f.browseErr = errors.New("mDNS blocked on this network")
	f.set("https://192.168.1.9:8443", endpoint{info: info(hubID, fpA)})
	res, err := Select(context.Background(), ident("192.168.1.9:8443"), f.deps(), fast)
	if err != nil || res.Route.Kind != LAN || res.Route.Addr != "192.168.1.9:8443" {
		t.Fatalf("route: %+v, %v", res.Route, err)
	}
}

func TestSelectFindsHubAfterDHCPMovedIt(t *testing.T) {
	f := newFake(t)
	// the old address is gone; mDNS knows the new one
	f.setHubs(announce(hubID, fpA, "192.168.1.30"))
	f.set("https://192.168.1.30:8443", endpoint{info: info(hubID, fpA)})
	res, err := Select(context.Background(), ident("192.168.1.9:8443"), f.deps(), fast)
	if err != nil || res.Route.Addr != "192.168.1.30:8443" {
		t.Fatalf("route: %+v, %v", res.Route, err)
	}
}

func TestSelectPrefersLANEvenIfTailnetAnswersFirst(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	f.set("https://192.168.1.9:8443", endpoint{info: info(hubID, fpA), delay: 150 * time.Millisecond})
	res, err := Select(context.Background(), ident("192.168.1.9:8443"), f.deps(), fast)
	if err != nil || res.Route.Kind != LAN {
		t.Fatalf("route: %+v, %v", res.Route, err)
	}
}

func TestSelectFallsBackToTailnet(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	// away from home: nothing on the LAN answers, and the old address times out
	f.set("https://192.168.1.9:8443", endpoint{info: info(hubID, fpA), delay: time.Hour})
	start := time.Now()
	res, err := Select(context.Background(), ident("192.168.1.9:8443"), f.deps(), fast)
	if err != nil || res.Route.Kind != Remote || res.Route.Base != tailURL || res.Route.Label() != "via Tailscale" {
		t.Fatalf("route: %+v, %v", res.Route, err)
	}
	if res.Route.Transport() != nil {
		t.Fatal("the tailnet route must use normal TLS")
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("fallback took %s", d)
	}
	if res.Changed != nil {
		t.Fatalf("no identity change here: %+v", res.Changed)
	}
}

func TestSelectIgnoresOtherHubs(t *testing.T) {
	f := newFake(t)
	f.setHubs(announce(otherID, fpB, "192.168.1.7"))
	f.set("https://192.168.1.7:8443", endpoint{info: info(otherID, fpB)})
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	res, err := Select(context.Background(), ident(), f.deps(), fast)
	if err != nil || res.Route.Kind != Remote {
		t.Fatalf("route: %+v, %v", res.Route, err)
	}
	if f.called("https://192.168.1.7:8443") {
		t.Fatal("contacted someone else's hub")
	}
}

func TestSelectRefusesARemoteThatIsAnotherHub(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{info: info(otherID, fpB)})
	_, err := Select(context.Background(), ident(), f.deps(), fast)
	if !errors.Is(err, ErrUnreachable) {
		t.Fatalf("want unreachable, got %v", err)
	}
}

func TestSelectOldHubWithoutInfo(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{err: hub.ErrNotFound})
	res, err := Select(context.Background(), Identity{Remote: tailURL}, f.deps(), fast)
	if err != nil || res.Route.Kind != Remote {
		t.Fatalf("route: %+v, %v", res.Route, err)
	}
}

func TestSelectAnnouncedCertificateChanged(t *testing.T) {
	f := newFake(t)
	// the hub announces its id with a new fingerprint; the tailnet confirms it
	f.setHubs(announce(hubID, fpB, "192.168.1.5"))
	f.set("https://192.168.1.5:8443", endpoint{info: info(hubID, fpB)})
	f.set(tailURL, endpoint{info: info(hubID, fpB)})
	res, err := Select(context.Background(), ident(), f.deps(), fast)
	if err != nil || res.Route.Kind != Remote {
		t.Fatalf("never switch silently: route %+v, %v", res.Route, err)
	}
	if f.called("https://192.168.1.5:8443") {
		t.Fatal("connected to an address announcing another certificate")
	}
	c := res.Changed
	if c == nil || c.Want != fpA || c.Got != fpB || !c.Verified || c.Addr != "192.168.1.5:8443" {
		t.Fatalf("changed: %+v", c)
	}

	// without the tailnet: an error saying so, not a route
	f.set(tailURL, endpoint{err: errors.New("no such host")})
	_, err = Select(context.Background(), ident(), f.deps(), fast)
	var ue *UnreachableError
	if !errors.As(err, &ue) || ue.Changed == nil || ue.Changed.Verified || !strings.Contains(err.Error(), "identity changed") {
		t.Fatalf("want an identity-changed error, got %v", err)
	}
}

func TestSelectLANOnlySkipsTheTailnet(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	o := fast
	o.LANOnly = true
	_, err := Select(context.Background(), ident(), f.deps(), o)
	if err == nil || f.called(tailURL) {
		t.Fatalf("LAN-only selection used the tailnet (err %v)", err)
	}
}

func TestSelectNothingWorks(t *testing.T) {
	f := newFake(t)
	_, err := Select(context.Background(), ident("192.168.1.9:8443"), f.deps(), fast)
	var ue *UnreachableError
	if !errors.As(err, &ue) || !errors.Is(err, ErrUnreachable) || ue.Changed != nil {
		t.Fatalf("got %v", err)
	}
	if !strings.Contains(ue.Detail(), "192.168.1.9:8443") || !strings.Contains(ue.Detail(), tailURL) {
		t.Fatalf("detail: %s", ue.Detail())
	}
}

// --- the real pinned transport, against a TLS server --------------------------------

func infoServer(t *testing.T, id string) (*httptest.Server, string) {
	t.Helper()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/hub/info" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Errorf("a probe sent credentials")
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"id": id, "name": "t15", "lan": map[string]any{"addresses": []string{}}})
	}))
	t.Cleanup(srv.Close)
	return srv, pin.Fingerprint(srv.Certificate().Raw)
}

func TestSelectRealPinnedTLS(t *testing.T) {
	srv, fp := infoServer(t, hubID)
	addr := strings.TrimPrefix(srv.URL, "https://")
	d := DefaultDeps()
	d.Browse = nil

	id := Identity{ID: hubID, Fingerprint: fp, LAN: []string{addr}}
	res, err := Select(context.Background(), id, d, fast)
	if err != nil || res.Route.Kind != LAN || res.Route.Addr != addr {
		t.Fatalf("pinned route: %+v, %v", res.Route, err)
	}
	c, _ := res.Route.Client("", "")
	if _, err := c.HubInfo(context.Background()); err != nil {
		t.Fatalf("client along the route: %v", err)
	}

	// the same server, but we're pinned to another certificate
	id.Fingerprint = fpB
	_, err = Select(context.Background(), id, d, fast)
	var ue *UnreachableError
	if !errors.As(err, &ue) || ue.Changed == nil || ue.Changed.Got != fp || ue.Changed.Want != fpB || ue.Changed.Addr != addr {
		t.Fatalf("want identity changed, got %v", err)
	}
}

// --- migration -----------------------------------------------------------------------

func TestMigrateOverTailnet(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{info: info(hubID, strings.ToUpper(fpA), "192.168.1.5")})
	h, err := Migrate(context.Background(), tailURL, f.deps(), fast)
	if err != nil {
		t.Fatal(err)
	}
	want := config.Hub{ID: hubID, Name: "t15", Fingerprint: fpA, PinSource: config.PinFromTailnet,
		LAN: []string{"192.168.1.5:8443"}, HTTPPort: httpPort, Tailnet: tailURL}
	if fmt.Sprint(*h) != fmt.Sprint(want) {
		t.Fatalf("got  %+v\nwant %+v", *h, want)
	}
	if f.browses.Load() != 0 {
		t.Fatal("no need for mDNS when the tailnet answers")
	}
}

func TestMigrateOldHub(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{err: hub.ErrNotFound})
	if _, err := Migrate(context.Background(), tailURL, f.deps(), fast); !errors.Is(err, ErrNoIdentity) {
		t.Fatalf("got %v", err)
	}
}

func TestMigrateOnLANTrustOnFirstUse(t *testing.T) {
	f := newFake(t)
	// Tailscale is off; the hub announces our tailnet URL, so it's the one
	f.setHubs(announce(otherID, fpB, "192.168.1.7"), announce(hubID, fpA, "192.168.1.5"))
	f.hubs[0].Tailnet = "https://other.tail0000.ts.net"
	f.set("https://192.168.1.5:8443", endpoint{info: info(hubID, "", "192.168.1.5")})
	h, err := Migrate(context.Background(), tailURL, f.deps(), fast)
	if err != nil {
		t.Fatal(err)
	}
	if h.ID != hubID || h.Fingerprint != fpA || h.PinSource != config.PinFromLAN || h.LAN[0] != "192.168.1.5:8443" || h.Tailnet != tailURL {
		t.Fatalf("identity: %+v", h)
	}
	if f.called("https://192.168.1.7:8443") {
		t.Fatal("probed a hub that isn't ours")
	}
}

func TestMigratePlainLANURL(t *testing.T) {
	f := newFake(t)
	f.setHubs(announce(hubID, fpA, "192.168.1.5"))
	f.set("https://192.168.1.5:8443", endpoint{info: info(hubID, fpA)})
	h, err := Migrate(context.Background(), "http://192.168.1.5:8000", f.deps(), fast)
	if err != nil || h.ID != hubID || h.PinSource != config.PinFromLAN {
		t.Fatalf("identity: %+v, %v", h, err)
	}
	// an http URL is never asked for a fingerprint (anyone could answer)
	if f.called("http://192.168.1.5:8000") {
		t.Fatal("asked the plain-HTTP URL for the identity")
	}
}

func TestMigrateRefusesUntiedOrAmbiguousHubs(t *testing.T) {
	f := newFake(t)
	f.setHubs(announce(otherID, fpB, "192.168.1.7"))
	f.hubs[0].Tailnet = ""
	f.set("https://192.168.1.7:8443", endpoint{info: info(otherID, fpB)})
	if _, err := Migrate(context.Background(), tailURL, f.deps(), fast); err == nil {
		t.Fatal("adopted a hub with nothing tying it to the configured one")
	}
	// two different hubs both claiming our tailnet URL
	f.setHubs(announce(hubID, fpA, "192.168.1.5"), announce(otherID, fpB, "192.168.1.7"))
	if _, err := Migrate(context.Background(), tailURL, f.deps(), fast); err == nil {
		t.Fatal("picked one of two hubs claiming the same URL")
	}
	// the hub's certificate doesn't match what it announces
	f.setHubs(announce(hubID, fpA, "192.168.1.5"))
	f.set("https://192.168.1.5:8443", endpoint{err: &pin.MismatchError{Want: fpA, Got: fpB}})
	if _, err := Migrate(context.Background(), tailURL, f.deps(), fast); err == nil {
		t.Fatal("adopted a hub whose certificate doesn't match its announcement")
	}
}

func TestRemember(t *testing.T) {
	h := config.Hub{ID: hubID, Fingerprint: fpA, LAN: []string{"192.168.1.9:8443", "192.168.1.8:8443"}}
	res := Result{Route: Route{Kind: LAN, Addr: "192.168.1.30:8443"}, Info: info(hubID, fpA, "192.168.1.30", "10.0.0.2")}
	got, changed := Remember(h, res, tailURL)
	if !changed || strings.Join(got.LAN, ",") != "192.168.1.30:8443,10.0.0.2:8443,192.168.1.9:8443,192.168.1.8:8443" {
		t.Fatalf("LAN: %v", got.LAN)
	}
	if got.Tailnet != tailURL || got.HTTPPort != httpPort || got.Name != "t15" {
		t.Fatalf("identity: %+v", got)
	}
	if _, changed := Remember(got, res, tailURL); changed {
		t.Fatal("nothing new, but reported a change")
	}
	// a new fingerprint from the tailnet is never adopted by Remember
	res = Result{Route: Route{Kind: Remote, Base: tailURL}, Info: info(hubID, fpB)}
	if got, _ := Remember(h, res, tailURL); got.Fingerprint != fpA {
		t.Fatal("Remember replaced the pin")
	}
	// but a first one, over verified TLS, is
	h.Fingerprint = ""
	if got, _ := Remember(h, res, tailURL); got.Fingerprint != fpB || got.PinSource != config.PinFromTailnet {
		t.Fatalf("first pin from the tailnet: %+v", got)
	}
	// and not over plain HTTP
	if got, _ := Remember(h, res, "http://192.168.1.5:8000"); got.Fingerprint != "" {
		t.Fatal("pinned from plain HTTP")
	}
	// another hub's answer changes nothing
	res.Info = info(otherID, fpB, "10.9.9.9")
	if got, _ := Remember(config.Hub{ID: hubID}, res, tailURL); len(got.LAN) != 0 || got.Fingerprint != "" {
		t.Fatalf("learnt from another hub: %+v", got)
	}
}

// --- the manager -------------------------------------------------------------------------

type recorder struct {
	mu     sync.Mutex
	routes []Route
}

func (r *recorder) add(rt Route) { r.mu.Lock(); r.routes = append(r.routes, rt); r.mu.Unlock() }
func (r *recorder) last() Route {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.routes) == 0 {
		return Route{}
	}
	return r.routes[len(r.routes)-1]
}

func newTestManager(f *fakeNet, id Identity) (*Manager, *recorder) {
	rec := &recorder{}
	var mu sync.Mutex
	m := &Manager{Deps: f.deps(), Options: fast, OnChange: rec.add, Logf: f.t.Logf}
	m.Identity = func() Identity { mu.Lock(); defer mu.Unlock(); return id }
	return m, rec
}

func TestManagerEnsureAndLost(t *testing.T) {
	f := newFake(t)
	f.set("https://192.168.1.9:8443", endpoint{info: info(hubID, fpA)})
	m, rec := newTestManager(f, ident("192.168.1.9:8443"))
	r, err := m.Ensure(context.Background())
	if err != nil || r.Kind != LAN {
		t.Fatalf("%+v %v", r, err)
	}
	calls := len(f.calls)
	if r2, _ := m.Ensure(context.Background()); r2 != r || len(f.calls) != calls {
		t.Fatal("Ensure should reuse the current route")
	}
	// the LAN goes away; the tailnet is there
	f.set("https://192.168.1.9:8443", endpoint{err: errors.New("connection refused")})
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	m.Lost(Route{Kind: LAN, Base: "https://somewhere-else"}) // not the current one: ignored
	if cur, _ := m.Current(); cur != r {
		t.Fatal("Lost of another route dropped the current one")
	}
	m.Lost(r)
	if _, ok := m.Current(); ok {
		t.Fatal("still on a lost route")
	}
	r, err = m.Ensure(context.Background())
	if err != nil || r.Kind != Remote || rec.last() != r {
		t.Fatalf("after losing the LAN: %+v %v", r, err)
	}
}

func TestManagerLegacyHub(t *testing.T) {
	f := newFake(t)
	m, _ := newTestManager(f, Identity{Remote: "http://127.0.0.1:8000"})
	r, err := m.Ensure(context.Background())
	if err != nil || r.Kind != Remote || r.Base != "http://127.0.0.1:8000" || len(f.calls) != 0 {
		t.Fatalf("legacy: %+v %v (calls %v)", r, err, f.calls)
	}
	m2, _ := newTestManager(f, Identity{})
	if _, err := m2.Ensure(context.Background()); !errors.Is(err, ErrNotPaired) {
		t.Fatalf("no hub: %v", err)
	}
}

func TestManagerVerifiedChangeFromTailnet(t *testing.T) {
	f := newFake(t)
	f.set(tailURL, endpoint{info: info(hubID, fpB)}) // the hub now has another certificate
	m, _ := newTestManager(f, ident())
	r, err := m.Ensure(context.Background())
	if err != nil || r.Kind != Remote {
		t.Fatalf("%+v %v", r, err)
	}
	c := m.Changed()
	if c == nil || !c.Verified || c.Got != fpB || c.Want != fpA {
		t.Fatalf("changed: %+v", c)
	}
	m.ClearChanged()
	if m.Changed() != nil {
		t.Fatal("not cleared")
	}
}

func TestManagerRunMovesToLAN(t *testing.T) {
	oldPoll, oldSettle, oldLook := netPollEvery, settleDelay, lanLookEvery
	netPollEvery, settleDelay, lanLookEvery = 20*time.Millisecond, 20*time.Millisecond, time.Hour
	defer func() { netPollEvery, settleDelay, lanLookEvery = oldPoll, oldSettle, oldLook }()

	f := newFake(t)
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	m, rec := newTestManager(f, ident())
	var sig atomic.Value
	sig.Store("mobile-data")
	m.NetSignature = func() string { return sig.Load().(string) }
	if r, _ := m.Ensure(context.Background()); r.Kind != Remote {
		t.Fatalf("start: %+v", r)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { m.Run(ctx); close(done) }()
	defer func() { cancel(); <-done }()
	time.Sleep(100 * time.Millisecond) // Run has read the starting signature

	// home: Wi-Fi connects and the hub is on it
	f.setHubs(announce(hubID, fpA, "192.168.1.5"))
	f.set("https://192.168.1.5:8443", endpoint{info: info(hubID, fpA)})
	sig.Store("wifi")
	waitFor(t, func() bool { return rec.last().Kind == LAN })

	// out again: Wi-Fi goes, the LAN route is dropped for the tailnet
	f.setHubs()
	f.set("https://192.168.1.5:8443", endpoint{err: errors.New("no route to host")})
	sig.Store("mobile-data-again")
	waitFor(t, func() bool { return rec.last().Kind == Remote })
}

func TestManagerRunLooksForLANPeriodically(t *testing.T) {
	oldPoll, oldLook := netPollEvery, lanLookEvery
	netPollEvery, lanLookEvery = 10*time.Millisecond, 50*time.Millisecond
	defer func() { netPollEvery, lanLookEvery = oldPoll, oldLook }()

	f := newFake(t)
	f.set(tailURL, endpoint{info: info(hubID, fpA)})
	m, rec := newTestManager(f, ident())
	m.NetSignature = func() string { return "same" }
	m.Ensure(context.Background())
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { m.Run(ctx); close(done) }()
	defer func() { cancel(); <-done }()

	// the network looks unchanged (say, a Wi-Fi that was already joined
	// before the hub came up), but the hub appears
	f.setHubs(announce(hubID, fpA, "192.168.1.5"))
	f.set("https://192.168.1.5:8443", endpoint{info: info(hubID, fpA)})
	waitFor(t, func() bool { return rec.last().Kind == LAN })
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("timed out")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestLabels(t *testing.T) {
	for base, want := range map[string]string{
		"https://t15.tail7375fe.ts.net": "via Tailscale",
		"http://100.101.102.103:8000":   "via Tailscale",
		"http://192.168.100.20:8000":    "via 192.168.100.20:8000",
	} {
		if got := (Route{Kind: Remote, Base: base}).Label(); got != want {
			t.Errorf("%s: %q, want %q", base, got, want)
		}
	}
}
