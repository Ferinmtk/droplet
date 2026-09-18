package remote

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// fakeHub accepts /ws and /upload like the real hub, one helper at a time.
type fakeHub struct {
	t       *testing.T
	srv     *httptest.Server
	conns   chan *hubConn
	mu      sync.Mutex
	uploads []upload
	auth    []string
	origins []string
}

type upload struct {
	to, name, auth string
	data           []byte
}

type hubConn struct {
	c     *websocket.Conn
	hello map[string]any
	in    chan map[string]any
}

func newFakeHub(t *testing.T) *fakeHub {
	h := &fakeHub{t: t, conns: make(chan *hubConn, 4)}
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		h.auth = append(h.auth, r.Header.Get("Authorization"))
		h.origins = append(h.origins, r.Header.Get("Origin"))
		h.mu.Unlock()
		if r.Header.Get("Authorization") != "Bearer tok" {
			http.Error(w, "no", http.StatusBadRequest)
			return
		}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		c.SetReadLimit(1 << 20)
		ctx := context.Background()
		_, data, err := c.Read(ctx)
		if err != nil {
			return
		}
		hc := &hubConn{c: c, in: make(chan map[string]any, 64)}
		json.Unmarshal(data, &hc.hello)
		welcome, _ := json.Marshal(map[string]any{"t": "welcome", "conn": "c1",
			"device": map[string]string{"id": "pc1", "name": "maryanne"}, "devices": map[string]any{}, "state": map[string]any{}})
		c.Write(ctx, websocket.MessageText, welcome)
		h.conns <- hc
		for {
			_, data, err := c.Read(ctx)
			if err != nil {
				close(hc.in)
				return
			}
			var m map[string]any
			json.Unmarshal(data, &m)
			if m["t"] == "ping" {
				c.Write(ctx, websocket.MessageText, []byte(`{"t":"pong"}`))
			}
			hc.in <- m
		}
	})
	mux.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		f, hdr, err := r.FormFile("files")
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		data, _ := io.ReadAll(f)
		h.mu.Lock()
		h.uploads = append(h.uploads, upload{r.URL.Query().Get("to"), hdr.Filename, r.Header.Get("Authorization"), data})
		h.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"saved":["` + hdr.Filename + `"]}`))
	})
	h.srv = httptest.NewServer(mux)
	t.Cleanup(h.srv.Close)
	return h
}

func (h *fakeHub) next() *hubConn {
	h.t.Helper()
	select {
	case c := <-h.conns:
		return c
	case <-time.After(5 * time.Second):
		h.t.Fatal("the app didn't connect")
		return nil
	}
}

func (c *hubConn) send(t *testing.T, v any) {
	t.Helper()
	b, _ := json.Marshal(v)
	if err := c.c.Write(context.Background(), websocket.MessageText, b); err != nil {
		t.Fatal(err)
	}
}

// expect waits for a frame of type typ from the app, skipping others.
func (c *hubConn) expect(t *testing.T, typ string) map[string]any {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case m, ok := <-c.in:
			if !ok {
				t.Fatalf("connection closed waiting for %q", typ)
			}
			if m["t"] == typ {
				return m
			}
		case <-deadline:
			t.Fatalf("no %q from the app", typ)
		}
	}
}

func waitCalls(t *testing.T, f *Fake, want func([]FakeCall) bool) []FakeCall {
	t.Helper()
	var all []FakeCall
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		all = append(all, f.Taken()...)
		if want(all) {
			return all
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("backend calls never matched; got %+v", all)
	return nil
}

func has(call string) func([]FakeCall) bool {
	return func(cs []FakeCall) bool {
		for _, c := range cs {
			if c.Call == call {
				return true
			}
		}
		return false
	}
}

type paramBox struct {
	mu sync.Mutex
	p  Params
}

func (b *paramBox) get() Params          { b.mu.Lock(); defer b.mu.Unlock(); return b.p }
func (b *paramBox) set(fn func(*Params)) { b.mu.Lock(); fn(&b.p); b.mu.Unlock() }

func startSession(t *testing.T, h *fakeHub, caps map[string]bool) (*Session, *Fake, *paramBox, chan string) {
	fake := &Fake{}
	box := &paramBox{p: Params{HubURL: h.srv.URL, Token: "tok", Name: "maryanne", Caps: caps}}
	notes := make(chan string, 8)
	s := New(Options{
		Params: box.get, Backend: fake.Backend(), App: "droplet-windows/test", Platform: "windows",
		Notify: func(title, body string) { notes <- title },
		Logf:   t.Logf,
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { s.Run(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	return s, fake, box, notes
}

func allCaps() map[string]bool {
	return map[string]bool{CapInput: true, CapMedia: true, CapLock: true, CapScreenshot: true, CapClipboard: true}
}

func TestSessionEndToEnd(t *testing.T) {
	h := newFakeHub(t)
	s, fake, _, notes := startSession(t, h, allCaps())
	c := h.next()

	if got := c.hello["caps"]; strings.Join(toStrings(got), ",") != "input,media,lock,screenshot,clipboard" {
		t.Fatalf("hello caps %v", got)
	}
	if c.hello["platform"] != "windows" || c.hello["app"] != "droplet-windows/test" {
		t.Fatalf("hello %v", c.hello)
	}
	h.mu.Lock()
	if h.origins[0] != "" {
		t.Fatalf("sent Origin %q", h.origins[0])
	}
	h.mu.Unlock()
	// the media state goes out straight away (volume first; the player
	// list follows as soon as the platform reports it)
	st := c.expect(t, "state")
	if st["kind"] != "media" || st["data"].(map[string]any)["volume"] == nil {
		t.Fatalf("media state %v", st)
	}
	for len(st["data"].(map[string]any)["players"].([]any)) == 0 {
		st = c.expect(t, "state")
	}
	if data := st["data"].(map[string]any); data["active"] != "fake" {
		t.Fatalf("media state %v", st)
	}

	from := map[string]string{"id": "ph1", "name": "Home"}
	c.send(t, map[string]any{"t": "input", "from": from, "ev": []map[string]any{
		{"k": "move", "dx": 4.5, "dy": -2}, {"k": "move", "dx": 0.5, "dy": 0}, {"k": "text", "s": "hi"},
		{"k": "button", "b": "left", "down": true}}})
	calls := waitCalls(t, fake, has("inject"))
	ss := calls[0].Strokes
	if ss[0] != (Stroke{Kind: StrokeMove, DX: 5, DY: -2}) || ss[1].Unit != 'h' || ss[3].Unit != 'i' ||
		ss[len(ss)-1] != (Stroke{Kind: StrokeButton, Button: ButtonLeft, Down: true}) {
		t.Fatalf("strokes %+v", ss)
	}
	if st := s.Status(); !st.Live || !st.Active || st.Controller != "Home" {
		t.Fatalf("status %+v", st)
	}

	c.send(t, map[string]any{"t": "media", "from": from, "action": "volume", "value": 0.25})
	waitCalls(t, fake, has("volume"))
	c.send(t, map[string]any{"t": "media", "from": from, "action": "next"})
	calls = waitCalls(t, fake, has("inject"))
	if calls[0].Strokes[0].Key.VK != VKMediaNextTrack {
		t.Fatalf("next: %+v", calls)
	}
	c.send(t, map[string]any{"t": "cmd", "from": from, "cmd": "lock"})
	waitCalls(t, fake, has("lock"))

	c.send(t, map[string]any{"t": "cmd", "from": from, "cmd": "screenshot"})
	waitCalls(t, fake, has("capture"))
	select {
	case title := <-notes:
		if title != "Home took a screenshot of this PC" {
			t.Fatalf("toast %q", title)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no screenshot toast")
	}
	h.mu.Lock()
	up := h.uploads[0]
	h.mu.Unlock()
	if up.to != "ph1" || up.auth != "Bearer tok" || !regexp.MustCompile(`^screenshot-maryanne-\d{8}-\d{6}\.png$`).MatchString(up.name) ||
		string(up.data[:4]) != "\x89PNG" {
		t.Fatalf("upload %+v", up.name)
	}

	// clipboard: incoming text is written, and not echoed back
	c.send(t, map[string]any{"t": "clip", "from": from, "text": "from the phone"})
	calls = waitCalls(t, fake, has("clip"))
	fake.Copy("copied here <b>&</b>")
	m := c.expect(t, "clip")
	if m["text"] != "copied here <b>&</b>" {
		t.Fatalf("clip out %v (an echo of the phone's text?)", m)
	}

	// rpc gets a polite refusal with the same id
	c.send(t, map[string]any{"t": "rpc", "id": "abc:r7", "method": "files.list", "from": from})
	r := c.expect(t, "rpc-result")
	if r["id"] != "abc:r7" || !strings.Contains(r["error"].(string), "doesn't support") {
		t.Fatalf("rpc %v", r)
	}
	// unknown messages are ignored, not fatal
	c.send(t, map[string]any{"t": "hologram", "from": from})
	c.send(t, map[string]any{"t": "input", "from": from, "ev": []map[string]any{{"k": "key", "key": "Enter"}}})
	waitCalls(t, fake, has("inject"))

	// the hub goes away mid-drag: the button is let go, and the app comes back
	c.c.Close(websocket.StatusGoingAway, "restart")
	calls = waitCalls(t, fake, has("inject"))
	if calls[0].Strokes[0] != (Stroke{Kind: StrokeButton, Button: ButtonLeft}) {
		t.Fatalf("release %+v", calls)
	}
	h.next()
}

func TestSessionReloadChangesCaps(t *testing.T) {
	h := newFakeHub(t)
	s, fake, box, _ := startSession(t, h, map[string]bool{CapInput: true, CapLock: true})
	c := h.next()
	if strings.Join(toStrings(c.hello["caps"]), ",") != "input,lock" {
		t.Fatalf("caps %v", c.hello["caps"])
	}
	// a Reload with nothing changed keeps the connection
	s.Reload()
	time.Sleep(100 * time.Millisecond)
	c.send(t, map[string]any{"t": "cmd", "cmd": "lock", "from": map[string]string{"id": "x", "name": "x"}})
	waitCalls(t, fake, has("lock"))

	// switching input off reconnects without it; input sent anyway is ignored
	box.set(func(p *Params) { p.Caps = map[string]bool{CapLock: true} })
	s.Reload()
	c2 := h.next()
	if strings.Join(toStrings(c2.hello["caps"]), ",") != "lock" {
		t.Fatalf("caps %v", c2.hello["caps"])
	}
	c2.send(t, map[string]any{"t": "input", "ev": []map[string]any{{"k": "click"}}})
	c2.send(t, map[string]any{"t": "cmd", "cmd": "lock", "from": map[string]string{"id": "x", "name": "x"}})
	calls := waitCalls(t, fake, has("lock"))
	for _, cl := range calls {
		if cl.Call == "inject" {
			t.Fatal("input was injected with input switched off")
		}
	}
	// pausing drops the connection entirely
	box.set(func(p *Params) { p.Paused = true })
	s.Reload()
	if _, ok := <-c2.in; ok {
		for range c2.in {
		}
	}
	deadline := time.Now().Add(2 * time.Second)
	for (s.Status().Live || len(s.Status().Offered) > 0) && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if st := s.Status(); st.Live || len(st.Offered) != 0 {
		t.Fatalf("paused status %+v", st)
	}
}

func TestSessionPingsAndBacksOff(t *testing.T) {
	old := pingEvery
	pingEvery = 100 * time.Millisecond
	defer func() { pingEvery = old }()
	h := newFakeHub(t)
	startSession(t, h, map[string]bool{CapLock: true})
	c := h.next()
	c.expect(t, "ping")
	c.expect(t, "ping")
}

func TestSessionBadTokenStatus(t *testing.T) {
	h := newFakeHub(t)
	fake := &Fake{}
	s := New(Options{Params: func() Params {
		return Params{HubURL: h.srv.URL, Token: "wrong", Caps: allCaps()}
	}, Backend: fake.Backend(), Logf: t.Logf})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go s.Run(ctx)
	deadline := time.Now().Add(3 * time.Second)
	for s.Status().Problem == "" && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if st := s.Status(); st.Live || !strings.Contains(st.Problem, "400") {
		t.Fatalf("status %+v", st)
	}
}

func TestWSURLAndNames(t *testing.T) {
	for in, want := range map[string]string{
		"https://t15.tail7375fe.ts.net": "wss://t15.tail7375fe.ts.net/ws",
		"http://127.0.0.1:8813/":        "ws://127.0.0.1:8813/ws",
		"t15.tail7375fe.ts.net":         "wss://t15.tail7375fe.ts.net/ws",
		"https://example.com/droplet":   "wss://example.com/droplet/ws",
	} {
		if got, err := wsURL(in); err != nil || got != want {
			t.Errorf("wsURL(%q) = %q, %v", in, got, err)
		}
	}
	ts := time.Date(2026, 9, 18, 7, 5, 3, 0, time.Local)
	if got := ScreenshotName("Mary Anne's PC", ts); got != "screenshot-Mary-Anne-s-PC-20260918-070503.png" {
		t.Fatalf("name %q", got)
	}
	if got := ScreenshotName("", ts); got != "screenshot-pc-20260918-070503.png" {
		t.Fatalf("name %q", got)
	}
}

func toStrings(v any) []string {
	var out []string
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	return out
}

func TestSessionPINRedirect(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/login", http.StatusFound) // every request, as a PIN hub does
	}))
	defer srv.Close()
	s := New(Options{Params: func() Params {
		return Params{HubURL: srv.URL, Token: "tok", Caps: map[string]bool{CapLock: true}}
	}, Backend: (&Fake{}).Backend(), Logf: t.Logf})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go s.Run(ctx)
	deadline := time.Now().Add(3 * time.Second)
	for s.Status().Problem == "" && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if p := s.Status().Problem; !strings.Contains(p, "PIN") {
		t.Fatalf("problem %q", p)
	}
}
