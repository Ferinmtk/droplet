package route

import (
	"context"
	"errors"
	"log"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
)

// Timings for Manager.Run (vars so tests can hurry them).
var (
	netPollEvery = 4 * time.Second       // how often the interface list is compared
	settleDelay  = 1500 * time.Millisecond // after a change, let DHCP finish
	lanLookEvery = 3 * time.Minute       // while on the tailnet, look for the LAN
	errHoldoff   = 3 * time.Second       // don't redo a failed selection sooner
)

// ErrNotPaired is there being no hub to route to.
var ErrNotPaired = errors.New("no hub set up yet")

// Manager keeps the current route: chooses it on demand, notices when the
// network changes, and moves from the tailnet to the LAN when the hub
// appears there. It's safe for concurrent use.
type Manager struct {
	Deps    Deps
	Options Options
	// Identity reads the paired hub's identity (from the config) each time.
	Identity func() Identity
	// OnResult is called after each successful selection, to remember what
	// was learnt (the hub's current LAN address, its tailnet URL…).
	OnResult func(Result)
	// OnChange is called when the route changes, including to none.
	OnChange func(Route)
	// NetSignature describes the network interfaces; a change means the
	// route is chosen again. Defaults to InterfaceSignature.
	NetSignature func() string
	Logf         func(format string, args ...any)

	sel sync.Mutex // one selection at a time

	mu        sync.Mutex
	cur       Route
	changed   *Changed
	lastErr   error
	lastErrAt time.Time
	kick      chan struct{}
}

// NewManager makes a manager over the real network.
func NewManager(identity func() Identity) *Manager {
	return &Manager{Deps: DefaultDeps(), Options: DefaultOptions, Identity: identity}
}

func (m *Manager) logf(format string, args ...any) {
	if m.Logf != nil {
		m.Logf(format, args...)
		return
	}
	log.Printf(format, args...)
}

// Current is the route in use, if there is one.
func (m *Manager) Current() (Route, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.cur, !m.cur.IsZero()
}

// Changed is the hub's LAN identity having changed, or nil.
func (m *Manager) Changed() *Changed {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.changed == nil {
		return nil
	}
	c := *m.changed
	return &c
}

// ClearChanged forgets an identity change (after re-pairing).
func (m *Manager) ClearChanged() {
	m.mu.Lock()
	m.changed = nil
	m.mu.Unlock()
}

// LastError is why the last selection found no route, or nil.
func (m *Manager) LastError() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !m.cur.IsZero() {
		return nil
	}
	return m.lastErr
}

// Ensure returns the current route, choosing one if there's none.
func (m *Manager) Ensure(ctx context.Context) (Route, error) {
	if r, ok := m.Current(); ok {
		return r, nil
	}
	m.mu.Lock()
	err, at := m.lastErr, m.lastErrAt
	m.mu.Unlock()
	if err != nil && time.Since(at) < errHoldoff {
		return Route{}, err
	}
	return m.reselect(ctx, false, true)
}

// Reselect chooses the route again from scratch.
func (m *Manager) Reselect(ctx context.Context) (Route, error) {
	return m.reselect(ctx, false, false)
}

// Set makes r the route (it was just shown to work, e.g. by pairing).
func (m *Manager) Set(r Route) {
	m.setRoute(r, nil)
}

// Reset drops the route and any error, e.g. when the hub or its identity
// changed; the next Ensure chooses again.
func (m *Manager) Reset() {
	m.mu.Lock()
	m.lastErr = nil
	m.changed = nil
	m.mu.Unlock()
	m.setRoute(Route{}, nil)
}

// Lost reports that r stopped working (a request failed to connect). If
// it's still the current route, it's dropped, so the next Ensure chooses
// again.
func (m *Manager) Lost(r Route) {
	m.mu.Lock()
	same := m.cur == r
	m.mu.Unlock()
	if same {
		m.logf("route: lost %s %s", r.Kind, r.Base)
		m.setRoute(Route{}, nil)
	}
}

// Kick asks Run to look for the LAN now.
func (m *Manager) Kick() {
	select {
	case m.kickCh() <- struct{}{}:
	default:
	}
}

func (m *Manager) kickCh() chan struct{} {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.kick == nil {
		m.kick = make(chan struct{}, 1)
	}
	return m.kick
}

func (m *Manager) setRoute(r Route, err error) {
	m.mu.Lock()
	old := m.cur
	m.cur = r
	if err != nil {
		m.lastErr, m.lastErrAt = err, time.Now()
	} else if !r.IsZero() {
		m.lastErr = nil
	}
	m.mu.Unlock()
	if old != r {
		if !r.IsZero() {
			m.logf("route: %s %s", r.Label(), r.Base)
		}
		if m.OnChange != nil {
			m.OnChange(r)
		}
	}
}

// reselect runs a selection. lanOnly keeps the current route unless the
// LAN turns up; onlyIfNone returns the current route if another caller
// chose one while this one waited.
func (m *Manager) reselect(ctx context.Context, lanOnly, onlyIfNone bool) (Route, error) {
	m.sel.Lock()
	defer m.sel.Unlock()
	if onlyIfNone {
		if r, ok := m.Current(); ok {
			return r, nil
		}
	}
	id := m.Identity()
	if id.ID == "" {
		// a hub we know no identity of (not migrated yet, or from before
		// local-first): use its URL as it is
		if id.Remote == "" {
			m.setRoute(Route{}, ErrNotPaired)
			return Route{}, ErrNotPaired
		}
		r := Route{Kind: Remote, Base: id.Remote}
		m.setRoute(r, nil)
		return r, nil
	}
	if lanOnly && !id.CanLAN() {
		r, _ := m.Current()
		return r, nil
	}
	o := m.Options
	o.LANOnly = lanOnly
	res, err := Select(ctx, id, m.Deps, o)
	if err != nil {
		var ue *UnreachableError
		if errors.As(err, &ue) {
			m.logf("route: no route to the hub: %s", ue.Detail())
			if ue.Changed != nil {
				m.mu.Lock()
				m.changed = ue.Changed
				m.mu.Unlock()
			}
		}
		if lanOnly || ctx.Err() != nil {
			r, _ := m.Current() // the LAN isn't there (or we were stopped): stay put
			return r, err
		}
		m.setRoute(Route{}, err)
		return Route{}, err
	}
	m.mu.Lock()
	switch {
	case res.Route.Kind == LAN:
		m.changed = nil // the pinned certificate answered: all is well
	case res.Changed != nil:
		m.changed = res.Changed
	}
	if res.Route.Kind == Remote && res.Info != nil && res.Info.FP() != "" && id.Fingerprint != "" &&
		res.Info.FP() != normalized(id.Fingerprint) && IsHTTPS(id.Remote) {
		// the hub itself, over verified TLS, reports a new certificate
		m.changed = &Changed{Want: normalized(id.Fingerprint), Got: res.Info.FP(), Addr: id.Remote, Verified: true}
	}
	m.mu.Unlock()
	if m.OnResult != nil {
		m.OnResult(res)
	}
	m.setRoute(res.Route, nil)
	return res.Route, nil
}

// IsHTTPS reports whether u is an https URL (so answers along it were
// checked with normal TLS verification).
func IsHTTPS(u string) bool { return strings.HasPrefix(strings.ToLower(u), "https://") }

// Run watches for network changes until ctx ends: when the interfaces
// change it chooses the route again, and while on the remote route it
// looks for the LAN every few minutes.
func (m *Manager) Run(ctx context.Context) {
	sigFn := m.NetSignature
	if sigFn == nil {
		sigFn = InterfaceSignature
	}
	kick := m.kickCh()

	sig := sigFn()
	lastLook := time.Now()
	t := time.NewTicker(netPollEvery)
	defer t.Stop()
	var settle <-chan time.Time
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if s := sigFn(); s != sig {
				sig = s
				m.logf("route: the network changed")
				settle = time.After(settleDelay)
				continue
			}
			if r, ok := m.Current(); ok && r.Kind == Remote && time.Since(lastLook) >= lanLookEvery {
				lastLook = time.Now()
				m.reselect(ctx, true, false)
			}
		case <-settle:
			settle = nil
			lastLook = time.Now()
			m.reselect(ctx, false, false)
		case <-kick:
			lastLook = time.Now()
			if r, ok := m.Current(); ok && r.Kind == LAN {
				continue
			}
			if _, ok := m.Current(); ok {
				m.reselect(ctx, true, false)
			} else {
				m.reselect(ctx, false, false)
			}
		}
	}
}

// InterfaceSignature describes the up network interfaces and their
// addresses: Wi-Fi joining or leaving, or a VPN (Tailscale) coming up or
// going down, changes it.
func InterfaceSignature() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "error: " + err.Error()
	}
	var parts []string
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifi.Addrs()
		for _, a := range addrs {
			parts = append(parts, ifi.Name+"="+a.String())
		}
	}
	sort.Strings(parts)
	return strings.Join(parts, ",")
}
