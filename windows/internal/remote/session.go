package remote

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/Ferinmtk/droplet/windows/internal/hub"
)

// Injector performs strokes (SendInput on Windows), in order, at once.
type Injector interface {
	Inject(strokes []Stroke) error
}

// NowPlaying reports what's playing. Run calls update whenever the list
// changes, until ctx ends.
type NowPlaying interface {
	Run(ctx context.Context, update func([]Player))
}

// Backend is what the desktop can do. A nil field means "can't", and the
// matching capability is left out of hello.
type Backend struct {
	Input      Injector
	Volume     VolumeControl
	NowPlaying NowPlaying
	Lock       func() error
	Capture    func() ([]byte, error) // the whole screen, as PNG
	Clipboard  Clipboard
}

// Has reports whether the backend can provide a capability.
func (b Backend) Has(capability string) bool {
	switch capability {
	case CapInput:
		return b.Input != nil
	case CapMedia:
		return b.Input != nil || b.Volume != nil // media keys need the injector
	case CapLock:
		return b.Lock != nil
	case CapScreenshot:
		return b.Capture != nil
	case CapClipboard:
		return b.Clipboard != nil
	}
	return false
}

// Params is what a connection needs from the settings. The session reads
// it again at every (re)connect and on Reload.
type Params struct {
	HubURL string
	// Transport connects to HubURL: the pinned LAN transport, or nil for
	// normal TLS (docs/local-first.md).
	Transport http.RoundTripper
	Token   string // the device token (droplet_device), sent as a bearer
	Session string // the hub's session cookie after a PIN login, if any
	Name    string // this device's name, for screenshot file names
	Caps    map[string]bool
	Paused  bool
}

// Status is the connection's state, for the tray.
type Status struct {
	Offered    []string // capabilities offered; empty means not connecting
	Live       bool
	Problem    string // why not live, in words
	Controller string // who used remote control in the last couple of minutes
	Active     bool   // input arrived in the last few seconds
}

// Options configures a Session.
type Options struct {
	Params   func() Params
	Backend  Backend
	App      string // e.g. "droplet-windows/1.2.0"
	Platform string // "windows"
	// Notify shows a notification (a screenshot was taken). Optional.
	Notify func(title, body string)
	// OnStatus is called when Status changes. Optional.
	OnStatus func(Status)
	// Logf logs; defaults to log.Printf. Typed text is never logged.
	Logf func(format string, args ...any)
}

const (
	defaultPing     = 25 * time.Second
	maxBackoff      = 30 * time.Second
	writeTimeout    = 10 * time.Second
	dialTimeout     = 20 * time.Second
	activeFor       = 3 * time.Second // tray shows "active" this long after input
	controllerFor   = 2 * time.Minute // "being controlled by …" this long after
	mediaTick       = time.Second
	mediaRefresh    = 5 * time.Second // position refresh while playing
	screenshotLimit = 10 * time.Minute
)

// pingEvery is how often the app pings the hub (a var so tests can hurry it).
var pingEvery = defaultPing

var errReload = errors.New("settings changed")

// Session keeps the live connection up. Create with New, then Run.
type Session struct {
	o      Options
	reload chan struct{}

	mu        sync.Mutex
	status    Status
	activeAt  time.Time
	controlAt time.Time
	expiry    *time.Timer
	playing   string // the active player's status, for play/pause
	machine   string

	clip clipSync
	tr   Translator
	// the last input error and when it was logged: while the lock screen or
	// an elevated window is up, every frame fails the same way
	lastErr   string
	lastErrAt time.Time
	media     chan struct{} // asks the media loop to publish now
}

// New makes a session.
func New(o Options) *Session {
	if o.Logf == nil {
		o.Logf = log.Printf
	}
	return &Session{o: o, reload: make(chan struct{}, 1), media: make(chan struct{}, 1)}
}

// Reload makes the session re-read its Params, reconnecting if they
// changed (a new token, a capability switched off, paused…).
func (s *Session) Reload() {
	select {
	case s.reload <- struct{}{}:
	default:
	}
}

// Status returns the current status.
func (s *Session) Status() Status {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.status
}

func (s *Session) update(fn func(st *Status)) {
	s.mu.Lock()
	before := fmt.Sprint(s.status)
	fn(&s.status)
	after := s.status
	s.mu.Unlock()
	if fmt.Sprint(after) != before && s.o.OnStatus != nil {
		s.o.OnStatus(after)
	}
}

// offered is the capabilities to announce: switched on, and working.
func (s *Session) offered(p Params) []string {
	if p.Paused || p.Token == "" || p.HubURL == "" {
		return nil
	}
	var out []string
	for _, c := range AllCaps {
		if p.Caps[c] && s.o.Backend.Has(c) {
			out = append(out, c)
		}
	}
	return out
}

func paramsKey(p Params, caps []string) string {
	// the transport's identity matters too: a new pin means a new transport
	return strings.Join([]string{p.HubURL, fmt.Sprintf("%p", p.Transport), p.Token, p.Session, strings.Join(caps, ",")}, "\x00")
}

// Run keeps connecting until ctx ends.
func (s *Session) Run(ctx context.Context) {
	backoff := time.Second
	for {
		p := s.o.Params()
		caps := s.offered(p)
		if len(caps) == 0 {
			s.update(func(st *Status) { *st = Status{} })
			select {
			case <-ctx.Done():
				return
			case <-s.reload:
				continue
			}
		}
		connected, err := s.connect(ctx, p, caps)
		if ctx.Err() != nil {
			s.update(func(st *Status) { st.Live = false })
			return
		}
		if errors.Is(err, errReload) {
			backoff = time.Second
			continue
		}
		if connected {
			backoff = time.Second
		}
		problem := describe(err, p)
		s.o.Logf("live connection: %s (retrying in %s)", problem, backoff)
		s.update(func(st *Status) { st.Offered, st.Live, st.Problem = caps, false, problem })
		t := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-s.reload:
			t.Stop()
			backoff = time.Second
			continue
		case <-t.C:
		}
		if backoff *= 2; backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

// dialError keeps the HTTP status of a refused handshake.
type dialError struct {
	status   int
	location string
	err      error
}

func (e *dialError) Error() string { return e.err.Error() }

func describe(err error, p Params) string {
	var de *dialError
	var ce websocket.CloseError
	switch {
	case err == nil:
		return "disconnected"
	case errors.As(err, &de) && de.status >= 300 && de.status < 400 && strings.Contains(de.location, "/login"):
		return "the hub wants a PIN (open Settings)"
	case errors.As(err, &de) && de.status == http.StatusForbidden:
		return "this PC hasn't been let in to the hub (open Settings)"
	case errors.As(err, &de) && de.status == http.StatusNotFound:
		return "this hub has no live connections (update the hub)"
	case errors.As(err, &de) && de.status != 0:
		return fmt.Sprintf("the hub refused the connection (%d)", de.status)
	case errors.As(err, &ce) && ce.Code == websocket.StatusPolicyViolation && strings.Contains(ce.Reason, "name this device"):
		return "the hub doesn't know this PC any more (removed?)"
	case errors.As(err, &ce):
		return fmt.Sprintf("the hub closed the connection (%d %s)", ce.Code, ce.Reason)
	}
	host := p.HubURL
	if u, e := hub.ParseHubURL(p.HubURL); e == nil {
		host = u.Host
	}
	msg := err.Error()
	if errors.Is(err, io.EOF) || strings.Contains(msg, "EOF") || strings.Contains(msg, "connection reset") {
		return "lost the connection to " + host
	}
	if errors.Is(err, context.DeadlineExceeded) || strings.Contains(msg, "dial tcp") || strings.Contains(msg, "no such host") {
		return "can't reach " + host
	}
	return msg
}

// wsURL is the hub's /ws address.
func wsURL(hubURL string) (string, error) {
	u, err := hub.ParseHubURL(hubURL)
	if err != nil {
		return "", err
	}
	switch u.Scheme {
	case "https":
		u.Scheme = "wss"
	default:
		u.Scheme = "ws"
	}
	u.Path += "/ws"
	return u.String(), nil
}

// connect runs one connection. connected reports whether the hub welcomed us.
func (s *Session) connect(ctx context.Context, p Params, caps []string) (connected bool, err error) {
	addr, err := wsURL(p.HubURL)
	if err != nil {
		return false, err
	}
	h := http.Header{}
	h.Set("Authorization", "Bearer "+p.Token)
	if p.Session != "" {
		h.Set("Cookie", "session="+p.Session) // a PIN hub's sign-in
	}
	h.Set("User-Agent", s.o.App)
	// no Origin header: the hub refuses handshakes from other sites' pages
	dctx, cancel := context.WithTimeout(ctx, dialTimeout)
	// don't follow redirects: a PIN hub redirects to /login, which is an
	// answer ("sign in first"), not somewhere to open a socket
	hc := &http.Client{Transport: p.Transport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	conn, resp, err := websocket.Dial(dctx, addr, &websocket.DialOptions{HTTPHeader: h, HTTPClient: hc})
	cancel()
	if err != nil {
		de := &dialError{err: err}
		if resp != nil {
			de.status, de.location = resp.StatusCode, resp.Header.Get("Location")
		}
		return false, de
	}
	defer conn.CloseNow()
	conn.SetReadLimit(readLimit)

	if err := s.send(ctx, conn, hello{T: "hello", Caps: caps, Platform: s.o.Platform, App: s.o.App}); err != nil {
		return false, err
	}
	wctx, cancel := context.WithTimeout(ctx, dialTimeout)
	var welcome Incoming
	err = readJSON(wctx, conn, &welcome)
	cancel()
	if err != nil {
		return false, err
	}
	if welcome.T != "welcome" {
		return false, fmt.Errorf("the hub answered %q instead of welcome", welcome.T)
	}
	s.mu.Lock()
	s.machine = p.Name
	if welcome.Device != nil && welcome.Device.Name != "" {
		s.machine = welcome.Device.Name
	}
	s.mu.Unlock()
	s.o.Logf("live: connected to %s as %s, offering %s", addr, s.machineName(), strings.Join(caps, ", "))
	s.update(func(st *Status) { st.Offered, st.Live, st.Problem = caps, true, "" })

	has := map[string]bool{}
	for _, c := range caps {
		has[c] = true
	}
	cctx, stop := context.WithCancelCause(ctx)
	var wg sync.WaitGroup
	defer func() {
		stop(nil)
		wg.Wait()
		// a drag or a held key mustn't outlive the controller
		if rel := s.tr.Release(); len(rel) > 0 && s.o.Backend.Input != nil {
			if err := s.o.Backend.Input.Inject(rel); err != nil {
				s.o.Logf("live: releasing buttons: %v", err)
			}
		}
		s.update(func(st *Status) { st.Live = false })
	}()

	var lastRecv, lastPing timeNow
	lastRecv.set(time.Now())
	key := paramsKey(p, caps)
	wg.Add(1)
	go func() { // pings, and settings changes
		defer wg.Done()
		t := time.NewTicker(pingEvery)
		defer t.Stop()
		for {
			select {
			case <-cctx.Done():
				return
			case <-s.reload:
				np := s.o.Params()
				if paramsKey(np, s.offered(np)) != key {
					stop(errReload)
					conn.Close(websocket.StatusNormalClosure, "settings changed")
					return
				}
			case now := <-t.C:
				if lp := lastPing.get(); !lp.IsZero() && lastRecv.get().Before(lp) {
					// nothing at all since the last ping, not even the pong
					stop(errors.New("no answer to ping"))
					conn.CloseNow()
					return
				}
				lastPing.set(now)
				if err := s.send(cctx, conn, map[string]string{"t": "ping"}); err != nil {
					stop(err)
					conn.CloseNow()
					return
				}
			}
		}
	}()
	if has[CapClipboard] {
		wg.Add(1)
		go func() { defer wg.Done(); s.clipLoop(cctx, conn) }()
	}
	if has[CapMedia] {
		wg.Add(1)
		go func() { defer wg.Done(); s.mediaLoop(cctx, conn) }()
	}

	for {
		var m Incoming
		err := readJSON(cctx, conn, &m)
		if err != nil {
			if cause := context.Cause(cctx); cause != nil && !errors.Is(cause, context.Canceled) {
				return true, cause
			}
			return true, err
		}
		lastRecv.set(time.Now())
		s.dispatch(cctx, conn, p, has, &m)
	}
}

// timeNow is a time shared between goroutines.
type timeNow struct {
	mu sync.Mutex
	t  time.Time
}

func (t *timeNow) set(v time.Time) { t.mu.Lock(); t.t = v; t.mu.Unlock() }
func (t *timeNow) get() time.Time  { t.mu.Lock(); defer t.mu.Unlock(); return t.t }

var errSkip = errors.New("not a JSON object")

func readJSON(ctx context.Context, conn *websocket.Conn, v *Incoming) error {
	for {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			return err
		}
		if typ != websocket.MessageText {
			continue
		}
		*v = Incoming{}
		if json.Unmarshal(data, v) == nil {
			return nil
		}
		// not an object we understand: ignore it, as the protocol asks
	}
}

// send writes one JSON frame. HTML escaping is off so text isn't inflated
// past the hub's frame limit by < and friends.
func (s *Session) send(ctx context.Context, conn *websocket.Conn, v any) error {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return err
	}
	data := bytes.TrimRight(b.Bytes(), "\n")
	if len(data) > maxFrame {
		return fmt.Errorf("message too large for the hub (%d bytes)", len(data))
	}
	wctx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	return conn.Write(wctx, websocket.MessageText, data)
}

func (s *Session) dispatch(ctx context.Context, conn *websocket.Conn, p Params, has map[string]bool, m *Incoming) {
	from := "Someone"
	if m.From != nil && m.From.Name != "" {
		from = m.From.Name
	}
	b := s.o.Backend
	switch m.T {
	case "input":
		if !has[CapInput] {
			return
		}
		s.activity(from, true)
		if strokes := s.tr.Translate(m.Ev); len(strokes) > 0 {
			if err := b.Input.Inject(strokes); err != nil {
				s.logQuietly("live: input from %s: %v", from, err)
			}
		}
	case "media":
		if !has[CapMedia] {
			return
		}
		s.activity(from, false)
		s.mu.Lock()
		playing := s.playing
		s.mu.Unlock()
		strokes, err := mediaAction(m.Action, m.Value, b.Volume, playing)
		if err != nil {
			s.o.Logf("live: media %s from %s: %v", m.Action, from, err)
		}
		if len(strokes) > 0 && b.Input != nil {
			if err := b.Input.Inject(strokes); err != nil {
				s.logQuietly("live: media keys: %v", err)
			}
		}
		s.refreshMedia()
	case "cmd":
		switch {
		case m.Cmd == "lock" && has[CapLock]:
			s.activity(from, false)
			s.o.Logf("live: %s locked this PC", from)
			if err := b.Lock(); err != nil {
				s.o.Logf("live: lock: %v", err)
			}
		case m.Cmd == "screenshot" && has[CapScreenshot] && m.From != nil && m.From.ID != "":
			s.activity(from, false)
			go s.screenshot(ctx, p, *m.From)
		}
	case "clip":
		if !has[CapClipboard] || m.Text == nil || *m.Text == "" || len(*m.Text) > maxClip {
			return
		}
		s.clip.applying(*m.Text)
		if err := b.Clipboard.WriteText(*m.Text); err != nil {
			s.o.Logf("live: clipboard from %s: %v", from, err)
		}
	case "rpc":
		// no rpc methods on Windows (files and SMS are the phone's); answer
		// so the controller isn't left waiting 30 s
		reply := map[string]string{"t": "rpc-result", "id": m.ID, "error": "droplet for Windows doesn't support " + m.Method}
		if err := s.send(ctx, conn, reply); err != nil {
			s.o.Logf("live: rpc reply: %v", err)
		}
	case "error":
		s.o.Logf("live: hub error (re %s): %s", m.Re, m.Error)
	}
	// pong, presence, state, welcome and anything newer: nothing to do
}

// logQuietly logs an error, but the same one at most every 30 s.
func (s *Session) logQuietly(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	now := time.Now()
	if msg == s.lastErr && now.Sub(s.lastErrAt) < 30*time.Second {
		return
	}
	s.lastErr, s.lastErrAt = msg, now
	s.o.Logf("%s", msg)
}

// activity notes remote control for the tray: who, and whether it was input.
func (s *Session) activity(from string, input bool) {
	now := time.Now()
	s.mu.Lock()
	s.controlAt = now
	if input {
		s.activeAt = now
	}
	if s.expiry == nil {
		s.expiry = time.AfterFunc(activeFor, s.expire)
	} else {
		s.expiry.Reset(activeFor)
	}
	s.mu.Unlock()
	s.update(func(st *Status) {
		st.Controller = from
		if input {
			st.Active = true
		}
	})
}

// expire clears "active" and later "being controlled" once they're stale.
func (s *Session) expire() {
	now := time.Now()
	s.mu.Lock()
	active := now.Sub(s.activeAt) < activeFor
	controlled := now.Sub(s.controlAt) < controllerFor
	switch {
	case active:
		s.expiry.Reset(activeFor - now.Sub(s.activeAt))
	case controlled:
		s.expiry.Reset(controllerFor - now.Sub(s.controlAt))
	}
	s.mu.Unlock()
	s.update(func(st *Status) {
		st.Active = active
		if !controlled {
			st.Controller = ""
		}
	})
}

func (s *Session) machineName() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.machine != "" {
		return s.machine
	}
	h, _ := os.Hostname()
	return h
}

// fileSafe keeps a name to letters, digits, dots, dashes and underscores.
func fileSafe(s string) string {
	s = strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_', r == '.':
			return r
		}
		return '-'
	}, strings.TrimSpace(s))
	s = strings.Trim(s, "-.")
	if s == "" {
		return "pc"
	}
	return s
}

// ScreenshotName is the file name for a screenshot taken now.
func ScreenshotName(machine string, t time.Time) string {
	return "screenshot-" + fileSafe(machine) + "-" + t.Format("20060102-150405") + ".png"
}

func (s *Session) screenshot(ctx context.Context, p Params, to Peer) {
	png, err := s.o.Backend.Capture()
	if err != nil {
		s.o.Logf("live: screenshot for %s: %v", to.Name, err)
		return
	}
	name := ScreenshotName(s.machineName(), time.Now())
	c, err := hub.NewWithTransport(p.HubURL, p.Token, p.Session, p.Transport)
	if err != nil {
		s.o.Logf("live: screenshot: %v", err)
		return
	}
	// the upload outlives the connection if need be: the requester still wants it
	uctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), screenshotLimit)
	defer cancel()
	if err := c.UploadData(uctx, to.ID, name, png); err != nil {
		s.o.Logf("live: screenshot upload to %s: %v", to.Name, err)
		return
	}
	s.o.Logf("live: sent %s (%d KB) to %s", name, len(png)/1024, to.Name)
	if s.o.Notify != nil {
		s.o.Notify(to.Name+" took a screenshot of this PC", "It was sent to "+to.Name+".")
	}
}

// --- clipboard ---------------------------------------------------------------------

func (s *Session) clipLoop(ctx context.Context, conn *websocket.Conn) {
	cb := s.o.Backend.Clipboard
	s.clip.reset() // each connection starts from what's on the clipboard now
	t := time.NewTicker(clipPoll)
	defer t.Stop()
	for {
		if text, ok := s.clip.observe(cb.Seq(), cb.ReadText, time.Now()); ok {
			if err := s.send(ctx, conn, map[string]string{"t": "clip", "text": text}); err != nil {
				s.o.Logf("live: sending the clipboard: %v", err)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// --- media -------------------------------------------------------------------------

func (s *Session) refreshMedia() {
	select {
	case s.media <- struct{}{}:
	default:
	}
}

// mediaLoop publishes the media state when it changes: at once for a new
// track, a play/pause or a volume change, and every few seconds while
// something plays (so position stays roughly right), never more than once
// a second.
func (s *Session) mediaLoop(ctx context.Context, conn *websocket.Conn) {
	b := s.o.Backend
	var mu sync.Mutex
	players := []Player{}
	if b.NowPlaying != nil {
		go b.NowPlaying.Run(ctx, func(ps []Player) {
			mu.Lock()
			players = append([]Player{}, ps...)
			mu.Unlock()
			s.refreshMedia()
		})
	}
	var lastKey string
	var lastAt time.Time
	publish := func(force bool) {
		mu.Lock()
		st := MediaState{Players: players}
		mu.Unlock()
		if b.Volume != nil {
			if v, err := b.Volume.Get(); err == nil {
				v.Level = float64(int(v.Level*1000+0.5)) / 1000
				st.Volume = &v
			}
		}
		active := ""
		if len(st.Players) > 0 {
			id := st.Players[0].ID // the platform lists the current session first
			st.Active = &id
			active = st.Players[0].Status
		}
		s.mu.Lock()
		s.playing = active
		s.mu.Unlock()
		key := mediaKey(st)
		now := time.Now()
		if now.Sub(lastAt) < time.Second {
			return
		}
		playing := active == "Playing"
		if key == lastKey && !(playing && now.Sub(lastAt) >= mediaRefresh) && !force {
			return
		}
		if err := s.send(ctx, conn, map[string]any{"t": "state", "kind": "media", "data": st}); err != nil {
			s.o.Logf("live: media state: %v", err)
			return
		}
		lastKey, lastAt = key, now
	}
	publish(true)
	t := time.NewTicker(mediaTick)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			publish(false)
		case <-s.media:
			publish(false)
		}
	}
}

// mediaKey identifies a media state, ignoring the position of whatever is
// playing (it moves every second; controllers can count along).
func mediaKey(st MediaState) string {
	c := st
	c.Players = make([]Player, len(st.Players))
	for i, p := range st.Players {
		if p.Status == "Playing" {
			p.Position = nil
		}
		c.Players[i] = p
	}
	b, _ := json.Marshal(c)
	return string(b)
}
