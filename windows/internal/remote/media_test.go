package remote

import (
	"encoding/json"
	"errors"
	"testing"
	"time"
)

type fakeVC struct {
	v           Volume
	failGet     bool
	failSet     bool
	sets, mutes int
}

func (f *fakeVC) Get() (Volume, error) {
	if f.failGet {
		return Volume{}, errors.New("no device")
	}
	return f.v, nil
}
func (f *fakeVC) SetLevel(l float64) error {
	if f.failSet {
		return errors.New("no device")
	}
	f.sets++
	f.v.Level = l
	return nil
}
func (f *fakeVC) SetMuted(m bool) error {
	if f.failSet {
		return errors.New("no device")
	}
	f.mutes++
	f.v.Muted = m
	return nil
}

func onlyKey(t *testing.T, ss []Stroke, vk uint16) {
	t.Helper()
	if len(ss) != 2 || ss[0].Key.VK != vk || !ss[0].Down || !ss[0].Key.Extended || ss[1].Down {
		t.Fatalf("want a press of %#x, got %+v", vk, ss)
	}
}

func TestMediaKeys(t *testing.T) {
	for action, vk := range map[string]uint16{"play-pause": VKMediaPlayPause, "next": VKMediaNextTrack,
		"previous": VKMediaPrevTrack, "stop": VKMediaStop} {
		ss, err := mediaAction(action, nil, nil, "")
		if err != nil {
			t.Fatal(err)
		}
		onlyKey(t, ss, vk)
	}
	// play and pause only toggle when they'd change something
	if ss, _ := mediaAction("play", nil, nil, "Playing"); len(ss) != 0 {
		t.Fatal("play while playing should do nothing")
	}
	if ss, _ := mediaAction("pause", nil, nil, "Paused"); len(ss) != 0 {
		t.Fatal("pause while paused should do nothing")
	}
	ss, _ := mediaAction("pause", nil, nil, "Playing")
	onlyKey(t, ss, VKMediaPlayPause)
	ss, _ = mediaAction("play", nil, nil, "") // unknown: best guess
	onlyKey(t, ss, VKMediaPlayPause)
	if _, err := mediaAction("seek", json.RawMessage("12"), nil, ""); err == nil {
		t.Fatal("seek should say it's unsupported")
	}
	if _, err := mediaAction("rewind-time", nil, nil, ""); err == nil {
		t.Fatal("unknown action should error")
	}
}

func TestMediaVolume(t *testing.T) {
	vc := &fakeVC{}
	ss, err := mediaAction("volume", json.RawMessage("0.37"), vc, "")
	if err != nil || len(ss) != 0 || vc.v.Level != 0.37 {
		t.Fatalf("exact volume: %v %+v %+v", err, ss, vc.v)
	}
	mediaAction("volume", json.RawMessage("7"), vc, "")
	if vc.v.Level != 1 {
		t.Fatalf("clamped to %v", vc.v.Level)
	}
	if _, err := mediaAction("volume", json.RawMessage(`"loud"`), vc, ""); err == nil {
		t.Fatal("bad value should error")
	}
	// Core Audio broken: the volume keys, all the way down then up to 40 %
	vc.failSet = true
	ss, err = mediaAction("volume", json.RawMessage("0.4"), vc, "")
	if err == nil {
		t.Fatal("the fallback should be reported")
	}
	downs, ups := 0, 0
	for _, s := range ss {
		if s.Down && s.Key.VK == VKVolumeDown {
			downs++
		}
		if s.Down && s.Key.VK == VKVolumeUp {
			ups++
		}
	}
	if downs != 50 || ups != 20 {
		t.Fatalf("fallback keys: %d down, %d up", downs, ups)
	}
}

func TestMediaMute(t *testing.T) {
	vc := &fakeVC{}
	mediaAction("mute", nil, vc, "") // toggle
	if !vc.v.Muted {
		t.Fatal("toggle should mute")
	}
	mediaAction("mute", json.RawMessage("true"), vc, "") // already muted
	if vc.mutes != 1 {
		t.Fatal("setting the same state shouldn't call Core Audio")
	}
	mediaAction("mute", json.RawMessage("false"), vc, "")
	if vc.v.Muted {
		t.Fatal("explicit false should unmute")
	}
	vc.failGet = true
	ss, err := mediaAction("mute", nil, vc, "")
	if err == nil {
		t.Fatal("fallback should be reported")
	}
	onlyKey(t, ss, VKVolumeMute)
	ss, _ = mediaAction("mute", nil, nil, "")
	onlyKey(t, ss, VKVolumeMute)
}

func TestMediaKeyIgnoresPlayingPosition(t *testing.T) {
	p1, p2 := 10.0, 11.0
	a := MediaState{Players: []Player{{ID: "x", Status: "Playing", Position: &p1}}}
	b := MediaState{Players: []Player{{ID: "x", Status: "Playing", Position: &p2}}}
	if mediaKey(a) != mediaKey(b) {
		t.Fatal("position of a playing track shouldn't count as a change")
	}
	a.Players[0].Status, b.Players[0].Status = "Paused", "Paused"
	if mediaKey(a) == mediaKey(b) {
		t.Fatal("a seek while paused is a change")
	}
	if a.Players[0].Position != &p1 {
		t.Fatal("mediaKey mustn't modify its argument")
	}
}

func TestClipSync(t *testing.T) {
	var c clipSync
	now := time.Now()
	text, seq := "", uint32(1)
	read := func() (string, bool) { return text, text != "" }
	step := func(d time.Duration) (string, bool) { now = now.Add(d); return c.observe(seq, read, now) }

	text = "already there"
	if _, ok := step(0); ok {
		t.Fatal("the clipboard at connect isn't sent")
	}
	text, seq = "hello", 2
	if _, ok := step(100 * time.Millisecond); ok {
		t.Fatal("sent before the change settled")
	}
	if got, ok := step(500 * time.Millisecond); !ok || got != "hello" {
		t.Fatalf("got %q %v", got, ok)
	}
	// quick successive copies: only the last one, and not within a second
	text, seq = "one", 3
	step(100 * time.Millisecond)
	text, seq = "two", 4
	if _, ok := step(450 * time.Millisecond); ok {
		t.Fatal("less than a second after the last send")
	}
	if got, ok := step(500 * time.Millisecond); !ok || got != "two" {
		t.Fatalf("got %q %v", got, ok)
	}
	// text from another device is written, and its echo isn't sent back
	c.applying("from phone")
	text, seq = "from phone", 5
	for i := 0; i < 4; i++ {
		if got, ok := step(time.Second); ok {
			t.Fatalf("echo sent: %q", got)
		}
	}
	// copying something already sent, after the phone changed it, goes out again
	text, seq = "two", 6
	if got, ok := step(time.Second); ok || got != "" {
		t.Fatal("must settle first")
	}
	if got, ok := step(time.Second); !ok || got != "two" {
		t.Fatalf("got %q %v", got, ok)
	}
	// private (password manager) or huge copies are skipped
	text, seq = "", 7
	if _, ok := step(2 * time.Second); ok {
		t.Fatal("no text")
	}
	text, seq = string(make([]byte, maxClip+1)), 8
	step(time.Second)
	if _, ok := step(time.Second); ok {
		t.Fatal("over 256 KB")
	}
}
