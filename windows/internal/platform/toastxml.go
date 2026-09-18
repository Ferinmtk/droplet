package platform

import (
	"fmt"
	"strings"
)

// toastXML is a notification as Windows' toast XML schema
// (learn.microsoft.com/windows/apps/design/shell/tiles-and-notifications/toast-schema).
// Clicks and buttons use protocol activation (https: links, or droplet:
// links handled by a new droplet.exe), so nothing has to stay registered as
// a COM server to receive them.
func toastXML(n Notification, actionKey string) string {
	var x strings.Builder
	x.WriteString(`<toast`)
	if n.Click != nil {
		fmt.Fprintf(&x, ` activationType="protocol" launch="%s"`, xmlEsc(ActionURL(actionKey, *n.Click)))
	}
	if n.Urgent {
		x.WriteString(` scenario="incomingCall"`)
	}
	x.WriteString(`><visual><binding template="ToastGeneric">`)
	fmt.Fprintf(&x, `<text hint-maxLines="1">%s</text>`, xmlEsc(clip(n.Title, 120)))
	if n.Body != "" {
		fmt.Fprintf(&x, `<text>%s</text>`, xmlEsc(clip(n.Body, 400)))
	}
	x.WriteString(`</binding></visual>`)
	if len(n.Buttons) > 0 {
		x.WriteString(`<actions>`)
		for _, b := range n.Buttons {
			fmt.Fprintf(&x, `<action content="%s" activationType="protocol" arguments="%s"/>`,
				xmlEsc(b.Label), xmlEsc(ActionURL(actionKey, b)))
		}
		x.WriteString(`</actions>`)
	}
	// rings have their own (louder, looping) sound; the rest keep the default chime
	if n.Urgent {
		x.WriteString(`<audio silent="true"/>`)
	}
	x.WriteString(`</toast>`)
	return x.String()
}

// toastTag is a notification's tag as Windows accepts it: at most 64
// UTF-16 units (60 here, to be safe), cut between characters.
func toastTag(t string) string {
	units := 0
	for i, r := range t {
		n := 1
		if r > 0xFFFF {
			n = 2 // a surrogate pair
		}
		if units+n > 60 {
			return t[:i]
		}
		units += n
	}
	return t
}

func clip(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n-1]) + "…"
	}
	return s
}

// xmlEsc makes s safe as XML text or an attribute value. Characters XML
// can't carry at all (most control characters) are dropped, since one of
// them in a message would make Windows reject the whole notification.
func xmlEsc(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s { // invalid UTF-8 arrives here as U+FFFD
		switch {
		case r == '&':
			b.WriteString("&amp;")
		case r == '<':
			b.WriteString("&lt;")
		case r == '>':
			b.WriteString("&gt;")
		case r == '"':
			b.WriteString("&quot;")
		case r == '\'':
			b.WriteString("&apos;")
		case r == '\t', r == '\n', r == '\r':
			b.WriteRune(r)
		case r < 0x20, r == 0xFFFE, r == 0xFFFF:
			// not allowed in XML 1.0
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}
