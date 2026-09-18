package remote

import "strings"

// Windows virtual-key codes. They're plain numbers, so the mapping from the
// protocol's key names lives here, where it can be tested anywhere.
const (
	VKBack     = 0x08
	VKTab      = 0x09
	VKReturn   = 0x0D
	VKEscape   = 0x1B
	VKSpace    = 0x20
	VKPrior    = 0x21 // Page Up
	VKNext     = 0x22 // Page Down
	VKEnd      = 0x23
	VKHome     = 0x24
	VKLeft     = 0x25
	VKUp       = 0x26
	VKRight    = 0x27
	VKDown     = 0x28
	VKSnapshot = 0x2C // Print Screen
	VKInsert   = 0x2D
	VKDelete   = 0x2E
	VKLWin     = 0x5B
	VKApps     = 0x5D // context menu key
	VKF1       = 0x70
	VKLShift   = 0xA0
	VKLControl = 0xA2
	VKLMenu    = 0xA4 // left Alt

	VKVolumeMute     = 0xAD
	VKVolumeDown     = 0xAE
	VKVolumeUp       = 0xAF
	VKMediaNextTrack = 0xB0
	VKMediaPrevTrack = 0xB1
	VKMediaStop      = 0xB2
	VKMediaPlayPause = 0xB3
)

// Key is a virtual key and whether it's an "extended" key. Extended keys
// (the arrows and the block above them, the media keys, the Windows key)
// need KEYEVENTF_EXTENDEDKEY, or Windows reads e.g. ArrowLeft as numpad 4.
type Key struct {
	VK       uint16
	Extended bool
}

var namedKeys = map[string]Key{
	"Enter":     {VKReturn, false},
	"Backspace": {VKBack, false},
	"Tab":       {VKTab, false},
	"Escape":    {VKEscape, false},
	"Esc":       {VKEscape, false},
	"Space":     {VKSpace, false},
	" ":         {VKSpace, false}, // what KeyboardEvent.key says for the space bar
	"Spacebar":  {VKSpace, false},
	"Delete":    {VKDelete, true},
	"Del":       {VKDelete, true},
	"Insert":    {VKInsert, true},
	"Home":      {VKHome, true},
	"End":       {VKEnd, true},
	"PageUp":    {VKPrior, true},
	"PageDown":  {VKNext, true},
	"ArrowUp":   {VKUp, true},
	"ArrowDown": {VKDown, true},
	"ArrowLeft": {VKLeft, true},
	"Up":        {VKUp, true},
	"Down":      {VKDown, true},
	"Left":      {VKLeft, true},
	"Right":     {VKRight, true},

	"ArrowRight":  {VKRight, true},
	"PrintScreen": {VKSnapshot, true},
	"ContextMenu": {VKApps, true},

	"MediaPlayPause":     {VKMediaPlayPause, true},
	"MediaNext":          {VKMediaNextTrack, true},
	"MediaTrackNext":     {VKMediaNextTrack, true},
	"MediaPrevious":      {VKMediaPrevTrack, true},
	"MediaTrackPrevious": {VKMediaPrevTrack, true},
	"MediaStop":          {VKMediaStop, true},
	"AudioVolumeUp":      {VKVolumeUp, true},
	"AudioVolumeDown":    {VKVolumeDown, true},
	"AudioVolumeMute":    {VKVolumeMute, true},

	// the modifiers themselves, e.g. a lone "Meta" opens the Start menu
	"Control": {VKLControl, false},
	"Shift":   {VKLShift, false},
	"Alt":     {VKLMenu, false},
	"Meta":    {VKLWin, true},
	"OS":      {VKLWin, true},
}

// LookupKey maps a protocol key name (the web's KeyboardEvent.key) to a key.
func LookupKey(name string) (Key, bool) {
	if k, ok := namedKeys[name]; ok {
		return k, true
	}
	r := []rune(name)
	if len(r) == 1 {
		switch c := r[0]; {
		case c >= 'a' && c <= 'z':
			return Key{uint16(c - 'a' + 'A'), false}, true
		case c >= 'A' && c <= 'Z':
			return Key{uint16(c), false}, true
		case c >= '0' && c <= '9':
			return Key{uint16(c), false}, true
		}
	}
	// F1–F24
	if len(name) >= 2 && len(name) <= 3 && name[0] == 'F' {
		n := 0
		for _, c := range name[1:] {
			if c < '0' || c > '9' {
				return Key{}, false
			}
			n = n*10 + int(c-'0')
		}
		if n >= 1 && n <= 24 {
			return Key{uint16(VKF1 + n - 1), false}, true
		}
	}
	return Key{}, false
}

// modifier names, in the order they're pressed
var modOrder = []string{"ctrl", "alt", "shift", "meta"}

var modKeys = map[string]Key{
	"ctrl":  {VKLControl, false},
	"alt":   {VKLMenu, false},
	"shift": {VKLShift, false},
	"meta":  {VKLWin, true},
}

// LookupMods turns a mods list into the keys to hold, in a fixed order,
// without duplicates. Unknown names are skipped. Aliases a web page might
// send ("control", "cmd", "win", "super") are accepted too.
func LookupMods(mods []string) []Key {
	want := map[string]bool{}
	for _, m := range mods {
		switch strings.ToLower(strings.TrimSpace(m)) {
		case "ctrl", "control":
			want["ctrl"] = true
		case "alt", "option":
			want["alt"] = true
		case "shift":
			want["shift"] = true
		case "meta", "win", "super", "cmd", "os":
			want["meta"] = true
		}
	}
	var out []Key
	for _, m := range modOrder {
		if want[m] {
			out = append(out, modKeys[m])
		}
	}
	return out
}
