package route

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
	"github.com/Ferinmtk/droplet/windows/internal/pin"
)

// ErrNoIdentity is a hub that can't say who it is: one from before
// local-first (no /api/hub/info). The app keeps using its URL as before.
var ErrNoIdentity = errors.New("this hub predates local-first (no /api/hub/info)")

// maxLAN is how many LAN addresses are remembered.
const maxLAN = 4

// FromInfo is the identity a hub describes in /api/hub/info. The
// fingerprint is taken only when source says the answer is trustworthy
// (verified TLS, or the pinned LAN connection it was checked against).
func FromInfo(info *hub.Info, pinSource string, remoteURL string) config.Hub {
	h := config.Hub{ID: info.ID, Name: info.Name, HTTPPort: info.LAN.HTTPPort, Tailnet: info.TailnetURL()}
	if fp, ok := pin.Normalize(info.FP()); ok && pinSource != "" {
		h.Fingerprint, h.PinSource = fp, pinSource
	}
	h.LAN = mergeAddrs(nil, info.LANEndpoints()...)
	if h.Tailnet == "" && IsTailnetURL(remoteURL) {
		h.Tailnet = remoteURL
	}
	return h
}

// Migrate works out the identity of the hub an older config points at
// (only a URL and a token):
//   - over remoteURL, when it's https and answers: the answer is as
//     trustworthy as the URL's TLS, so its fingerprint is pinned;
//   - otherwise with mDNS, trusting on first use, but only a hub that ties
//     itself to remoteURL: it announces remoteURL as its tailnet URL, or
//     remoteURL is one of its LAN addresses. Its certificate must match the
//     fingerprint it announces.
//
// ErrNoIdentity means the hub answered but can't say who it is.
func Migrate(ctx context.Context, remoteURL string, deps Deps, o Options) (*config.Hub, error) {
	u, err := url.Parse(remoteURL)
	if err != nil || u.Host == "" {
		return nil, fmt.Errorf("can't migrate: bad hub URL %q", remoteURL)
	}
	var remoteErr error
	if IsHTTPS(remoteURL) {
		rctx, cancel := context.WithTimeout(ctx, o.RemoteTimeout)
		info, err := deps.Info(rctx, remoteURL, nil)
		cancel()
		switch {
		case err == nil:
			h := FromInfo(info, config.PinFromTailnet, remoteURL)
			return &h, nil
		case errors.Is(err, hub.ErrNotFound):
			return nil, ErrNoIdentity
		}
		remoteErr = err
	}
	if deps.Browse == nil {
		return nil, remoteErr
	}
	hubs, err := deps.Browse(ctx, 2*o.BrowseWait, nil)
	if err != nil {
		return nil, errors.Join(remoteErr, err)
	}
	var match *lan.Hub
	for i := range hubs {
		if !tiedTo(hubs[i], u) {
			continue
		}
		if match != nil && match.ID != hubs[i].ID {
			return nil, errors.New("more than one hub on the LAN claims this hub's address; pair again from Settings")
		}
		match = &hubs[i]
	}
	if match == nil {
		if remoteErr != nil {
			return nil, remoteErr
		}
		return nil, errors.New("the hub isn't on this network")
	}
	fp := match.Fingerprint
	for _, ep := range match.Endpoints() {
		pctx, cancel := context.WithTimeout(ctx, o.LANTimeout)
		info, err := deps.Info(pctx, LANBase(ep), lanTransport(fp))
		cancel()
		if err != nil || info.ID != match.ID {
			continue
		}
		h := FromInfo(info, "", remoteURL)
		// pinned to what mDNS announced, which the connection just matched
		h.Fingerprint, h.PinSource = fp, config.PinFromLAN
		h.LAN = mergeAddrs([]string{ep}, h.LAN...)
		if h.Tailnet == "" {
			h.Tailnet = match.Tailnet
		}
		if h.HTTPPort == 0 {
			h.HTTPPort = match.HTTPPort
		}
		return &h, nil
	}
	return nil, errors.New("the hub on this network didn't answer with the certificate it announces")
}

// tiedTo reports whether an mDNS announcement is the hub at u.
func tiedTo(h lan.Hub, u *url.URL) bool {
	if h.Tailnet != "" && sameURL(h.Tailnet, u.String()) {
		return true
	}
	ip, err := netip.ParseAddr(u.Hostname())
	if err != nil {
		return false
	}
	port := u.Port()
	for _, a := range h.Addrs {
		if a != ip.Unmap() {
			continue
		}
		if port == "" || port == strconv.Itoa(h.HTTPPort) || port == strconv.Itoa(h.Port) {
			return true
		}
	}
	return false
}

func sameURL(a, b string) bool {
	return strings.TrimRight(strings.ToLower(a), "/") == strings.TrimRight(strings.ToLower(b), "/")
}

// Remember folds what a selection learnt into the stored identity: the
// LAN address that answered goes first, and the hub's own account of its
// addresses, name and tailnet URL is kept. A first fingerprint is adopted
// only from verified TLS (an https remote route); a different one never is
// (that's an identity change, which takes re-pairing). It reports whether
// anything changed.
func Remember(h config.Hub, res Result, remoteURL string) (config.Hub, bool) {
	before := fmt.Sprint(h)
	var fresh []string // newest knowledge first: what worked, then what the hub says
	if res.Route.Kind == LAN && res.Route.Addr != "" {
		fresh = append(fresh, res.Route.Addr)
	}
	if info := res.Info; info != nil && info.ID == h.ID {
		fresh = append(fresh, info.LANEndpoints()...)
	}
	h.LAN = mergeAddrs(fresh, h.LAN...)
	if info := res.Info; info != nil && info.ID == h.ID {
		if info.Name != "" {
			h.Name = info.Name
		}
		if info.LAN.HTTPPort > 0 {
			h.HTTPPort = info.LAN.HTTPPort
		}
		if t := info.TailnetURL(); t != "" {
			h.Tailnet = t
		}
		if h.Fingerprint == "" && res.Route.Kind == Remote && IsHTTPS(remoteURL) {
			if fp, ok := pin.Normalize(info.FP()); ok {
				h.Fingerprint, h.PinSource = fp, config.PinFromTailnet
			}
		}
	}
	return h, fmt.Sprint(h) != before
}

// mergeAddrs puts first before rest, without duplicates or junk, and keeps
// at most maxLAN.
func mergeAddrs(first []string, rest ...string) []string {
	var out []string
	seen := map[string]bool{}
	for _, list := range [][]string{first, rest} {
		for _, a := range list {
			host, port, err := net.SplitHostPort(a)
			if err != nil || net.ParseIP(host) == nil || port == "" || seen[a] {
				continue
			}
			seen[a] = true
			out = append(out, a)
			if len(out) == maxLAN {
				return out
			}
		}
	}
	return out
}

// MigrateRetry is how long to wait before trying a failed migration again.
const MigrateRetry = 10 * time.Minute
