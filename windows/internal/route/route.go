// Package route decides how the app reaches its hub (docs/local-first.md §3):
// directly on the LAN, over TLS pinned to the hub's certificate, when the
// hub is there; otherwise over its tailnet URL with normal TLS. It also works
// out the hub's identity for configs from before local-first (Migrate).
//
// Everything here is platform-neutral and takes its network access as
// functions (Deps), so the choices can be tested without a network.
package route

import (
	"context"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
	"github.com/Ferinmtk/droplet/windows/internal/pin"
)

// Kind is which way a route goes.
type Kind string

const (
	// LAN is straight to the hub on the local network, pinned.
	LAN Kind = "lan"
	// Remote is the configured URL (normally the tailnet), normal TLS.
	Remote Kind = "remote"
)

// Route is one way to the hub.
type Route struct {
	Kind Kind
	Base string // e.g. https://192.168.100.20:8443 or https://t15.tail7375fe.ts.net
	Addr string // LAN only: "ip:port"
	Pin  string // LAN only: the certificate fingerprint it's pinned to
}

// IsZero reports whether r is no route at all.
func (r Route) IsZero() bool { return r.Base == "" }

// Transport is how to connect along r: the pinned transport on the LAN,
// nil (Go's default, fully verified) otherwise.
func (r Route) Transport() http.RoundTripper {
	if r.Kind == LAN {
		return lanTransport(r.Pin)
	}
	return nil
}

// Client is a hub client along r.
func (r Route) Client(token, session string) (*hub.Client, error) {
	return hub.NewWithTransport(r.Base, token, session, r.Transport())
}

// Label is how the route is shown: "on Wi-Fi" or "via Tailscale".
func (r Route) Label() string {
	switch {
	case r.IsZero():
		return ""
	case r.Kind == LAN:
		return "on Wi-Fi"
	case IsTailnetURL(r.Base):
		return "via Tailscale"
	}
	if u, err := url.Parse(r.Base); err == nil && u.Host != "" {
		return "via " + u.Host
	}
	return "via " + r.Base
}

var cgnat = netip.MustParsePrefix("100.64.0.0/10")

// IsTailnetURL reports whether u points into a tailnet (a *.ts.net name or
// a 100.64.0.0/10 address).
func IsTailnetURL(u string) bool {
	p, err := url.Parse(u)
	if err != nil {
		return false
	}
	h := strings.ToLower(p.Hostname())
	if strings.HasSuffix(h, ".ts.net") {
		return true
	}
	if ip, err := netip.ParseAddr(h); err == nil && cgnat.Contains(ip.Unmap()) {
		return true
	}
	return false
}

// LANBase is the base URL for a LAN address.
func LANBase(addr string) string { return "https://" + addr }

// lanTransports keeps one pinned transport per fingerprint, so connections
// are reused across clients instead of piling up a pool per client.
var lanTransports sync.Map // pin -> *http.Transport

func lanTransport(fp string) http.RoundTripper {
	if t, ok := lanTransports.Load(fp); ok {
		return t.(*http.Transport)
	}
	t, _ := lanTransports.LoadOrStore(fp, pin.Transport(fp))
	return t.(*http.Transport)
}

// Identity is what route selection needs to know about the paired hub.
type Identity struct {
	ID          string
	Fingerprint string
	LAN         []string // last LAN addresses, newest first
	Remote      string   // fallback URL (tailnet), or ""
}

// IdentityOf reads it from the config.
func IdentityOf(c config.Config) Identity {
	id := Identity{Remote: c.RemoteURL()}
	if c.Hub != nil {
		id.ID, id.Fingerprint = c.Hub.ID, c.Hub.Fingerprint
		id.LAN = append([]string(nil), c.Hub.LAN...)
	}
	return id
}

// CanLAN reports whether a LAN route is possible: a known hub and a pin.
func (id Identity) CanLAN() bool {
	_, ok := pin.Normalize(id.Fingerprint)
	return id.ID != "" && ok
}

// Deps is the network access selection needs; tests replace it.
type Deps struct {
	// Browse searches the LAN for hubs (lan.Browse).
	Browse func(ctx context.Context, wait time.Duration, found func(lan.Hub) bool) ([]lan.Hub, error)
	// Info fetches /api/hub/info from base through rt (nil: normal TLS).
	// It sends no credentials.
	Info func(ctx context.Context, base string, rt http.RoundTripper) (*hub.Info, error)
}

// DefaultDeps uses the real network.
func DefaultDeps() Deps {
	return Deps{
		Browse: lan.Browse,
		Info: func(ctx context.Context, base string, rt http.RoundTripper) (*hub.Info, error) {
			c, err := hub.NewWithTransport(base, "", "", rt)
			if err != nil {
				return nil, err
			}
			return c.HubInfo(ctx)
		},
	}
}

// tailscaleV6 is Tailscale's IPv6 range.
var tailscaleV6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")

// TailscaleUp reports whether this machine is on a tailnet right now: an
// up interface has a Tailscale address.
func TailscaleUp() bool {
	ifaces, err := net.Interfaces()
	if err != nil {
		return false
	}
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, _ := ifi.Addrs()
		for _, a := range addrs {
			p, err := netip.ParsePrefix(a.String())
			if err != nil {
				continue
			}
			ip := p.Addr().Unmap()
			if cgnat.Contains(ip) || tailscaleV6.Contains(ip) {
				return true
			}
		}
	}
	return false
}
