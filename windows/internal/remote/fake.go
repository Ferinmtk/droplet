package remote

import (
	"bytes"
	"context"
	"encoding/json"
	"hash/crc32"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"sync"
)

// Fake is a backend that records what it's asked to do instead of doing
// it. The Linux build's `droplet live` uses it, so the protocol can be
// driven end to end against a real hub without touching a desktop, and the
// tests use it as a double.
//
// Every call is written to Log as one JSON line. The clipboard is the file
// ClipFile when set (write to it to "copy"), or memory otherwise.
type Fake struct {
	Log      io.Writer
	ClipFile string

	mu     sync.Mutex
	Calls  []FakeCall
	clip   string
	seq    uint32
	volume Volume
}

// FakeCall is one recorded call.
type FakeCall struct {
	Call    string   `json:"call"`
	Strokes []Stroke `json:"strokes,omitempty"`
	Text    string   `json:"text,omitempty"`
	Level   *float64 `json:"level,omitempty"`
	Muted   *bool    `json:"muted,omitempty"`
}

func (f *Fake) record(c FakeCall) {
	f.mu.Lock()
	f.Calls = append(f.Calls, c)
	f.mu.Unlock()
	if f.Log != nil {
		b, _ := json.Marshal(c)
		f.Log.Write(append(b, '\n'))
	}
}

// Taken returns and clears the recorded calls.
func (f *Fake) Taken() []FakeCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := f.Calls
	f.Calls = nil
	return out
}

// Backend exposes every capability.
func (f *Fake) Backend() Backend {
	return Backend{
		Input:      fakeInput{f},
		Volume:     fakeVolume{f},
		NowPlaying: fakePlaying{},
		Lock:       func() error { f.record(FakeCall{Call: "lock"}); return nil },
		Capture:    f.capture,
		Clipboard:  fakeClip{f},
	}
}

type fakeInput struct{ f *Fake }

func (i fakeInput) Inject(s []Stroke) error {
	i.f.record(FakeCall{Call: "inject", Strokes: s})
	return nil
}

type fakeVolume struct{ f *Fake }

func (v fakeVolume) Get() (Volume, error) {
	v.f.mu.Lock()
	defer v.f.mu.Unlock()
	return v.f.volume, nil
}

func (v fakeVolume) SetLevel(l float64) error {
	v.f.mu.Lock()
	v.f.volume.Level = l
	v.f.mu.Unlock()
	v.f.record(FakeCall{Call: "volume", Level: &l})
	return nil
}

func (v fakeVolume) SetMuted(m bool) error {
	v.f.mu.Lock()
	v.f.volume.Muted = m
	v.f.mu.Unlock()
	v.f.record(FakeCall{Call: "mute", Muted: &m})
	return nil
}

// fakePlaying reports one track, once.
type fakePlaying struct{}

func (fakePlaying) Run(ctx context.Context, update func([]Player)) {
	pos, length := 42.0, 210.0
	update([]Player{{ID: "fake", Name: "Fake player", Status: "Paused", Title: "Test track", Artist: "droplet",
		Position: &pos, Length: &length, CanNext: true, CanPrevious: true}})
	<-ctx.Done()
}

func (f *Fake) capture() ([]byte, error) {
	f.record(FakeCall{Call: "capture"})
	img := image.NewRGBA(image.Rect(0, 0, 64, 40))
	for y := 0; y < 40; y++ {
		for x := 0; x < 64; x++ {
			img.Set(x, y, color.RGBA{uint8(x * 4), uint8(y * 6), 0xc0, 0xff})
		}
	}
	var b bytes.Buffer
	err := png.Encode(&b, img)
	return b.Bytes(), err
}

type fakeClip struct{ f *Fake }

func (c fakeClip) Seq() uint32 {
	if c.f.ClipFile != "" {
		data, _ := os.ReadFile(c.f.ClipFile)
		return crc32.ChecksumIEEE(data)
	}
	c.f.mu.Lock()
	defer c.f.mu.Unlock()
	return c.f.seq
}

func (c fakeClip) ReadText() (string, bool) {
	if c.f.ClipFile != "" {
		data, err := os.ReadFile(c.f.ClipFile)
		return string(data), err == nil && len(data) > 0
	}
	c.f.mu.Lock()
	defer c.f.mu.Unlock()
	return c.f.clip, c.f.clip != ""
}

func (c fakeClip) WriteText(t string) error {
	c.f.record(FakeCall{Call: "clip", Text: t})
	if c.f.ClipFile != "" {
		return os.WriteFile(c.f.ClipFile, []byte(t), 0o600)
	}
	c.f.mu.Lock()
	c.f.clip = t
	c.f.seq++
	c.f.mu.Unlock()
	return nil
}

// Copy simulates copying text on this machine (memory clipboard only).
func (f *Fake) Copy(t string) {
	f.mu.Lock()
	f.clip = t
	f.seq++
	f.mu.Unlock()
}
