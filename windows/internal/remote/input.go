package remote

import (
	"math"
	"unicode/utf16"
)

// WheelDelta is one wheel notch (a line) in Windows' units.
const WheelDelta = 120

// limits on one event, so a buggy or hostile controller can't fling the
// pointer to infinity or queue a minute of scrolling
const (
	maxMove   = 10000 // pixels per event
	maxScroll = 100   // lines per event
	maxClicks = 3
)

// Button is a mouse button.
type Button uint8

const (
	ButtonLeft Button = iota + 1
	ButtonRight
	ButtonMiddle
)

func parseButton(s string) (Button, bool) {
	switch s {
	case "left", "":
		return ButtonLeft, true
	case "right":
		return ButtonRight, true
	case "middle":
		return ButtonMiddle, true
	}
	return 0, false
}

// StrokeKind says which fields of a Stroke apply.
type StrokeKind uint8

const (
	// StrokeMove moves the pointer by DX, DY whole pixels.
	StrokeMove StrokeKind = iota + 1
	// StrokeButton presses (Down) or releases a mouse button.
	StrokeButton
	// StrokeWheel turns the vertical wheel by Delta (positive = away from
	// the user = scroll up, Windows' convention).
	StrokeWheel
	// StrokeHWheel tilts the wheel by Delta (positive = scroll right).
	StrokeHWheel
	// StrokeKey presses (Down) or releases a virtual key.
	StrokeKey
	// StrokeUnicode types one UTF-16 code unit (KEYEVENTF_UNICODE).
	StrokeUnicode
)

// Stroke is one low-level input, close to one Windows INPUT structure.
type Stroke struct {
	Kind   StrokeKind
	DX, DY int
	Button Button
	Down   bool
	Delta  int
	Key    Key
	Unit   uint16
}

// Translator turns protocol input events into strokes. It carries the
// fractions of pixels and wheel notches from one event to the next and
// remembers which buttons are held, so they can be let go if the controller
// vanishes mid-drag. Not safe for concurrent use.
type Translator struct {
	fx, fy float64 // pixel fractions not yet moved
	wy, wx float64 // wheel fractions (in WheelDelta units) not yet sent
	held   map[Button]bool
}

// Translate converts one input message's events, in order.
func (t *Translator) Translate(evs []Event) []Stroke {
	var out []Stroke
	for _, e := range evs {
		out = t.event(out, e)
	}
	return out
}

func (t *Translator) event(out []Stroke, e Event) []Stroke {
	switch e.K {
	case "move":
		return t.move(out, e.DX, e.DY)
	case "button":
		b, ok := parseButton(e.B)
		if !ok {
			return out
		}
		if t.held == nil {
			t.held = map[Button]bool{}
		}
		t.held[b] = e.Down
		return append(out, Stroke{Kind: StrokeButton, Button: b, Down: e.Down})
	case "click":
		b, ok := parseButton(e.B)
		if !ok {
			return out
		}
		n := e.N
		if n < 1 {
			n = 1
		}
		if n > maxClicks {
			n = maxClicks
		}
		if t.held[b] {
			// a click while the button is held down: let go first, so the
			// click is a real press and release
			out = append(out, Stroke{Kind: StrokeButton, Button: b})
		}
		delete(t.held, b)
		for i := 0; i < n; i++ {
			out = append(out, Stroke{Kind: StrokeButton, Button: b, Down: true}, Stroke{Kind: StrokeButton, Button: b})
		}
		return out
	case "scroll":
		return t.scroll(out, e.DX, e.DY)
	case "text":
		return TypeText(out, e.S)
	case "key":
		k, ok := LookupKey(e.Key)
		if !ok {
			// a character without a VK name (like "é" or "/"): type it,
			// unless it's meant as a shortcut we can't express
			if r := []rune(e.Key); len(r) == 1 && len(LookupMods(e.Mods)) == 0 {
				return TypeText(out, e.Key)
			}
			return out
		}
		return PressKey(out, k, LookupMods(e.Mods))
	}
	return out // unknown event kinds are ignored, per the protocol
}

func clamp(v, limit float64) float64 {
	if math.IsNaN(v) {
		return 0
	}
	return math.Max(-limit, math.Min(limit, v))
}

// whole is the integer part of v toward zero, forgiving the float error of
// summed fractions (ten moves of 0.3 must make 3 pixels, not 2.9999…).
func whole(v float64) float64 {
	return math.Trunc(v + math.Copysign(1e-9, v))
}

// move adds whole pixels and keeps the fraction; consecutive moves are
// merged into one stroke.
func (t *Translator) move(out []Stroke, dx, dy float64) []Stroke {
	t.fx += clamp(dx, maxMove)
	t.fy += clamp(dy, maxMove)
	ix, iy := whole(t.fx), whole(t.fy)
	t.fx -= ix
	t.fy -= iy
	if ix == 0 && iy == 0 {
		return out
	}
	if n := len(out); n > 0 && out[n-1].Kind == StrokeMove {
		out[n-1].DX += int(ix)
		out[n-1].DY += int(iy)
		return out
	}
	return append(out, Stroke{Kind: StrokeMove, DX: int(ix), DY: int(iy)})
}

// scroll converts lines to wheel units. The protocol's positive dy scrolls
// down, which on Windows is a negative wheel delta; positive dx scrolls
// right, which is a positive horizontal delta.
func (t *Translator) scroll(out []Stroke, dx, dy float64) []Stroke {
	t.wy += -clamp(dy, maxScroll) * WheelDelta
	t.wx += clamp(dx, maxScroll) * WheelDelta
	if v := whole(t.wy); v != 0 {
		t.wy -= v
		out = append(out, Stroke{Kind: StrokeWheel, Delta: int(v)})
	}
	if h := whole(t.wx); h != 0 {
		t.wx -= h
		out = append(out, Stroke{Kind: StrokeHWheel, Delta: int(h)})
	}
	return out
}

// Release lets go of every button still held (after a disconnect, or when
// remote control is paused) and forgets carried fractions.
func (t *Translator) Release() []Stroke {
	var out []Stroke
	for _, b := range []Button{ButtonLeft, ButtonRight, ButtonMiddle} {
		if t.held[b] {
			out = append(out, Stroke{Kind: StrokeButton, Button: b})
		}
	}
	*t = Translator{}
	return out
}

// PressKey presses k with the modifiers held, then releases in reverse.
func PressKey(out []Stroke, k Key, mods []Key) []Stroke {
	var hold []Key
	for _, m := range mods {
		if m.VK != k.VK { // "Shift" with mods ["shift"]: press it once
			hold = append(hold, m)
		}
	}
	for _, m := range hold {
		out = append(out, Stroke{Kind: StrokeKey, Key: m, Down: true})
	}
	out = append(out, Stroke{Kind: StrokeKey, Key: k, Down: true}, Stroke{Kind: StrokeKey, Key: k})
	for i := len(hold) - 1; i >= 0; i-- {
		out = append(out, Stroke{Kind: StrokeKey, Key: hold[i]})
	}
	return out
}

// TypeText types s as Unicode keystrokes: each UTF-16 code unit (both
// halves of a surrogate pair for emoji) pressed and released. Line breaks
// and tabs become real Enter and Tab presses, which apps treat differently
// from the characters; other control characters are dropped.
func TypeText(out []Stroke, s string) []Stroke {
	prevCR := false
	for _, r := range s {
		switch {
		case r == '\n' && prevCR:
			// the \n of a \r\n pair: already pressed Enter for the \r
		case r == '\r' || r == '\n':
			out = PressKey(out, Key{VK: VKReturn}, nil)
		case r == '\t':
			out = PressKey(out, Key{VK: VKTab}, nil)
		case r < 0x20 || r == 0x7f || (r >= 0x80 && r < 0xa0):
			// control characters have no keystroke
		default:
			for _, u := range utf16.Encode([]rune{r}) {
				out = append(out, Stroke{Kind: StrokeUnicode, Unit: u, Down: true}, Stroke{Kind: StrokeUnicode, Unit: u})
			}
		}
		prevCR = r == '\r'
	}
	return out
}
