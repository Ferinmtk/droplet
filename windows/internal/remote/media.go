package remote

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
)

// VolumeControl sets the system output volume exactly (Core Audio on Windows).
type VolumeControl interface {
	Get() (Volume, error)
	SetLevel(level float64) error // 0..1
	SetMuted(muted bool) error
}

// volumeKeySteps is how many volume-key presses span 0–100 % on Windows
// (each press moves 2 %).
const volumeKeySteps = 50

// errUnsupported is returned for media actions this app can't perform.
var errUnsupported = errors.New("not supported on Windows")

// mediaAction works out one media request. It returns the strokes to inject
// (media and volume keys) and performs volume changes through vc directly.
// active is the current player's status ("Playing", "Paused", …) when
// known, so "play" and "pause" don't toggle the wrong way.
func mediaAction(action string, value json.RawMessage, vc VolumeControl, active string) ([]Stroke, error) {
	key := func(vk uint16) []Stroke { return PressKey(nil, Key{VK: vk, Extended: true}, nil) }
	switch action {
	case "play-pause":
		return key(VKMediaPlayPause), nil
	case "play":
		if active == "Playing" {
			return nil, nil
		}
		return key(VKMediaPlayPause), nil
	case "pause":
		if active != "" && active != "Playing" {
			return nil, nil
		}
		return key(VKMediaPlayPause), nil
	case "next":
		return key(VKMediaNextTrack), nil
	case "previous":
		return key(VKMediaPrevTrack), nil
	case "stop":
		return key(VKMediaStop), nil
	case "volume":
		var level float64
		if err := json.Unmarshal(value, &level); err != nil || math.IsNaN(level) {
			return nil, fmt.Errorf("volume needs a number from 0 to 1")
		}
		level = math.Max(0, math.Min(1, level))
		if vc != nil {
			err := vc.SetLevel(level)
			if err == nil {
				return nil, nil
			}
			// fall back to the keys below, which still work
			return VolumeKeys(level), fmt.Errorf("core audio: %w (used the volume keys)", err)
		}
		return VolumeKeys(level), nil
	case "mute":
		var want *bool
		if len(value) > 0 && string(value) != "null" {
			var b bool
			if err := json.Unmarshal(value, &b); err != nil {
				return nil, fmt.Errorf("mute takes true, false or nothing")
			}
			want = &b
		}
		if vc != nil {
			v, err := vc.Get()
			if err == nil {
				muted := !v.Muted
				if want != nil {
					muted = *want
				}
				if muted == v.Muted {
					return nil, nil
				}
				if err = vc.SetMuted(muted); err == nil {
					return nil, nil
				}
			}
			// the mute key can only toggle; it's the best left to do
			return key(VKVolumeMute), fmt.Errorf("core audio: %w (toggled with the mute key)", err)
		}
		return key(VKVolumeMute), nil
	case "seek":
		return nil, fmt.Errorf("seek is %w", errUnsupported)
	}
	return nil, fmt.Errorf("unknown media action %q", action)
}

// VolumeKeys sets a level with the volume keys alone: all the way down,
// then up in 2 % steps. Used only when Core Audio isn't available.
func VolumeKeys(level float64) []Stroke {
	var out []Stroke
	for i := 0; i < volumeKeySteps; i++ {
		out = PressKey(out, Key{VK: VKVolumeDown, Extended: true}, nil)
	}
	for i := 0; i < int(math.Round(level*volumeKeySteps)); i++ {
		out = PressKey(out, Key{VK: VKVolumeUp, Extended: true}, nil)
	}
	return out
}
