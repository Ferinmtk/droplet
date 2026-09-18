package remote

import (
	"sync"
	"time"
)

// Clipboard is the system clipboard, text only.
type Clipboard interface {
	// Seq changes whenever the clipboard's contents change
	// (GetClipboardSequenceNumber on Windows).
	Seq() uint32
	// ReadText returns the clipboard's text. ok is false when there's no
	// text, or when the app that copied it asked for it to stay private
	// (password managers do).
	ReadText() (text string, ok bool)
	WriteText(text string) error
}

const (
	clipPoll     = 500 * time.Millisecond
	clipSettle   = 400 * time.Millisecond // a change must hold this long before it's sent
	clipMinSpace = time.Second            // at most one clip a second
)

// clipSync decides what clipboard text to send, and keeps the text we were
// just given from being sent back.
type clipSync struct {
	mu        sync.Mutex
	seq       uint32
	started   bool
	pending   string
	pendingAt time.Time
	// the text the other devices have too: what we last sent or were sent
	synced string
	sentAt time.Time // when the last clip went out
}

// observe is called on every poll with the current sequence number and a
// way to read the text. It returns text to send now, if any.
func (c *clipSync) observe(seq uint32, read func() (string, bool), now time.Time) (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.started {
		// what's on the clipboard when the connection starts isn't news
		c.started, c.seq = true, seq
		return "", false
	}
	if seq != c.seq {
		c.seq = seq
		text, ok := read()
		switch {
		case !ok || text == "" || len(text) > maxClip:
			c.pending = "" // a newer copy (an image, a secret) replaces unsent text
		case text == c.synced:
			c.pending = "" // our own write coming back, or nothing new
		default:
			c.pending, c.pendingAt = text, now
		}
	}
	if c.pending == "" || now.Sub(c.pendingAt) < clipSettle || now.Sub(c.sentAt) < clipMinSpace {
		return "", false
	}
	out := c.pending
	c.pending, c.synced, c.sentAt = "", out, now
	return out, true
}

// applying records text from another device, just before it's written.
func (c *clipSync) applying(text string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.synced = text
	c.pending = ""
}

// reset forgets everything, for a new connection.
func (c *clipSync) reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.seq, c.started, c.pending, c.pendingAt, c.synced, c.sentAt = 0, false, "", time.Time{}, "", time.Time{}
}
