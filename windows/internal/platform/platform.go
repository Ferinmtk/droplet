// Package platform is everything that talks to the desktop: notifications,
// sound, the clipboard, Explorer integration, autostart. The Windows
// implementation is the real one; other systems get small stand-ins so the
// rest of the app can be built and tested anywhere.
package platform

import (
	"net/url"
	"strings"
)

// AppID is the AppUserModelID notifications are shown under.
const AppID = "Ferinmtk.droplet"

// Scheme is the URL scheme notification buttons use to call back into droplet.exe.
const Scheme = "droplet"

// ActionKind is what clicking a notification (or one of its buttons) does.
type ActionKind string

const (
	ActOpenURL    ActionKind = "url"    // open Arg in the browser
	ActOpenFile   ActionKind = "open"   // open the file Arg with its default app
	ActShowFile   ActionKind = "show"   // show the file Arg selected in Explorer
	ActOpenFolder ActionKind = "folder" // open the folder Arg
	ActStopRing   ActionKind = "stop-ring"
)

// Action is a notification click target.
type Action struct {
	Label string
	Kind  ActionKind
	Arg   string
}

// Notification is one toast.
type Notification struct {
	Title string
	Body  string
	// Tag groups updates: a toast with the same tag replaces the previous one
	Tag     string
	Click   *Action
	Buttons []Action
	// Urgent keeps it on screen until dismissed (used for rings)
	Urgent bool
}

// Dest is a place files can be sent to, for Explorer's Send To menu.
type Dest struct {
	ID   string // device id, or "hub"
	Name string // shown in the menu
}

// Clip is what's on the clipboard, in the most useful form available.
type Clip struct {
	Text  string
	Files []string
	PNG   []byte // an image (e.g. a screenshot), re-encoded as PNG
}

// ActionURL builds the droplet: link a notification uses for an action,
// carrying the secret key so web pages can't trigger it through the scheme.
func ActionURL(key string, a Action) string {
	if a.Kind == ActOpenURL {
		return a.Arg
	}
	q := url.Values{"k": {key}}
	if a.Arg != "" {
		q.Set("p", a.Arg)
	}
	return Scheme + ":" + string(a.Kind) + "?" + q.Encode()
}

// ParseActionURL reverses ActionURL, rejecting links without the right key.
func ParseActionURL(raw, key string) (Action, bool) {
	rest, ok := strings.CutPrefix(raw, Scheme+":")
	if !ok || key == "" {
		return Action{}, false
	}
	kind, query, _ := strings.Cut(strings.TrimLeft(rest, "/"), "?")
	q, err := url.ParseQuery(query)
	if err != nil || q.Get("k") != key {
		return Action{}, false
	}
	switch k := ActionKind(strings.TrimRight(kind, "/")); k {
	case ActOpenFile, ActShowFile, ActOpenFolder, ActStopRing:
		return Action{Kind: k, Arg: q.Get("p")}, true
	}
	return Action{}, false
}
