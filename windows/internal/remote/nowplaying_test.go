package remote

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"math/rand"
	"strings"
	"testing"
)

func TestSMTCLineParsing(t *testing.T) {
	// PowerShell writes a lone session as an object, several as an array
	one := `{"current":"Spotify.exe","players":{"id":"Spotify.exe","status":"Playing","title":"Só","artist":"A","album":"B","position":12.5,"length":200,"can_next":true,"can_previous":true,"can_seek":true,"art_key":"k1"}}`
	var l SMTCLine
	if err := json.Unmarshal([]byte(one), &l); err != nil {
		t.Fatal(err)
	}
	ss, err := l.Sessions()
	if err != nil || len(ss) != 1 {
		t.Fatalf("%v %v", ss, err)
	}
	ps := ToPlayers(l.Current, ss, map[string]string{"k1": "data:image/jpeg;base64,xx"})
	p := ps[0]
	if p.Name != "Spotify" || p.Status != "Playing" || p.Title != "Só" || *p.Position != 12.5 || *p.Length != 200 ||
		p.CanSeek || !p.CanNext || p.Art == nil {
		t.Fatalf("%+v", p)
	}

	many := `{"current":"Chrome","players":[{"id":"MSEdge","status":"Paused","length":0},{"id":"Chrome","status":"Changing","position":900,"length":60}]}`
	json.Unmarshal([]byte(many), &l)
	ss, _ = l.Sessions()
	ps = ToPlayers(l.Current, ss, nil)
	if len(ps) != 2 || ps[0].ID != "Chrome" || ps[0].Status != "Stopped" || *ps[0].Position != 60 || ps[1].Name != "Edge" || ps[1].Length != nil {
		t.Fatalf("%+v", ps)
	}
	json.Unmarshal([]byte(`{"current":"","players":[]}`), &l)
	if ss, _ := l.Sessions(); len(ToPlayers("", ss, nil)) != 0 || ToPlayers("", ss, nil) == nil {
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
	u, err := ArtDataURL(base64.StdEncoding.EncodeToString(b.Bytes()))
	if err != nil || !strings.HasPrefix(u, "data:image/jpeg;base64,") || len(u) > 64*1024 {
		t.Fatalf("len %d err %v", len(u), err)
	}
	if _, err := ArtDataURL("bm90IGFuIGltYWdl"); err == nil {
		t.Fatal("garbage should fail")
	}
}
