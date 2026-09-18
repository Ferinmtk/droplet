// Package assets embeds droplet's icons (made by tools/mkicon).
package assets

import _ "embed"

// Icon is the tray and window icon.
//
//go:embed droplet.ico
var Icon []byte

// IconDim is the tray icon while the hub can't be reached.
//
//go:embed droplet-dim.ico
var IconDim []byte

// IconLive is the tray icon for a few seconds after remote input.
//
//go:embed droplet-live.ico
var IconLive []byte

// PNG is the icon shown on notifications.
//
//go:embed droplet.png
var PNG []byte
