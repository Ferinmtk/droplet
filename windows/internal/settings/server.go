// Package settings serves the companion's settings page on 127.0.0.1, at a
// random port and an unguessable path, and a couple of control endpoints
// used by notification buttons.
package settings

import (
	"context"
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/agent"
	"github.com/Ferinmtk/droplet/windows/internal/config"
)

//go:embed page.html
var page []byte

// Server is the local settings/control server.
type Server struct {
	Agent *agent.Agent
	// OnSaved runs after settings are saved (e.g. to refresh the tray).
	OnSaved func()

	token string
	ln    net.Listener
	srv   *http.Server
}

// Start listens on a random loopback port.
func Start(a *agent.Agent) (*Server, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	s := &Server{Agent: a, token: hex.EncodeToString(b), ln: ln}
	s.srv = &http.Server{Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	go s.srv.Serve(ln)
	return s, nil
}

// URL is the settings page address, secret path included.
func (s *Server) URL() string {
	return "http://" + s.ln.Addr().String() + "/" + s.token + "/"
}

// Close stops the server.
func (s *Server) Close() error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	return s.srv.Shutdown(ctx)
}

func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	p := "/" + s.token
	mux.HandleFunc("GET "+p+"/{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:")
		w.Write(page)
	})
	mux.HandleFunc("GET "+p+"/api/settings", func(w http.ResponseWriter, r *http.Request) {
		cfg := s.Agent.Store.Get()
		writeJSON(w, http.StatusOK, map[string]any{
			"settings":       s.Agent.CurrentSettings(),
			"registered":     cfg.Registered(),
			"first_run":      !s.Agent.Store.Exists(),
			"status":         s.Agent.Status().Tooltip(),
			"default_hub":    config.DefaultHub,
			"remote":         s.Agent.CurrentRemote(),
			"live":           liveText(s.Agent.Status()),
			"hub":            hubState(s.Agent),
			"suggested_name": suggestedName(),
		})
	})
	mux.HandleFunc("GET "+p+"/api/discover", func(w http.ResponseWriter, r *http.Request) {
		found, err := s.Agent.Discover(r.Context())
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]any{"hubs": []agent.Found{}, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"hubs": found})
	})
	mux.HandleFunc("POST "+p+"/api/join", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			HubID  string `json:"hub_id"`
			Name   string `json:"name"`
			PIN    string `json:"pin"`
			Repair bool   `json:"repair"` // accept the paired hub's new certificate
		}
		if !readJSON(w, r, &in) {
			return
		}
		res, err := s.Agent.Join(r.Context(), in.HubID, in.Name, in.PIN, in.Repair)
		if !writeErr(w, err) {
			return
		}
		if s.OnSaved != nil {
			s.OnSaved()
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "join": res, "hub_url": s.Agent.Store.Get().HubURL})
	})
	mux.HandleFunc("POST "+p+"/api/pairing/pin", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			PIN string `json:"pin"`
		}
		if !readJSON(w, r, &in) {
			return
		}
		if !writeErr(w, s.Agent.SignInWithPIN(r.Context(), in.PIN)) {
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST "+p+"/api/pairing/cancel", func(w http.ResponseWriter, r *http.Request) {
		if !writeErr(w, s.Agent.CancelJoin()) {
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST "+p+"/api/repair", func(w http.ResponseWriter, r *http.Request) {
		res, err := s.Agent.Repair(r.Context())
		if !writeErr(w, err) {
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "repair": res})
	})
	mux.HandleFunc("POST "+p+"/api/remote", func(w http.ResponseWriter, r *http.Request) {
		var in agent.RemoteSettings
		if !readJSON(w, r, &in) {
			return
		}
		if err := s.Agent.SetRemote(in); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "remote": s.Agent.CurrentRemote()})
	})
	mux.HandleFunc("POST "+p+"/api/link", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			HubURL string `json:"hub_url"`
			HubID  string `json:"hub_id"` // a hub found on the LAN
			Code   string `json:"code"`
			PIN    string `json:"pin"`
		}
		if !readJSON(w, r, &in) {
			return
		}
		var res *agent.LinkResult
		var err error
		if in.HubID != "" {
			res, err = s.Agent.LinkLAN(r.Context(), in.HubID, in.Code, in.PIN)
		} else {
			res, err = s.Agent.Link(r.Context(), in.HubURL, in.Code, in.PIN)
		}
		if !writeErr(w, err) {
			return
		}
		if s.OnSaved != nil {
			s.OnSaved()
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "link": res, "hub_url": s.Agent.Store.Get().HubURL})
	})
	mux.HandleFunc("POST "+p+"/api/remove-old", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			ID string `json:"id"`
		}
		if !readJSON(w, r, &in) {
			return
		}
		if err := s.Agent.RemoveOld(r.Context(), in.ID); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST "+p+"/api/probe", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			HubURL string `json:"hub_url"`
			PIN    string `json:"pin"`
		}
		if !readJSON(w, r, &in) {
			return
		}
		writeJSON(w, http.StatusOK, s.Agent.ProbeHub(r.Context(), in.HubURL, in.PIN))
	})
	mux.HandleFunc("POST "+p+"/api/save", func(w http.ResponseWriter, r *http.Request) {
		var in agent.Settings
		if !readJSON(w, r, &in) {
			return
		}
		if !writeErr(w, s.Agent.Configure(r.Context(), in)) {
			return
		}
		if s.OnSaved != nil {
			s.OnSaved()
		}
		cfg := s.Agent.Store.Get()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "name": cfg.DeviceName, "hub_url": cfg.HubURL,
			"pending": cfg.Pending(), "code": cfg.PairCode})
	})
	mux.HandleFunc("POST "+p+"/api/reload", func(w http.ResponseWriter, r *http.Request) {
		// another droplet.exe (droplet link) changed config.json
		if err := s.Agent.Store.Reload(); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		s.Agent.Reset()
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST "+p+"/api/stop-ring", func(w http.ResponseWriter, r *http.Request) {
		s.Agent.StopRing()
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	return guard(s.ln.Addr().String(), mux)
}

// writeErr answers with err (a field error points at its field) and
// reports whether there was none.
func writeErr(w http.ResponseWriter, err error) bool {
	var fe *agent.FieldError
	switch {
	case err == nil:
		return true
	case errors.As(err, &fe):
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": fe.Msg, "field": fe.Field})
	default:
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return false
}

// suggestedName is this PC's own name, for a first run.
func suggestedName() string {
	h, err := os.Hostname()
	if err != nil {
		return ""
	}
	return strings.ToLower(h)
}

// hubState is the paired hub, how it's reached, and whatever needs the
// person: a join request waiting, or the hub's identity having changed.
func hubState(a *agent.Agent) map[string]any {
	cfg := a.Store.Get()
	st := a.Status()
	r, ok := a.Routes.Current()
	name := agent.HubName(cfg)
	out := map[string]any{
		"name":        name,
		"paired":      cfg.Hub != nil,
		"route":       r.Label(),
		"route_kind":  string(r.Kind),
		"connected":   ok && st.Connected,
		"pending":     cfg.Pending(),
		"code":        cfg.PairCode,
		"device":      cfg.DeviceName,
		"not_allowed": st.NotAllowed,
		"remote_url":  cfg.RemoteURL(),
	}
	if cfg.Hub != nil {
		out["id"] = cfg.Hub.ID
		out["pin_source"] = cfg.Hub.PinSource
		out["lan"] = cfg.Hub.LAN
	}
	text := ""
	switch {
	case cfg.Pending():
		text = "Waiting for one of your devices to let \"" + cfg.DeviceName + "\" in."
	case st.NotAllowed:
		text = name + " doesn't let this PC in any more (removed, or the request was declined). Join again below."
	case !cfg.Registered():
		text = "Not set up yet. Pick your hub below and choose Join, or enter its Tailscale address."
	case ok && st.Connected:
		text = "Connected to " + name + " " + r.Label() + "."
	case !st.Polled:
		text = "Connecting to " + name + "…"
	case st.Problem != "":
		text = "Not connected: " + st.Problem + "."
	default:
		text = "Can't reach " + name + " right now."
	}
	out["text"] = text
	if ch := a.Routes.Changed(); ch != nil {
		out["changed"] = map[string]any{"verified": ch.Verified, "addr": ch.Addr,
			"got": short(ch.Got), "want": short(ch.Want)}
	}
	return out
}

func short(fp string) string {
	if len(fp) > 16 {
		return fp[:16]
	}
	return fp
}

// liveText describes the live connection for the settings page.
func liveText(st agent.Status) map[string]any {
	r := st.Remote
	text := "Off: nothing is switched on, or remote control is paused."
	switch {
	case !st.Configured:
		text = "Not connected: set this PC up first."
	case r.Live && r.Controller != "":
		text = "Connected. Being controlled by " + r.Controller + "."
	case r.Live:
		text = "Connected. Other devices can use what's switched on below."
	case len(r.Offered) > 0 && r.Problem != "":
		text = "Not connected: " + r.Problem + ". Retrying…"
	case len(r.Offered) > 0:
		text = "Connecting…"
	}
	return map[string]any{"live": r.Live, "text": text}
}

// guard rejects requests whose Host isn't this loopback address (DNS
// rebinding) and cross-site POSTs, on top of the secret path.
func guard(addr string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != addr {
			http.Error(w, "wrong host", http.StatusForbidden)
			return
		}
		if r.Method == http.MethodPost {
			if o := r.Header.Get("Origin"); o != "" && o != "http://"+addr {
				http.Error(w, "cross-origin", http.StatusForbidden)
				return
			}
			if r.Header.Get("Content-Type") != "application/json" {
				http.Error(w, "json only", http.StatusUnsupportedMediaType)
				return
			}
		}
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request"})
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

// --- finding the running instance -------------------------------------------------

// instance.json tells a second droplet.exe (a relaunch, or a notification
// button) where the running one's settings/control server is.

func instanceFile() (string, error) {
	dir, err := config.Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "instance.json"), nil
}

// Publish records this server's URL for other droplet.exe processes.
func (s *Server) Publish() error {
	p, err := instanceFile()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	data, _ := json.Marshal(map[string]any{"url": s.URL(), "pid": os.Getpid()})
	return os.WriteFile(p, data, 0o600)
}

// Unpublish removes the record (on quit).
func Unpublish() {
	if p, err := instanceFile(); err == nil {
		os.Remove(p)
	}
}

// RunningURL is the running instance's settings URL, if there is one.
func RunningURL() (string, bool) {
	p, err := instanceFile()
	if err != nil {
		return "", false
	}
	data, err := os.ReadFile(p)
	if err != nil {
		return "", false
	}
	var rec struct {
		URL string `json:"url"`
	}
	if json.Unmarshal(data, &rec) != nil || !strings.HasPrefix(rec.URL, "http://127.0.0.1:") {
		return "", false
	}
	// make sure it's alive, not left over from a crash
	c := http.Client{Timeout: 2 * time.Second}
	resp, err := c.Get(rec.URL + "api/settings")
	if err != nil {
		return "", false
	}
	resp.Body.Close()
	return rec.URL, resp.StatusCode == http.StatusOK
}

// PostRunning calls a control endpoint (e.g. "api/stop-ring") on the running instance.
func PostRunning(endpoint string) error {
	u, ok := RunningURL()
	if !ok {
		return errors.New("droplet isn't running")
	}
	c := http.Client{Timeout: 15 * time.Second}
	resp, err := c.Post(u+endpoint, "application/json", strings.NewReader("{}"))
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return errors.New(resp.Status)
	}
	return nil
}
