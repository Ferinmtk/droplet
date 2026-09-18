// Package remote is the tray app's live connection to the hub (see
// docs/remote.md): it lets other devices move the mouse, type, control media,
// lock the PC, take screenshots and share the clipboard in real time.
//
// Everything here is platform neutral. The desktop side (SendInput, Core
// Audio, the clipboard, screen capture) sits behind the Backend interfaces,
// implemented for Windows in package platform, so the protocol handling and
// the event-to-action translation are tested on any OS.
package remote

import "encoding/json"

// Capability names, as used in hello and by the hub's routing.
const (
	CapInput      = "input"
	CapMedia      = "media"
	CapLock       = "lock"
	CapScreenshot = "screenshot"
	CapClipboard  = "clipboard"
)

// AllCaps lists the capabilities this app can offer, in hello order.
var AllCaps = []string{CapInput, CapMedia, CapLock, CapScreenshot, CapClipboard}

const (
	maxFrame   = 512 * 1024 // the hub's limit for one frame
	maxClip    = 256 * 1024 // clipboard text limit, in bytes of UTF-8
	readLimit  = 1 << 20    // frames we accept from the hub (it forwards ≤ 512 KB)
	helloGrace = 15         // seconds the hub waits for hello
)

// Peer is a device as the hub names it in "from" and "device".
type Peer struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// DeviceInfo is one entry of welcome.devices / presence.devices.
type DeviceInfo struct {
	Caps []string `json:"caps"`
	Apps []struct {
		Platform string `json:"platform"`
		App      string `json:"app"`
	} `json:"apps"`
}

// Incoming is any frame from the hub. Only the fields of its type are set.
type Incoming struct {
	T    string `json:"t"`
	From *Peer  `json:"from,omitempty"`

	// welcome
	Conn    string                `json:"conn,omitempty"`
	Device  *Peer                 `json:"device,omitempty"`
	Devices map[string]DeviceInfo `json:"devices,omitempty"`

	// input
	Ev []Event `json:"ev,omitempty"`

	// media
	Action string          `json:"action,omitempty"`
	Player string          `json:"player,omitempty"`
	Value  json.RawMessage `json:"value,omitempty"`

	// cmd
	Cmd string `json:"cmd,omitempty"`

	// clip
	Text *string `json:"text,omitempty"`

	// rpc
	ID     string `json:"id,omitempty"`
	Method string `json:"method,omitempty"`

	// error
	Re    string `json:"re,omitempty"`
	Error string `json:"error,omitempty"`
}

// Event is one entry of an input message's "ev" list.
type Event struct {
	K    string   `json:"k"`
	DX   float64  `json:"dx,omitempty"`
	DY   float64  `json:"dy,omitempty"`
	B    string   `json:"b,omitempty"`
	Down bool     `json:"down,omitempty"`
	N    int      `json:"n,omitempty"`
	S    string   `json:"s,omitempty"`
	Key  string   `json:"key,omitempty"`
	Mods []string `json:"mods,omitempty"`
}

// hello is the first frame this app sends.
type hello struct {
	T        string   `json:"t"`
	Caps     []string `json:"caps"`
	Platform string   `json:"platform"`
	App      string   `json:"app"`
}

// Volume is the system output volume, as published in media state.
type Volume struct {
	Level float64 `json:"level"`
	Muted bool    `json:"muted"`
}

// Player is one media session, in the protocol's media state format.
type Player struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Status      string   `json:"status"` // Playing | Paused | Stopped
	Title       string   `json:"title"`
	Artist      string   `json:"artist"`
	Album       string   `json:"album"`
	Art         *string  `json:"art"` // data: URL, or null
	Position    *float64 `json:"position"`
	Length      *float64 `json:"length"`
	CanSeek     bool     `json:"can_seek"`
	CanNext     bool     `json:"can_next"`
	CanPrevious bool     `json:"can_previous"`
}

// MediaState is the data of a {"t":"state","kind":"media"} message.
type MediaState struct {
	Players []Player `json:"players"`
	Active  *string  `json:"active"`
	Volume  *Volume  `json:"volume"`
}
