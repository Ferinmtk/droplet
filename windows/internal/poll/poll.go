// Package poll decides what's new between two looks at the hub, so each
// file and message is announced exactly once, even across restarts.
package poll

import (
	"sort"
	"strconv"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/hub"
)

// settleAfter is how old an inbox file without sender info must be before
// it counts as complete. The hub labels files with their sender only after
// the whole upload is saved, so a labelled file is always complete; this is
// the fallback for one whose label went missing.
const settleAfter = 60 * time.Second

// InboxKey identifies one inbox item: a name reused by a later upload is a new item.
func InboxKey(f hub.File) string {
	return f.Name + "|" + strconv.FormatInt(f.Mtime, 10)
}

// Complete reports whether an inbox file has finished uploading.
func Complete(f hub.File, now time.Time) bool {
	return f.From != "" || now.Sub(time.Unix(f.Mtime, 0)) > settleAfter
}

// Inbox compares the inbox with the keys already announced. It returns the
// complete files not yet announced (oldest first) and the new seen list,
// which drops items no longer in the inbox so it can't grow forever.
func Inbox(current []hub.File, seen []string, now time.Time) (fresh []hub.File, nextSeen []string) {
	was := make(map[string]bool, len(seen))
	for _, k := range seen {
		was[k] = true
	}
	for _, f := range current {
		if !Complete(f, now) {
			continue // announced once it's complete
		}
		k := InboxKey(f)
		nextSeen = append(nextSeen, k)
		if !was[k] {
			fresh = append(fresh, f)
		}
	}
	sort.SliceStable(fresh, func(i, j int) bool { return fresh[i].Mtime < fresh[j].Mtime })
	return fresh, nextSeen
}

// Messages picks the messages from one sender worth announcing: at most the
// last `unread` of them (the hub's count), and only those newer than the
// last one shown. It returns them oldest first with the new high-water mark.
func Messages(thread []hub.Message, from string, unread int, seenTS float64) (fresh []hub.Message, nextSeen float64) {
	nextSeen = seenTS
	var theirs []hub.Message
	for _, m := range thread {
		if m.From == from {
			theirs = append(theirs, m)
			if m.TS > nextSeen {
				nextSeen = m.TS
			}
		}
	}
	sort.SliceStable(theirs, func(i, j int) bool { return theirs[i].TS < theirs[j].TS })
	if unread < len(theirs) {
		theirs = theirs[len(theirs)-unread:]
	}
	for _, m := range theirs {
		if m.TS > seenTS {
			fresh = append(fresh, m)
		}
	}
	return fresh, nextSeen
}

// SameDevices reports whether two device lists name the same destinations,
// which is when Explorer's Send To shortcuts and the tray menus need no rebuild.
func SameDevices(a, b []hub.Device) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].ID != b[i].ID || a[i].Name != b[i].Name || a[i].Self != b[i].Self {
			return false
		}
	}
	return true
}
