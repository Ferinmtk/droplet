package agent

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/config"
	"github.com/Ferinmtk/droplet/windows/internal/hub"
	"github.com/Ferinmtk/droplet/windows/internal/lan"
	"github.com/Ferinmtk/droplet/windows/internal/pairing"
	"github.com/Ferinmtk/droplet/windows/internal/pin"
	"github.com/Ferinmtk/droplet/windows/internal/platform"
	"github.com/Ferinmtk/droplet/windows/internal/route"
)

// discoverWait is how long a scan of the LAN takes.
const discoverWait = 2 * time.Second

// --- identity migration ------------------------------------------------------------

// migrate works out the identity of the hub an older config points at, at
// most every route.MigrateRetry. Until it succeeds the configured URL is
// used exactly as before.
func (a *Agent) migrate(ctx context.Context, cfg config.Config) {
	a.mu.Lock()
	if !a.migratedAt.IsZero() && time.Since(a.migratedAt) < route.MigrateRetry {
		a.mu.Unlock()
		return
	}
	a.migratedAt = time.Now()
	a.mu.Unlock()
	h, err := route.Migrate(ctx, cfg.RemoteURL(), a.Routes.Deps, a.Routes.Options)
	if err != nil {
		log.Printf("hub identity: %v (using %s as before)", err, cfg.RemoteURL())
		return
	}
	err = a.Store.Update(func(c *config.Config) {
		if c.Hub == nil && c.RemoteURL() == cfg.RemoteURL() {
			c.Hub = h
		}
	})
	if err != nil {
		log.Printf("save config: %v", err)
		return
	}
	how := "checked over " + cfg.RemoteURL()
	if h.PinSource == config.PinFromLAN {
		how = "trusted on first use on the LAN"
	}
	if h.Fingerprint == "" {
		how = "no LAN certificate yet: tailnet only"
	}
	log.Printf("hub identity: %s (id %s), %s", h.Name, h.ID, how)
	a.Routes.Reset()
}

// --- pairing -----------------------------------------------------------------------

// pollPairing asks whether a join request has been answered.
func (a *Agent) pollPairing(ctx context.Context, c *hub.Client, r route.Route, cfg config.Config) error {
	me, err := c.Me(ctx)
	st, code := pairing.Evaluate(me, err)
	name := HubName(cfg)
	switch st {
	case pairing.Unknown:
		if connectionError(err) {
			a.Routes.Lost(r)
		}
		a.setStatus(func(s *Status) { s.Polled, s.Connected, s.Problem = true, false, describe(err, name) })
		if err == nil {
			err = errors.New("no answer about the join request")
		}
		return err
	case pairing.Pending:
		if code != cfg.PairCode {
			if err := a.Store.Update(func(c *config.Config) { c.PairCode = code }); err != nil {
				log.Printf("save config: %v", err)
			}
		}
		a.setStatus(func(s *Status) {
			s.Polled, s.Connected, s.Problem = true, true, ""
			s.Pending, s.PairCode = true, code
		})
		return nil
	case pairing.Approved:
		err := a.Store.Update(func(c *config.Config) {
			c.PairPending, c.PairCode = false, ""
			c.DeviceID, c.DeviceName = me.Device.ID, me.Device.Name
		})
		if err != nil {
			return err
		}
		log.Printf("pairing: %s let %q in", name, me.Device.Name)
		notify(platform.Notification{
			Title: "This PC is now part of droplet",
			Body:  name + " let \"" + me.Device.Name + "\" in.",
			Tag:   "pair",
		})
		a.mu.Lock()
		a.client = nil // same token, but let the next poll start clean
		a.pairWarned = false
		a.mu.Unlock()
		a.setStatus(func(s *Status) { s.Pending, s.PairCode, s.Configured, s.NotAllowed = false, "", true, false })
		a.Poke()
		a.remoteChanged() // the live connection can start now
		return nil
	case pairing.Declined:
		err := a.Store.Update(func(c *config.Config) {
			c.DeviceToken, c.Session, c.DeviceID = "", "", ""
			c.PairPending, c.PairCode = false, ""
		})
		if err != nil {
			return err
		}
		log.Printf("pairing: %s declined the request (or it expired)", name)
		notify(platform.Notification{
			Title: "droplet wasn't let in",
			Body:  name + " declined this PC's request to join, or it expired. Open Settings to ask again.",
			Tag:   "pair",
		})
		a.mu.Lock()
		a.client = nil
		a.mu.Unlock()
		a.setStatus(func(s *Status) { s.Pending, s.PairCode, s.Configured = false, "", false })
		a.remoteChanged()
		return nil
	}
	return nil
}

// notAllowed handles the hub's 403 {"pair": true} to a device that thought
// it was in: it's either waiting after all, or removed or declined.
func (a *Agent) notAllowed(ctx context.Context, c *hub.Client, cfg config.Config) error {
	name := HubName(cfg)
	me, err := c.Me(ctx)
	if err == nil && me.Device != nil && me.Device.Pending {
		if err := a.Store.Update(func(n *config.Config) { n.PairPending, n.PairCode = true, me.Device.Code }); err != nil {
			return err
		}
		a.setStatus(func(s *Status) {
			s.Polled, s.Connected, s.Problem = true, true, ""
			s.Configured, s.Pending, s.PairCode = false, true, me.Device.Code
		})
		a.needPairing("Waiting to be let in to "+name,
			"On one of your devices, allow \""+me.Device.Name+"\" and check the code is "+me.Device.Code+".")
		return nil
	}
	a.setStatus(func(s *Status) {
		s.Polled, s.Connected, s.Configured, s.NotAllowed = true, false, false, true
		s.Problem = name + " doesn't let this PC in"
	})
	a.needPairing("droplet isn't let in to "+name,
		"This PC was removed from the hub, or its request to join was declined. Open Settings to pair it again.")
	return hub.ErrNotAllowed
}

// needPairing notifies once, and opens Settings, when the person has to
// pair (again).
func (a *Agent) needPairing(title, body string) {
	a.mu.Lock()
	warned := a.pairWarned
	a.pairWarned = true
	a.mu.Unlock()
	if warned {
		return
	}
	if title != "" {
		notify(platform.Notification{Title: title, Body: body, Tag: "pair"})
	}
	if a.OnNeedPairing != nil {
		a.OnNeedPairing()
	}
}

// checkPair has the poll loop look into an action refused with
// {"pair": true}: it notifies and opens Settings.
func (a *Agent) checkPair(err error) {
	if errors.Is(err, hub.ErrNotAllowed) {
		a.Poke()
	}
}

// warnChanged tells the person, once per new certificate, that the hub's
// identity on the LAN changed. droplet never switches to it by itself.
func (a *Agent) warnChanged(c *route.Changed) {
	a.mu.Lock()
	seen := a.changedWarned == c.Got
	a.changedWarned = c.Got
	a.mu.Unlock()
	if seen {
		return
	}
	name := HubName(a.Store.Get())
	log.Printf("hub identity: %s at %s presents certificate %s, pinned %s (confirmed over the tailnet: %v)",
		name, c.Addr, c.Got, c.Want, c.Verified)
	body := "Something on your network says it's " + name + " but has a different certificate, so droplet won't use it. " +
		"If you reset the hub, open Settings and choose Re-pair."
	if c.Verified {
		body = name + " has a new certificate, and Tailscale confirms it's the hub's. " +
			"Open Settings and choose Re-pair to use it on Wi-Fi again."
	}
	notify(platform.Notification{Title: "The hub's identity changed", Body: body, Tag: "identity"})
	if a.OnNeedPairing != nil {
		a.OnNeedPairing()
	}
}

// --- finding hubs and joining ------------------------------------------------------------

// Found is a hub on the LAN, as the settings page lists it.
type Found struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	Instance string   `json:"instance"`
	Addrs    []string `json:"addrs"`
	Tailnet  string   `json:"tailnet,omitempty"`
	// Fingerprint is shown shortened, so people can compare it if they like.
	Fingerprint string `json:"fingerprint"`
	Paired      bool   `json:"paired"`  // the hub this PC is paired with
	Changed     bool   `json:"changed"` // …but its certificate isn't the pinned one
}

// Discover scans the LAN for hubs.
func (a *Agent) Discover(ctx context.Context) ([]Found, error) {
	hubs, err := a.Routes.Deps.Browse(ctx, discoverWait, nil)
	if err != nil {
		return nil, err
	}
	a.mu.Lock()
	a.discovered = hubs
	a.mu.Unlock()
	cfg := a.Store.Get()
	out := []Found{}
	for _, h := range hubs {
		f := Found{ID: h.ID, Name: h.Name, Instance: h.Instance, Addrs: h.Endpoints(), Tailnet: h.Tailnet,
			Fingerprint: h.Fingerprint[:16]}
		if f.Name == "" {
			f.Name = h.Instance
		}
		if cfg.Hub != nil && cfg.Hub.ID == h.ID {
			f.Paired = true
			f.Changed = cfg.Hub.Fingerprint != "" && cfg.Hub.Fingerprint != h.Fingerprint
		}
		out = append(out, f)
	}
	return out, nil
}

// findDiscovered returns the hub with id from the last scan, scanning again
// if it isn't there.
func (a *Agent) findDiscovered(ctx context.Context, id string) (lan.Hub, bool) {
	a.mu.Lock()
	hubs := a.discovered
	a.mu.Unlock()
	for _, h := range hubs {
		if h.ID == id {
			return h, true
		}
	}
	var found lan.Hub
	ok := false
	a.Routes.Deps.Browse(ctx, discoverWait, func(h lan.Hub) bool {
		if h.ID == id {
			found, ok = h, true
			return true
		}
		return false
	})
	return found, ok
}

// reachDiscovered connects to a hub found on the LAN, pinned to the
// certificate it announces (trust on first use: pairing, where the owner
// compares the code, confirms it's the right hub). A paired hub that now
// announces another certificate is refused unless trustNew (re-pairing).
func (a *Agent) reachDiscovered(ctx context.Context, id string, trustNew bool) (route.Route, *hub.Info, lan.Hub, error) {
	h, ok := a.findDiscovered(ctx, id)
	if !ok {
		return route.Route{}, nil, h, &FieldError{"hub", "That hub isn't answering on this network any more. Scan again."}
	}
	cfg := a.Store.Get()
	if cfg.Hub != nil && cfg.Hub.ID == h.ID && cfg.Hub.Fingerprint != "" && cfg.Hub.Fingerprint != h.Fingerprint && !trustNew {
		return route.Route{}, nil, h, &FieldError{"hub", "This hub's identity changed since this PC paired with it. " +
			"If you reset the hub, choose Re-pair."}
	}
	var lastErr error
	for _, ep := range h.Endpoints() {
		r := route.Route{Kind: route.LAN, Base: route.LANBase(ep), Addr: ep, Pin: h.Fingerprint}
		pctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		info, err := a.Routes.Deps.Info(pctx, r.Base, r.Transport())
		cancel()
		if err == nil && info.ID != h.ID {
			err = fmt.Errorf("it answered as a different hub (%s)", info.ID)
		}
		if err == nil {
			return r, info, h, nil
		}
		lastErr = err
	}
	msg := "Couldn't connect to " + displayName(h) + " securely"
	if _, ok := pin.AsMismatch(lastErr); ok {
		msg += ": its certificate isn't the one it announces, so it may not be your hub."
	} else if lastErr != nil {
		msg += ": " + lastErr.Error()
	}
	return route.Route{}, nil, h, &FieldError{"hub", msg}
}

func displayName(h lan.Hub) string {
	if h.Name != "" {
		return h.Name
	}
	return h.Instance
}

// lanIdentity is the identity to store for a hub reached on the LAN.
func lanIdentity(cfg config.Config, h lan.Hub, r route.Route, info *hub.Info) *config.Hub {
	id := route.FromInfo(info, "", "")
	id.Fingerprint, id.PinSource = h.Fingerprint, config.PinFromLAN
	if cfg.Hub != nil && cfg.Hub.ID == h.ID && cfg.Hub.Fingerprint == h.Fingerprint && cfg.Hub.PinSource != "" {
		id.PinSource = cfg.Hub.PinSource // the same pin as before, from wherever it came
	}
	withAddr, _ := route.Remember(config.Hub{ID: h.ID, LAN: id.LAN}, route.Result{Route: r}, "")
	id.LAN = withAddr.LAN
	if id.Tailnet == "" {
		id.Tailnet = h.Tailnet
	}
	if id.HTTPPort == 0 {
		id.HTTPPort = h.HTTPPort
	}
	if id.Name == "" {
		id.Name = h.Name
	}
	return &id
}

// JoinResult is what asking to join did.
type JoinResult struct {
	Name    string `json:"name"`
	Hub     string `json:"hub"`
	Pending bool   `json:"pending"`
	Code    string `json:"code,omitempty"`
}

// Join asks a hub found on the LAN to let this PC in, as a new device
// called name (docs/local-first.md §4). With the hub's PIN it's let in at
// once; otherwise it waits for the owner to allow it on another device,
// comparing Code. The poll loop follows the request from there.
// trustNew accepts a paired hub's new certificate (re-pairing).
func (a *Agent) Join(ctx context.Context, hubID, name, pinCode string, trustNew bool) (*JoinResult, error) {
	name, err := cleanDeviceName(name)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	r, info, h, err := a.reachDiscovered(ctx, hubID, trustNew)
	if err != nil {
		return nil, err
	}
	// a new device: no earlier token comes along
	c, err := r.Client("", "")
	if err != nil {
		return nil, err
	}
	if pinCode != "" {
		if err := c.Login(ctx, pinCode); err != nil {
			return nil, &FieldError{"pin", friendly(err, c)}
		}
	}
	dev, err := c.Register(ctx, name)
	var nt *hub.NameTakenError
	var se *hub.StatusError
	switch {
	case errors.As(err, &nt):
		return nil, &FieldError{"name", "\"" + name + "\" is already a device on this hub (perhaps this PC, from before). " +
			"Pick another name, or remove the old one under Devices on another device first."}
	case errors.As(err, &se) && se.Msg != "":
		return nil, &FieldError{"hub", se.Msg}
	case err != nil:
		return nil, &FieldError{"hub", friendly(err, c)}
	}
	cfg := a.Store.Get()
	id := lanIdentity(cfg, h, r, info)
	err = a.Store.Update(func(n *config.Config) {
		if n.Hub == nil || n.Hub.ID != id.ID {
			n.HubURL = id.Tailnet // another hub: its own tailnet URL (or none), not the old one
		}
		n.Hub = id
		n.DeviceToken, n.Session = c.Token, c.Session
		n.DeviceID, n.DeviceName = dev.ID, dev.Name
		n.PairPending, n.PairCode = dev.Pending, dev.Code
		n.InboxSeen, n.ChatSeen = nil, map[string]float64{} // a new device: nothing seen yet
	})
	if err != nil {
		return nil, fmt.Errorf("saving settings: %w", err)
	}
	a.mu.Lock()
	a.lastDests = nil
	a.changedWarned = ""
	a.mu.Unlock()
	a.Reset()
	a.Routes.Set(r)
	res := &JoinResult{Name: dev.Name, Hub: id.Name, Pending: dev.Pending, Code: dev.Code}
	if dev.Pending {
		log.Printf("pairing: asked %s to let %q in (code %s)", id.Name, dev.Name, dev.Code)
	} else {
		log.Printf("pairing: joined %s as %q", id.Name, dev.Name)
	}
	return res, nil
}

// SignInWithPIN lets a waiting device in with the hub's PIN instead.
func (a *Agent) SignInWithPIN(ctx context.Context, pinCode string) error {
	cfg := a.Store.Get()
	if !cfg.Pending() {
		return errors.New("this PC isn't waiting to be let in")
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	c, _, err := a.clientFor(ctx)
	if err != nil {
		return &FieldError{"pin", "Can't reach the hub: " + err.Error()}
	}
	if err := c.Login(ctx, pinCode); err != nil {
		return &FieldError{"pin", friendly(err, c)}
	}
	if err := a.Store.Update(func(n *config.Config) { n.Session = c.Session }); err != nil {
		return err
	}
	a.Poke() // the poll loop sees the device is in
	return nil
}

// CancelJoin forgets a join request that's still waiting. The hub drops
// unanswered requests by itself after a day.
func (a *Agent) CancelJoin() error {
	err := a.Store.Update(func(n *config.Config) {
		if n.Pending() {
			n.DeviceToken, n.Session, n.DeviceID = "", "", ""
			n.PairPending, n.PairCode = false, ""
		}
	})
	a.Reset()
	return err
}

// LinkLAN is Link over the LAN, with a hub found by Discover.
func (a *Agent) LinkLAN(ctx context.Context, hubID, code, pinCode string) (*LinkResult, error) {
	digits, err := linkDigits(code)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	r, info, h, err := a.reachDiscovered(ctx, hubID, false)
	if err != nil {
		return nil, err
	}
	c, err := r.Client("", "")
	if err != nil {
		return nil, err
	}
	cfg := a.Store.Get()
	id := lanIdentity(cfg, h, r, info)
	res, err := a.link(ctx, c, digits, pinCode, id, "")
	if err != nil {
		return nil, err
	}
	a.Routes.Set(r)
	return res, nil
}

// --- re-pairing after an identity change -------------------------------------------

// RepairResult is what Re-pair did.
type RepairResult struct {
	// Done: the new certificate was confirmed over the tailnet and is pinned
	// now; nothing else to do.
	Done bool `json:"done"`
	// Join: it couldn't be confirmed. Ask to join again on the LAN
	// (trusting the new certificate), as a new device.
	Join   bool   `json:"join"`
	HubID  string `json:"hub_id,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// Repair deals with the hub's certificate having changed. When the hub can
// be asked over the tailnet (verified TLS), its new fingerprint is pinned
// and the device carries on as it was. Otherwise the new certificate can
// only be trusted by pairing again on the LAN, as a new device: the old
// token is never sent to a certificate nobody has vouched for.
func (a *Agent) Repair(ctx context.Context) (*RepairResult, error) {
	cfg := a.Store.Get()
	if cfg.Hub == nil {
		return nil, errors.New("this PC isn't paired with a hub")
	}
	remoteURL := cfg.RemoteURL()
	reason := "The hub can't be reached over Tailscale to confirm its new certificate."
	if route.IsHTTPS(remoteURL) {
		ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		info, err := a.Routes.Deps.Info(ctx, remoteURL, nil)
		cancel()
		fp, ok := "", false
		if err == nil {
			fp, ok = pin.Normalize(info.FP())
		}
		switch {
		case err == nil && info.ID == cfg.Hub.ID && ok:
			err := a.Store.Update(func(n *config.Config) {
				if n.Hub != nil && n.Hub.ID == info.ID {
					n.Hub.Fingerprint, n.Hub.PinSource = fp, config.PinFromTailnet
				}
			})
			if err != nil {
				return nil, err
			}
			log.Printf("hub identity: re-pinned %s to %s, confirmed over %s", cfg.Hub.ID, fp, remoteURL)
			a.mu.Lock()
			a.changedWarned = ""
			a.mu.Unlock()
			a.Routes.Reset()
			a.Poke()
			return &RepairResult{Done: true}, nil
		case err == nil && info.ID != cfg.Hub.ID:
			reason = remoteURL + " is a different hub now."
		case err == nil:
			reason = "The hub has no LAN certificate at the moment."
		default:
			reason = "Couldn't ask the hub over Tailscale: " + err.Error()
		}
	}
	return &RepairResult{Join: true, HubID: cfg.Hub.ID, Reason: reason}, nil
}

// cleanDeviceName checks a device name typed in Settings.
func cleanDeviceName(s string) (string, error) {
	name := strings.Join(strings.Fields(s), " ")
	if name == "" {
		return "", &FieldError{"name", "Give this PC a name, like \"maryanne\"."}
	}
	if len([]rune(name)) > 40 {
		return "", &FieldError{"name", "Keep the name under 40 characters."}
	}
	return name, nil
}

// WaitForPairing follows this PC's join request until it's answered or ctx
// ends, reporting each change to update. The tray's poll loop does the
// same by itself; this is for the command line.
func (a *Agent) WaitForPairing(ctx context.Context, update func(st pairing.State, code string)) (pairing.State, error) {
	last := pairing.State("")
	for {
		cfg := a.Store.Get()
		st, code := pairing.Pending, cfg.PairCode
		switch {
		case cfg.Registered():
			st = pairing.Approved
		case !cfg.Pending():
			st = pairing.Declined
		}
		if st != last && update != nil {
			update(st, code)
		}
		last = st
		if st.Done() {
			return st, nil
		}
		pctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		if c, r, err := a.clientFor(pctx); err == nil {
			a.pollPairing(pctx, c, r, cfg)
		}
		cancel()
		if !a.Store.Get().Pending() {
			continue // answered: report it now
		}
		t := time.NewTimer(pairing.Every)
		select {
		case <-ctx.Done():
			t.Stop()
			return last, ctx.Err()
		case <-t.C:
		}
	}
}

// FindHub picks a discovered hub by id, name or instance name; with an
// empty query, the only hub there is.
func FindHub(found []Found, query string) (Found, error) {
	q := strings.ToLower(strings.TrimSpace(query))
	var matches []Found
	for _, f := range found {
		if q == "" || f.ID == q || strings.ToLower(f.Name) == q || strings.ToLower(f.Instance) == q {
			matches = append(matches, f)
		}
	}
	switch {
	case len(found) == 0:
		return Found{}, errors.New("no droplet hub found on this network")
	case len(matches) == 1:
		return matches[0], nil
	case len(matches) == 0:
		return Found{}, fmt.Errorf("no hub called %q on this network (see droplet hubs)", query)
	}
	return Found{}, errors.New("more than one hub on this network: say which, by name or id (see droplet hubs)")
}
