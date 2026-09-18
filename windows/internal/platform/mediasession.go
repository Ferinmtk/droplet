package platform

import (
	"math"
	"time"
)

// Helpers for reading Windows media sessions (see nowplaying_windows.go),
// kept free of Windows calls so they can be tested anywhere.

// playbackStatus names a GlobalSystemMediaTransportControlsSessionPlaybackStatus.
func playbackStatus(v int32) string {
	switch v {
	case 0:
		return "Closed"
	case 1:
		return "Opened"
	case 2:
		return "Changing"
	case 3:
		return "Stopped"
	case 4:
		return "Playing"
	case 5:
		return "Paused"
	}
	return "Stopped"
}

// timeline works out a session's length and position in seconds from its
// timeline properties (TimeSpans and a DateTime, in 100 ns ticks). While
// playing, the position is moved on by the time since the app last
// reported it, as apps only report it now and then.
func timeline(start, end, pos, updated int64, playing bool, now time.Time) (length, position float64) {
	const ticksPerSecond = 1e7
	length = float64(end-start) / ticksPerSecond
	position = float64(pos-start) / ticksPerSecond
	// DateTime ticks count from 1601-01-01 UTC; an unset one is 0
	const unixEpochTicks = 116444736000000000
	if playing && updated > unixEpochTicks && updated-unixEpochTicks < math.MaxInt64/100 {
		at := time.Unix(0, (updated-unixEpochTicks)*100)
		if at.Year() > 2000 && !at.After(now) {
			position += now.Sub(at).Seconds()
		}
	}
	return length, position
}
