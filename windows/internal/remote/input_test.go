package remote

import (
	"reflect"
	"testing"
	"time"
	"unicode/utf16"
)

func TestLookupKey(t *testing.T) {
	cases := map[string]Key{
		"Enter": {VKReturn, false}, "Backspace": {VKBack, false}, "Tab": {VKTab, false},
		"Escape": {VKEscape, false}, "Space": {VKSpace, false}, " ": {VKSpace, false},
		"Delete": {VKDelete, true}, "Insert": {VKInsert, true}, "Home": {VKHome, true}, "End": {VKEnd, true},
		"PageUp": {VKPrior, true}, "PageDown": {VKNext, true},
		"ArrowUp": {VKUp, true}, "ArrowDown": {VKDown, true}, "ArrowLeft": {VKLeft, true}, "ArrowRight": {VKRight, true},
		"F1": {0x70, false}, "F5": {0x74, false}, "F12": {0x7B, false}, "F24": {0x87, false},
		"MediaPlayPause": {0xB3, true}, "MediaNext": {0xB0, true}, "MediaPrevious": {0xB1, true}, "MediaStop": {0xB2, true},
		"AudioVolumeUp": {0xAF, true}, "AudioVolumeDown": {0xAE, true}, "AudioVolumeMute": {0xAD, true},
		"PrintScreen": {0x2C, true}, "ContextMenu": {0x5D, true},
		"a": {'A', false}, "z": {'Z', false}, "B": {'B', false}, "0": {'0', false}, "9": {'9', false},
		"Meta": {VKLWin, true},
	}
	for name, want := range cases {
		got, ok := LookupKey(name)
		if !ok || got != want {
			t.Errorf("LookupKey(%q) = %+v, %v; want %+v", name, got, ok, want)
		}
	}
	for _, bad := range []string{"", "F0", "F25", "F1x", "Fn", "é", "/", "Unidentified", "ab"} {
		if k, ok := LookupKey(bad); ok {
			t.Errorf("LookupKey(%q) = %+v, want no key", bad, k)
		}
	}
}

func TestLookupModsOrderAndDedupe(t *testing.T) {
	got := LookupMods([]string{"shift", "meta", "ctrl", "Control", "bogus", "alt"})
	want := []Key{{VKLControl, false}, {VKLMenu, false}, {VKLShift, false}, {VKLWin, true}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v", got)
	}
	if LookupMods(nil) != nil {
		t.Fatal("no mods should be nil")
	}
}

func key(vk uint16, ext, down bool) Stroke {
	return Stroke{Kind: StrokeKey, Key: Key{vk, ext}, Down: down}
}

func TestKeyWithModifiers(t *testing.T) {
	var tr Translator
	got := tr.Translate([]Event{{K: "key", Key: "ArrowLeft", Mods: []string{"shift", "ctrl"}}})
	want := []Stroke{
		key(VKLControl, false, true), key(VKLShift, false, true),
		key(VKLeft, true, true), key(VKLeft, true, false),
		key(VKLShift, false, false), key(VKLControl, false, false),
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v\nwant %+v", got, want)
	}
	// the modifier as the key itself isn't pressed twice
	got = tr.Translate([]Event{{K: "key", Key: "Shift", Mods: []string{"shift"}}})
	if want := []Stroke{key(VKLShift, false, true), key(VKLShift, false, false)}; !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v", got)
	}
	// presentation remote: plain keys
	got = tr.Translate([]Event{{K: "key", Key: "b"}, {K: "key", Key: "F5"}})
	if want := []Stroke{key('B', false, true), key('B', false, false), key(0x74, false, true), key(0x74, false, false)}; !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v", got)
	}
}

func TestUnknownKeys(t *testing.T) {
	var tr Translator
	// an unnamed character is typed when there are no modifiers…
	got := tr.Translate([]Event{{K: "key", Key: "é"}})
	if len(got) != 2 || got[0].Kind != StrokeUnicode || got[0].Unit != 'é' {
		t.Fatalf("got %+v", got)
	}
	// …and ignored as a shortcut, as are unknown names and event kinds
	if got := tr.Translate([]Event{{K: "key", Key: "/", Mods: []string{"ctrl"}}, {K: "key", Key: "Hyper"}, {K: "warp"}}); len(got) != 0 {
		t.Fatalf("got %+v", got)
	}
}

func unicodeUnits(ss []Stroke) (downs []uint16) {
	for _, s := range ss {
		if s.Kind == StrokeUnicode && s.Down {
			downs = append(downs, s.Unit)
		}
	}
	return
}

func TestTypeTextUnicodeAndSurrogates(t *testing.T) {
	var tr Translator
	s := "héllo 👋"
	got := tr.Translate([]Event{{K: "text", S: s}})
	want := utf16.Encode([]rune(s))
	if u := unicodeUnits(got); !reflect.DeepEqual(u, want) {
		t.Fatalf("units %x, want %x", u, want)
	}
	// 👋 is a surrogate pair: both halves, each pressed then released
	n := len(got)
	tail := got[n-4:]
	if tail[0].Unit != 0xD83D || !tail[0].Down || tail[1].Unit != 0xD83D || tail[1].Down ||
		tail[2].Unit != 0xDC4B || !tail[2].Down || tail[3].Unit != 0xDC4B || tail[3].Down {
		t.Fatalf("surrogate strokes %+v", tail)
	}
	if len(got) != 2*len(want) {
		t.Fatalf("every unit needs a press and a release: %d strokes for %d units", len(got), len(want))
	}
}

func TestTypeTextLineBreaksAndControls(t *testing.T) {
	got := TypeText(nil, "a\r\nb\nc\td\x07")
	var seq []string
	for _, s := range got {
		if !s.Down {
			continue
		}
		switch s.Kind {
		case StrokeUnicode:
			seq = append(seq, string(rune(s.Unit)))
		case StrokeKey:
			seq = append(seq, map[uint16]string{VKReturn: "⏎", VKTab: "⇥"}[s.Key.VK])
		}
	}
	if want := []string{"a", "⏎", "b", "⏎", "c", "⇥", "d"}; !reflect.DeepEqual(seq, want) {
		t.Fatalf("got %v, want %v", seq, want)
	}
}

func TestMoveCarriesFractions(t *testing.T) {
	var tr Translator
	var x, y int
	for i := 0; i < 10; i++ {
		for _, s := range tr.Translate([]Event{{K: "move", DX: 0.3, DY: -0.25}}) {
			x += s.DX
			y += s.DY
		}
	}
	if x != 3 || y != -2 {
		t.Fatalf("10 × (0.3, -0.25) moved (%d, %d), want (3, -2)", x, y)
	}
	// consecutive moves in one message become one stroke
	got := tr.Translate([]Event{{K: "move", DX: 4.5, DY: -2}, {K: "move", DX: 0.5, DY: 1}})
	// carried so far: x 0, y -0.5; so y: -0.5-2 → -2 (carry -0.5), then -0.5+1 → 0
	if len(got) != 1 || got[0].Kind != StrokeMove || got[0].DX != 5 || got[0].DY != -2 {
		t.Fatalf("got %+v", got)
	}
	// absurd values are capped
	got = tr.Translate([]Event{{K: "move", DX: 1e12}})
	if got[0].DX != maxMove {
		t.Fatalf("got %+v", got)
	}
}

func TestScrollDirectionsAndFractions(t *testing.T) {
	var tr Translator
	// positive dy scrolls down = negative wheel on Windows
	got := tr.Translate([]Event{{K: "scroll", DY: 1}})
	if want := []Stroke{{Kind: StrokeWheel, Delta: -120}}; !reflect.DeepEqual(got, want) {
		t.Fatalf("down: %+v", got)
	}
	got = tr.Translate([]Event{{K: "scroll", DY: -2}})
	if want := []Stroke{{Kind: StrokeWheel, Delta: 240}}; !reflect.DeepEqual(got, want) {
		t.Fatalf("up: %+v", got)
	}
	// positive dx scrolls right = positive horizontal wheel
	got = tr.Translate([]Event{{K: "scroll", DX: 0.5}})
	if want := []Stroke{{Kind: StrokeHWheel, Delta: 60}}; !reflect.DeepEqual(got, want) {
		t.Fatalf("right: %+v", got)
	}
	// fractions of a wheel unit are carried
	total := 0
	for i := 0; i < 3; i++ {
		for _, s := range tr.Translate([]Event{{K: "scroll", DY: 0.004}}) { // 0.48 units each
			total += s.Delta
		}
	}
	if total != -1 {
		t.Fatalf("3 × 0.48 units scrolled %d, want -1 (and 0.44 carried)", total)
	}
}

func TestClicksAndHeldButtons(t *testing.T) {
	var tr Translator
	down := func(b Button) Stroke { return Stroke{Kind: StrokeButton, Button: b, Down: true} }
	up := func(b Button) Stroke { return Stroke{Kind: StrokeButton, Button: b} }
	got := tr.Translate([]Event{{K: "click", B: "left", N: 2}, {K: "click", B: "right"}})
	if want := []Stroke{down(ButtonLeft), up(ButtonLeft), down(ButtonLeft), up(ButtonLeft), down(ButtonRight), up(ButtonRight)}; !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v", got)
	}
	// a drag left hanging is released when the controller goes away
	tr.Translate([]Event{{K: "button", B: "left", Down: true}, {K: "button", B: "middle", Down: true}, {K: "button", B: "middle"}})
	if got := tr.Release(); !reflect.DeepEqual(got, []Stroke{up(ButtonLeft)}) {
		t.Fatalf("release: %+v", got)
	}
	if got := tr.Release(); len(got) != 0 {
		t.Fatalf("second release: %+v", got)
	}
	if got := tr.Translate([]Event{{K: "click", B: "sideways"}}); len(got) != 0 {
		t.Fatalf("unknown button: %+v", got)
	}
}

func TestPointerAbsolute(t *testing.T) {
	screen := Rect{Left: -1920, Top: 0, Width: 1920 + 2560, Height: 1440}
	if p := Step(Point{0, 0}, -5000, 5000, screen); p != (Point{-1920, 1439}) {
		t.Fatalf("clamped to %+v", p)
	}
	if p := Step(Point{10, 10}, 3, -4, screen); p != (Point{13, 6}) {
		t.Fatalf("step %+v", p)
	}
	// normalised coordinates map back to the same pixel under Windows' rule
	for _, p := range []Point{{-1920, 0}, {0, 0}, {2559, 1439}, {123, 456}} {
		nx, ny := Normalize(p, screen)
		bx := int(nx)*screen.Width/65536 + screen.Left
		by := int(ny)*screen.Height/65536 + screen.Top
		if bx != p.X || by != p.Y {
			t.Errorf("%+v → (%d,%d) → (%d,%d)", p, nx, ny, bx, by)
		}
	}
	var ptr Pointer
	now := time.Now()
	base := ptr.Base(Point{100, 100}, now)
	ptr.Moved(Point{100, 100}, Point{110, 100}, now)
	// the cursor hasn't caught up yet: carry on from the target
	if b := ptr.Base(Point{100, 100}, now.Add(5*time.Millisecond)); b != (Point{110, 100}) || base != (Point{100, 100}) {
		t.Fatalf("stale reading: %+v", b)
	}
	// it has (or the person moved the mouse): trust the cursor
	if b := ptr.Base(Point{300, 40}, now.Add(5*time.Millisecond)); b != (Point{300, 40}) {
		t.Fatalf("moved: %+v", b)
	}
	// long after, a still cursor is just still
	if b := ptr.Base(Point{100, 100}, now.Add(time.Second)); b != (Point{100, 100}) {
		t.Fatalf("late: %+v", b)
	}
}
