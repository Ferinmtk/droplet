package remote

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math/rand"
	"strings"
	"testing"
)

func TestToPlayers(t *testing.T) {
	one := []SMTCSession{{ID: "Spotify.exe", Status: "Playing", Title: "Só", Artist: "A", Album: "B",
		Position: 12.5, Length: 200, CanNext: true, CanPrevious: true, CanSeek: true, ArtKey: "k1"}}
	ps := ToPlayers("Spotify.exe", one, map[string]string{"k1": "data:image/jpeg;base64,xx"})
	p := ps[0]
	if p.Name != "Spotify" || p.Status != "Playing" || p.Title != "Só" || *p.Position != 12.5 || *p.Length != 200 ||
		p.CanSeek || !p.CanNext || p.Art == nil {
		t.Fatalf("%+v", p)
	}

	many := []SMTCSession{{ID: "MSEdge", Status: "Paused", Length: 0}, {ID: "Chrome", Status: "Changing", Position: 900, Length: 60}}
	ps = ToPlayers("Chrome", many, nil)
	if len(ps) != 2 || ps[0].ID != "Chrome" || ps[0].Status != "Stopped" || *ps[0].Position != 60 || ps[1].Name != "Edge" || ps[1].Length != nil {
		t.Fatalf("%+v", ps)
	}
	// art that couldn't be read (no entry in the map) is left out
	if ps := ToPlayers("", []SMTCSession{{ID: "x", ArtKey: "missing"}}, map[string]string{}); ps[0].Art != nil {
		t.Fatal("no art expected")
	}
	if ps := ToPlayers("", nil, nil); ps == nil || len(ps) != 0 {
		t.Fatal("nothing playing must be an empty list, not null")
	}
}

func TestPlayerName(t *testing.T) {
	for in, want := range map[string]string{
		"Spotify.exe": "Spotify", "Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic": "Media Player",
		"MSEdge": "Edge", "308046B0AF4A39CB": "Firefox", `C:\Apps\Foo.exe`: "Foo", "": "Media",
	} {
		if got := PlayerName(in); got != want {
			t.Errorf("PlayerName(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestArtDataURL(t *testing.T) {
	// a big noisy image (hard to compress) still fits in 64 KB
	img := image.NewRGBA(image.Rect(0, 0, 1200, 900))
	r := rand.New(rand.NewSource(1))
	for i := range img.Pix {
		img.Pix[i] = uint8(r.Intn(256))
	}
	img.Set(0, 0, color.White)
	var b bytes.Buffer
	png.Encode(&b, img)
	u, err := ArtDataURL(b.Bytes())
	if err != nil || !strings.HasPrefix(u, "data:image/jpeg;base64,") || len(u) > 64*1024 {
		t.Fatalf("len %d err %v", len(u), err)
	}
	if _, err := ArtDataURL([]byte("not an image")); err == nil {
		t.Fatal("garbage should fail")
	}
}
