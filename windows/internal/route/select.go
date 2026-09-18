package route

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
	"github.com/Ferinmtk/droplet/windows/internal/pin"
)

// Options tunes Select.
type Options struct {
	LANTimeout    time.Duration // per LAN address tried (docs: about 1.5 s)
	BrowseWait    time.Duration // how long mDNS may take
	RemoteTimeout time.Duration // the tailnet can be slow to wake up
	LANOnly       bool          // look for the LAN only (while on the tailnet)
	MaxLastAddrs  int           // how many remembered addresses to try
}

// DefaultOptions follows docs/local-first.md.
var DefaultOptions = Options{
	LANTimeout:    1500 * time.Millisecond,
	BrowseWait:    1500 * time.Millisecond,
	RemoteTimeout: 10 * time.Second,
	MaxLastAddrs:  3,
}

// Changed is the hub's LAN certificate no longer matching the pin.
type Changed struct {
	Want string // the pinned fingerprint
	Got  string // what the hub presents (or announces) now
	Addr string // where
	// Verified: the tailnet (normal, verified TLS) says Got is the hub's
	// fingerprint now, so re-pairing can adopt it with no guesswork.
	Verified bool
}

func (c *Changed) Error() string {
	return (&pin.MismatchError{Want: c.Want, Got: c.Got}).Error()
}

// Result is what Select found.
type Result struct {
	Route Route
	// Info is what the chosen route's hub said about itself.
	Info *hub.Info
	// Changed is set when a LAN address answered with another certificate,
	// or announced another fingerprint, and no LAN route worked.
	Changed *Changed
}

// ErrUnreachable is no route working.
var ErrUnreachable = errors.New("can't reach the hub")

// UnreachableError says why, and whether the identity changed.
type UnreachableError struct {
	Changed *Changed
	Tried   []string // what was tried and how it failed, for the log
}

func (e *UnreachableError) Error() string {
	if e.Changed != nil {
		return e.Changed.Error()
	}
	return ErrUnreachable.Error()
}

func (e *UnreachableError) Unwrap() error { return ErrUnreachable }

// Detail is a one-line account of what was tried, for the log.
func (e *UnreachableError) Detail() string { return strings.Join(e.Tried, "; ") }

type lanOutcome struct {
	addr    string
	info    *hub.Info
	changed *Changed
	tried   []string
}

type remoteOutcome struct {
	info *hub.Info
	err  error
}

// Select finds the best working route to the hub id describes: the LAN
// first (an mDNS answer with the hub's id, or the last address that
// worked, over pinned TLS), else the remote URL with normal TLS. LAN and
// remote are tried at the same time; the remote one is used only once the
// LAN has failed.
func Select(ctx context.Context, id Identity, deps Deps, o Options) (Result, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	lanDone := make(chan lanOutcome, 1)
	if id.CanLAN() {
		go func() { lanDone <- selectLAN(ctx, id, deps, o) }()
	} else {
		lanDone <- lanOutcome{tried: []string{"LAN: not paired for it (no certificate pin)"}}
	}
	remoteDone := make(chan remoteOutcome, 1)
	remote := id.Remote != "" && !o.LANOnly
	if remote {
		go func() {
			rctx, rcancel := context.WithTimeout(ctx, o.RemoteTimeout)
			defer rcancel()
			info, err := deps.Info(rctx, id.Remote, nil)
			switch {
			case errors.Is(err, hub.ErrNotFound):
				// a hub from before local-first: nothing to check it by, and
				// it answered, so it's there
				info, err = nil, nil
			case err == nil && id.ID != "" && info.ID != id.ID:
				err = fmt.Errorf("%s is a different hub (id %s, not %s)", id.Remote, info.ID, id.ID)
			}
			remoteDone <- remoteOutcome{info, err}
		}()
	}

	var lo *lanOutcome
	var ro *remoteOutcome
	for {
		select {
		case o := <-lanDone:
			lo = &o
			if o.addr != "" {
				r := Route{Kind: LAN, Base: LANBase(o.addr), Addr: o.addr, Pin: normalized(id.Fingerprint)}
				return Result{Route: r, Info: o.info}, nil
			}
		case o := <-remoteDone:
			ro = &o
		case <-ctx.Done():
			return Result{}, ctx.Err()
		}
		if lo == nil || (remote && ro == nil) {
			continue
		}
		// the LAN failed; the remote route has answered (or there is none)
		tried := lo.tried
		if remote && ro.err == nil {
			res := Result{Route: Route{Kind: Remote, Base: id.Remote}, Info: ro.info, Changed: lo.changed}
			if res.Changed != nil && ro.info != nil && ro.info.FP() != "" {
				res.Changed.Verified = ro.info.FP() == res.Changed.Got
			}
			return res, nil
		}
		if remote {
			tried = append(tried, "remote "+id.Remote+": "+ro.err.Error())
		}
		return Result{}, &UnreachableError{Changed: lo.changed, Tried: tried}
	}
}

func normalized(fp string) string {
	n, _ := pin.Normalize(fp)
	return n
}

// selectLAN tries the remembered addresses at once, and whatever mDNS finds
// with the hub's id as it comes in; the first that answers with the pinned
// certificate and the right id wins.
func selectLAN(ctx context.Context, id Identity, deps Deps, o Options) lanOutcome {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	fp := normalized(id.Fingerprint)
	rt := lanTransport(fp)

	type probe struct {
		addr string
		info *hub.Info
		err  error
	}
	results := make(chan probe)
	var (
		wg      sync.WaitGroup
		mu      sync.Mutex
		tried   = map[string]bool{}
		notes   []string
		changed *Changed
	)
	note := func(s string) { mu.Lock(); notes = append(notes, s); mu.Unlock() }
	start := func(addr string) {
		mu.Lock()
		if tried[addr] {
			mu.Unlock()
			return
		}
		tried[addr] = true
		mu.Unlock()
		wg.Add(1)
		go func() {
			defer wg.Done()
			pctx, pcancel := context.WithTimeout(ctx, o.LANTimeout)
			defer pcancel()
			info, err := deps.Info(pctx, LANBase(addr), rt)
			if err == nil && info.ID != id.ID {
				err = fmt.Errorf("a different hub (id %s)", info.ID)
			}
			select {
			case results <- probe{addr, info, err}:
			case <-ctx.Done():
			}
		}()
	}

	n := o.MaxLastAddrs
	for i, addr := range id.LAN {
		if i >= n {
			break
		}
		start(addr)
	}
	if deps.Browse != nil {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := deps.Browse(ctx, o.BrowseWait, func(h lan.Hub) bool {
				if h.ID != id.ID {
					return false // someone else's hub
				}
				if h.Fingerprint != fp {
					// it says it's our hub but has another certificate:
					// either not ours, or its certificate was regenerated
					mu.Lock()
					if changed == nil && len(h.Endpoints()) > 0 {
						changed = &Changed{Want: fp, Got: h.Fingerprint, Addr: h.Endpoints()[0]}
					}
					mu.Unlock()
					note("mDNS: " + h.Instance + " announces another certificate")
					return false
				}
				for _, ep := range h.Endpoints() {
					start(ep)
				}
				return true
			})
			if err != nil && ctx.Err() == nil {
				note("mDNS: " + err.Error())
			}
		}()
	}
	go func() { wg.Wait(); close(results) }()

	for p := range results {
		if p.err == nil {
			cancel() // the rest can stop
			return lanOutcome{addr: p.addr, info: p.info}
		}
		if me, ok := pin.AsMismatch(p.err); ok && me.Got != "" {
			mu.Lock()
			if changed == nil {
				changed = &Changed{Want: fp, Got: me.Got, Addr: p.addr}
			}
			mu.Unlock()
		}
		note("LAN " + p.addr + ": " + p.err.Error())
	}
	mu.Lock()
	defer mu.Unlock()
	if len(tried) == 0 && len(notes) == 0 {
		notes = append(notes, "LAN: no address to try and nothing found by mDNS")
	}
	return lanOutcome{changed: changed, tried: notes}
}
