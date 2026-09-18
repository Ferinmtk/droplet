// Package hubtest is a small in-memory imitation of a droplet hub's API,
// for testing the client and the agent without Python.
package hubtest

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"time"
)

// Device is a registered device.
type Device struct {
	ID, Name, Token string
	Links           []string // tokens of apps linked to this device
}

// Hub is the fake hub's state. Lock Mu to inspect or change it from a test.
type Hub struct {
	Mu       sync.Mutex
	Server   *httptest.Server
	PIN      string // when set, requests need the session cookie from /login
	Ring     bool   // whether ring endpoints exist
	Devices  []*Device
	Inbox    map[string]map[string]InboxFile // device id -> name -> file
	Received map[string][]byte               // hub uploads (to=hub)
	Texts    []string                        // text saved on the hub
	Chats    []Message
	Read     map[string]map[string]float64 // reader -> sender -> ts
	ActiveRg map[string]*Ring              // device id -> ring
	Rang     []string                      // targets rung
	Codes    map[string]string             // link code -> device id
	Removed  []string                      // device ids removed
}

// LinkCode makes a one-time code for linking an app to d.
func (h *Hub) LinkCode(d *Device) string {
	h.Mu.Lock()
	defer h.Mu.Unlock()
	code := "4" + rid()[:5]
	code = strings.Map(func(r rune) rune {
		if r >= 'a' {
			return '0' + (r-'a')%10
		}
		return r
	}, code)
	h.Codes[code] = d.ID
	return code
}

// InboxFile is a file waiting in a device's inbox.
type InboxFile struct {
	Data  []byte
	From  string
	Mtime int64
}

// Message is a chat message.
type Message struct {
	ID   string  `json:"id"`
	From string  `json:"from"`
	To   string  `json:"to"`
	Text string  `json:"text"`
	TS   float64 `json:"ts"`
}

// Ring is an active ring.
type Ring struct {
	ID   string  `json:"id"`
	From string  `json:"from"`
	TS   float64 `json:"ts"`
}

// New starts a fake hub; close it with h.Server.Close().
func New() *Hub {
	h := &Hub{
		Inbox:    map[string]map[string]InboxFile{},
		Received: map[string][]byte{},
		Read:     map[string]map[string]float64{},
		ActiveRg: map[string]*Ring{},
		Codes:    map[string]string{},
	}
	h.Server = httptest.NewServer(h)
	return h
}

func rid() string {
	b := make([]byte, 6)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// AddDevice registers a device directly (another phone, say) and returns it.
func (h *Hub) AddDevice(name string) *Device {
	h.Mu.Lock()
	defer h.Mu.Unlock()
	d := &Device{ID: rid(), Name: name, Token: rid()}
	h.Devices = append(h.Devices, d)
	return d
}

// Deliver puts a file in a device's inbox, as if another device sent it.
func (h *Hub) Deliver(to *Device, from, name string, data []byte) {
	h.Mu.Lock()
	defer h.Mu.Unlock()
	if h.Inbox[to.ID] == nil {
		h.Inbox[to.ID] = map[string]InboxFile{}
	}
	h.Inbox[to.ID][name] = InboxFile{Data: data, From: from, Mtime: time.Now().Unix()}
}

// Say adds a chat message from one device to another.
func (h *Hub) Say(from, to *Device, text string) {
	h.Mu.Lock()
	defer h.Mu.Unlock()
	h.Chats = append(h.Chats, Message{ID: rid(), From: from.ID, To: to.ID, Text: text, TS: float64(time.Now().UnixNano()) / 1e9})
}

func (h *Hub) byToken(r *http.Request) *Device {
	token := ""
	if a := r.Header.Get("Authorization"); strings.HasPrefix(a, "Bearer ") {
		token = strings.TrimPrefix(a, "Bearer ")
	} else if c, err := r.Cookie("droplet_device"); err == nil {
		token = c.Value
	}
	if token == "" {
		return nil
	}
	for _, d := range h.Devices {
		if d.Token == token {
			return d
		}
		for _, l := range d.Links {
			if l == token {
				return d
			}
		}
	}
	return nil
}

func (h *Hub) get(id string) *Device {
	for _, d := range h.Devices {
		if d.ID == id {
			return d
		}
	}
	return nil
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.Mu.Lock()
	defer h.Mu.Unlock()
	p := r.URL.Path
	if p == "/login" {
		if r.Method == http.MethodPost && r.FormValue("pin") == h.PIN {
			http.SetCookie(w, &http.Cookie{Name: "session", Value: "authed", Path: "/"})
			http.Redirect(w, r, "/", http.StatusFound)
			return
		}
		w.Write([]byte("<form>wrong</form>"))
		return
	}
	if h.PIN != "" {
		if c, err := r.Cookie("session"); err != nil || c.Value != "authed" {
			http.Redirect(w, r, "/login", http.StatusFound)
			return
		}
	}
	me := h.byToken(r)
	switch {
	case p == "/api/me":
		var dev any
		var suggested any = "maryanne"
		if me != nil {
			dev = map[string]any{"id": me.ID, "name": me.Name, "push": false}
			suggested = nil
		}
		writeJSON(w, 200, map[string]any{"device": dev, "suggested": suggested, "push_key": nil, "hub_url": nil})
	case p == "/api/device" && r.Method == http.MethodPost:
		var in struct{ Name string }
		json.NewDecoder(r.Body).Decode(&in)
		for _, d := range h.Devices {
			if strings.EqualFold(d.Name, in.Name) && d != me {
				writeJSON(w, 409, map[string]string{"error": in.Name + " is already a device here."})
				return
			}
		}
		if me != nil {
			me.Name = in.Name
			writeJSON(w, 200, map[string]string{"id": me.ID, "name": me.Name})
			return
		}
		d := &Device{ID: rid(), Name: in.Name, Token: rid()}
		h.Devices = append(h.Devices, d)
		http.SetCookie(w, &http.Cookie{Name: "droplet_device", Value: d.Token, MaxAge: 5 * 365 * 24 * 3600, HttpOnly: true})
		writeJSON(w, 200, map[string]string{"id": d.ID, "name": d.Name})
	case p == "/api/device/link" && r.Method == http.MethodPost:
		var in struct{ Code, Client string }
		json.NewDecoder(r.Body).Decode(&in)
		id, ok := h.Codes[in.Code]
		delete(h.Codes, in.Code)
		d := h.get(id)
		if !ok || d == nil {
			writeJSON(w, 403, map[string]string{"error": "That code is wrong or has expired. Make a new one."})
			return
		}
		tok := rid()
		d.Links = append(d.Links, tok)
		writeJSON(w, 200, map[string]string{"id": d.ID, "name": d.Name, "token": tok})
	case strings.HasPrefix(p, "/api/device/") && strings.HasSuffix(p, "/remove") && r.Method == http.MethodPost:
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/api/device/"), "/remove")
		for i, d := range h.Devices {
			if d.ID == id {
				h.Devices = append(h.Devices[:i], h.Devices[i+1:]...)
				h.Removed = append(h.Removed, id)
				writeJSON(w, 200, map[string]string{"removed": id})
				return
			}
		}
		http.NotFound(w, r)
	case p == "/api/files":
		devs := []map[string]any{}
		for _, d := range h.Devices {
			devs = append(devs, map[string]any{"id": d.ID, "name": d.Name, "online": true, "push": false, "self": d == me})
		}
		inbox := []map[string]any{}
		unread := map[string]int{}
		if me != nil {
			for name, f := range h.Inbox[me.ID] {
				inbox = append(inbox, map[string]any{"name": name, "size": len(f.Data), "mtime": f.Mtime, "image": false, "from": f.From})
			}
			for _, m := range h.Chats {
				if m.To == me.ID && m.TS > h.Read[me.ID][m.From] {
					unread[m.From]++
				}
			}
		}
		writeJSON(w, 200, map[string]any{"received": []any{}, "shared": []any{}, "inbox": inbox, "devices": devs, "unread": unread, "tailnet": []any{}})
	case strings.HasPrefix(p, "/api/chat/"):
		other := h.get(strings.TrimPrefix(p, "/api/chat/"))
		if me == nil || other == nil {
			http.NotFound(w, r)
			return
		}
		var msgs []Message
		for _, m := range h.Chats {
			if (m.From == me.ID && m.To == other.ID) || (m.From == other.ID && m.To == me.ID) {
				msgs = append(msgs, m)
			}
		}
		if len(msgs) > 0 {
			if h.Read[me.ID] == nil {
				h.Read[me.ID] = map[string]float64{}
			}
			h.Read[me.ID][other.ID] = msgs[len(msgs)-1].TS
		}
		writeJSON(w, 200, map[string]any{"with": map[string]string{"id": other.ID, "name": other.Name}, "messages": msgs})
	case p == "/upload" && r.Method == http.MethodPost:
		to := r.URL.Query().Get("to")
		var dest *Device
		if to != "" && to != "hub" {
			if dest = h.get(to); dest == nil {
				http.NotFound(w, r)
				return
			}
		}
		mr, err := r.MultipartReader()
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		saved := []string{}
		for {
			part, err := mr.NextPart()
			if err != nil {
				break
			}
			data, _ := io.ReadAll(part)
			name := part.FileName()
			saved = append(saved, name)
			if dest == nil {
				h.Received[name] = data
			} else {
				if h.Inbox[dest.ID] == nil {
					h.Inbox[dest.ID] = map[string]InboxFile{}
				}
				from := "someone"
				if me != nil {
					from = me.Name
				}
				h.Inbox[dest.ID][name] = InboxFile{Data: data, From: from, Mtime: time.Now().Unix()}
			}
		}
		writeJSON(w, 200, map[string]any{"saved": saved})
	case p == "/text" && r.Method == http.MethodPost:
		text, to := strings.TrimSpace(r.FormValue("text")), r.FormValue("to")
		if text == "" {
			writeJSON(w, 400, map[string]string{"error": "empty"})
			return
		}
		if to == "" || to == "hub" {
			h.Texts = append(h.Texts, text)
			writeJSON(w, 200, map[string]string{"saved": "text.txt"})
			return
		}
		dest := h.get(to)
		if dest == nil {
			http.NotFound(w, r)
			return
		}
		if me == nil {
			writeJSON(w, 400, map[string]string{"error": "Name this device first"})
			return
		}
		m := Message{ID: rid(), From: me.ID, To: dest.ID, Text: text, TS: float64(time.Now().UnixNano()) / 1e9}
		h.Chats = append(h.Chats, m)
		writeJSON(w, 200, map[string]any{"message": m})
	case strings.HasPrefix(p, "/d/inbox/"):
		name := strings.TrimPrefix(p, "/d/inbox/")
		if me == nil {
			http.NotFound(w, r)
			return
		}
		f, ok := h.Inbox[me.ID][name]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Disposition", "attachment")
		w.Write(f.Data)
	case strings.HasPrefix(p, "/delete/inbox/") && r.Method == http.MethodPost:
		name := strings.TrimPrefix(p, "/delete/inbox/")
		if me == nil || h.Inbox[me.ID] == nil {
			http.NotFound(w, r)
			return
		}
		if _, ok := h.Inbox[me.ID][name]; !ok {
			http.NotFound(w, r)
			return
		}
		delete(h.Inbox[me.ID], name)
		writeJSON(w, 200, map[string]string{"deleted": name})
	case strings.HasPrefix(p, "/api/ring") || strings.HasSuffix(p, "/ring"):
		h.ring(w, r, me)
	default:
		http.NotFound(w, r)
	}
}

func (h *Hub) ring(w http.ResponseWriter, r *http.Request, me *Device) {
	if !h.Ring {
		http.NotFound(w, r)
		return
	}
	p := r.URL.Path
	switch {
	case p == "/api/ring" && r.Method == http.MethodGet:
		var rg any
		if me != nil && h.ActiveRg[me.ID] != nil {
			rg = h.ActiveRg[me.ID]
		}
		writeJSON(w, 200, map[string]any{"ring": rg})
	case p == "/api/ring/stop" && r.Method == http.MethodPost:
		if me != nil {
			delete(h.ActiveRg, me.ID)
		}
		writeJSON(w, 200, map[string]bool{"ok": true})
	case p == "/api/hub/ring" && r.Method == http.MethodPost:
		h.Rang = append(h.Rang, "hub")
		writeJSON(w, 200, map[string]bool{"ok": true})
	case strings.HasPrefix(p, "/api/device/") && r.Method == http.MethodPost:
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/api/device/"), "/ring")
		d := h.get(id)
		if d == nil {
			http.NotFound(w, r)
			return
		}
		from := "someone"
		if me != nil {
			from = me.Name
		}
		h.ActiveRg[d.ID] = &Ring{ID: rid(), From: from, TS: float64(time.Now().Unix())}
		h.Rang = append(h.Rang, d.ID)
		writeJSON(w, 200, map[string]bool{"ok": true})
	default:
		http.NotFound(w, r)
	}
}
