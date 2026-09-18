package remote

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"image"
	"image/jpeg"
	_ "image/png" // album art arrives as JPEG or PNG
	"strings"

	_ "golang.org/x/image/bmp"
	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

// SMTCLine is one line from the Windows now-playing helper (a PowerShell
// loop over GlobalSystemMediaTransportControlsSessionManager): either the
// sessions, or the album art for one of them.
type SMTCLine struct {
	Current string          `json:"current"`
	Players json.RawMessage `json:"players"`
	ArtKey  string          `json:"art_key"`
	Art     string          `json:"art"` // base64 image, only on art lines
	Error   string          `json:"error"`
}

// SMTCSession is one session as the helper reports it.
type SMTCSession struct {
	ID          string  `json:"id"`
	Status      string  `json:"status"`
	Title       string  `json:"title"`
	Artist      string  `json:"artist"`
	Album       string  `json:"album"`
	Position    float64 `json:"position"`
	Length      float64 `json:"length"`
	CanSeek     bool    `json:"can_seek"`
	CanNext     bool    `json:"can_next"`
	CanPrevious bool    `json:"can_previous"`
	ArtKey      string  `json:"art_key"`
}

// Sessions decodes the players field, which PowerShell may write as a
// single object instead of a one-element array.
func (l SMTCLine) Sessions() ([]SMTCSession, error) {
	raw := bytes.TrimSpace(l.Players)
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	if raw[0] == '{' {
		var one SMTCSession
		err := json.Unmarshal(raw, &one)
		return []SMTCSession{one}, err
	}
	var many []SMTCSession
	err := json.Unmarshal(raw, &many)
	return many, err
}

// ToPlayers converts sessions to protocol players, the current one first.
// art maps art keys to data: URLs.
func ToPlayers(current string, ss []SMTCSession, art map[string]string) []Player {
	out := []Player{}
	for _, s := range ss {
		if s.ID == "" {
			continue
		}
		p := Player{
			ID: s.ID, Name: PlayerName(s.ID), Status: normStatus(s.Status),
			Title: s.Title, Artist: s.Artist, Album: s.Album,
			CanSeek: false, CanNext: s.CanNext, CanPrevious: s.CanPrevious,
		}
		// seeking isn't wired up on Windows, so don't offer it, whatever the app says
		if s.Length > 0 {
			l := s.Length
			pos := min(max(s.Position, 0), l)
			p.Length, p.Position = &l, &pos
		}
		if u, ok := art[s.ArtKey]; ok && s.ArtKey != "" {
			p.Art = &u
		}
		if s.ID == current {
			out = append([]Player{p}, out...)
		} else {
			out = append(out, p)
		}
	}
	return out
}

func normStatus(s string) string {
	switch s {
	case "Playing", "Paused":
		return s
	}
	return "Stopped"
}

// PlayerName makes an app user model id readable: "Spotify.exe" →
// "Spotify", "Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic" →
// "Media Player".
func PlayerName(aumid string) string {
	known := map[string]string{
		"spotify": "Spotify", "chrome": "Chrome", "msedge": "Edge", "firefox": "Firefox",
		"308046b0af4a39cb": "Firefox", "6f193ccc56814779": "Firefox", "microsoft.zunemusic": "Media Player",
		"microsoft.zunevideo": "Films & TV", "vlc": "VLC", "brave": "Brave", "opera": "Opera",
		"foobar2000": "foobar2000", "musicbee": "MusicBee", "itunes": "iTunes", "applemusic": "Apple Music",
		"appleinc.applemusicwin": "Apple Music",
	}
	name := aumid
	if i := strings.LastIndexAny(name, `\/`); i >= 0 {
		name = name[i+1:]
	}
	if i := strings.IndexByte(name, '!'); i >= 0 {
		name = name[:i]
	}
	if i := strings.IndexByte(name, '_'); i > 0 {
		name = name[:i]
	}
	name = strings.TrimSuffix(strings.TrimSuffix(name, ".exe"), ".EXE")
	if n, ok := known[strings.ToLower(name)]; ok {
		return n
	}
	if name == "" {
		return "Media"
	}
	return name
}

// maxArt is the protocol's cap on an art data: URL.
const maxArt = 64 * 1024

// ArtDataURL shrinks album art to a JPEG data: URL of at most 64 KB.
func ArtDataURL(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return "", err
	}
	src, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return "", err
	}
	for _, side := range []int{300, 200, 128} {
		b := src.Bounds()
		w, h := b.Dx(), b.Dy()
		if w <= 0 || h <= 0 {
			return "", errors.New("empty image")
		}
		if w > side || h > side {
			if w >= h {
				w, h = side, max(1, h*side/w)
			} else {
				w, h = max(1, w*side/h), side
			}
		}
		dst := image.NewRGBA(image.Rect(0, 0, w, h))
		draw.ApproxBiLinear.Scale(dst, dst.Bounds(), src, b, draw.Src, nil)
		for _, q := range []int{80, 60} {
			var out bytes.Buffer
			if err := jpeg.Encode(&out, dst, &jpeg.Options{Quality: q}); err != nil {
				return "", err
			}
			u := "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(out.Bytes())
			if len(u) <= maxArt {
				return u, nil
			}
		}
	}
	return "", errors.New("art too large")
}
