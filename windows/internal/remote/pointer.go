package remote

import "time"

// Point is a screen position in physical pixels.
type Point struct{ X, Y int }

// Rect is the virtual screen: all monitors' bounding box.
type Rect struct{ Left, Top, Width, Height int }

// Pointer turns relative moves into absolute positions.
//
// Why absolute: a relative SendInput move goes through Windows' pointer
// speed and "Enhance pointer precision" curve. The controller has already
// applied its own acceleration, so a relative move would be accelerated
// twice and feel different on every PC. An absolute move lands exactly
// where it's sent.
//
// Each batch starts from the real cursor position (so the physical mouse
// and the edges of the screen are respected). Right after an injection the
// cursor may not have caught up yet; if it still reads exactly where the
// previous batch started, the previous target is used instead, so quickly
// arriving batches don't lose movement.
type Pointer struct {
	lastBase, lastTarget Point
	lastAt               time.Time
	have                 bool
}

// staleWindow is how long after an injection a cursor reading that hasn't
// moved is assumed to be stale.
const staleWindow = 50 * time.Millisecond

// Base is where the next batch of moves starts, given the cursor reading.
func (p *Pointer) Base(cursor Point, now time.Time) Point {
	if p.have && now.Sub(p.lastAt) < staleWindow && cursor == p.lastBase && cursor != p.lastTarget {
		return p.lastTarget
	}
	return cursor
}

// Moved records a batch that started at base and ended at target.
func (p *Pointer) Moved(cursor, target Point, now time.Time) {
	p.lastBase, p.lastTarget, p.lastAt, p.have = cursor, target, now, true
}

// Step moves from pos by (dx, dy), kept inside the virtual screen.
func Step(pos Point, dx, dy int, screen Rect) Point {
	x, y := pos.X+dx, pos.Y+dy
	if screen.Width <= 0 || screen.Height <= 0 {
		return Point{x, y}
	}
	x = min(max(x, screen.Left), screen.Left+screen.Width-1)
	y = min(max(y, screen.Top), screen.Top+screen.Height-1)
	return Point{x, y}
}

// Normalize converts a pixel position to SendInput's absolute coordinates
// (0–65535 across the virtual desktop, MOUSEEVENTF_VIRTUALDESK), aiming at
// the middle of the pixel so rounding can't land on its neighbour.
func Normalize(p Point, screen Rect) (int32, int32) {
	norm := func(v, origin, size int) int32 {
		if size <= 0 {
			return 0
		}
		n := ((v-origin)*65536 + 32768) / size
		return int32(min(max(n, 0), 65535))
	}
	return norm(p.X, screen.Left, screen.Width), norm(p.Y, screen.Top, screen.Height)
}
