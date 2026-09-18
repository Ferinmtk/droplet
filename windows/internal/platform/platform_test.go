package platform

import (
	"bytes"
	"encoding/binary"
	"image/png"
	"testing"
)

func TestActionURLRoundTrip(t *testing.T) {
	key := "0123456789abcdef"
	a := Action{Kind: ActShowFile, Arg: `C:\Users\m\Downloads\droplet\report (1).pdf`}
	u := ActionURL(key, a)
	got, ok := ParseActionURL(u, key)
	if !ok || got.Kind != a.Kind || got.Arg != a.Arg {
		t.Fatalf("%s -> %+v %v", u, got, ok)
	}
	if _, ok := ParseActionURL(u, "other-key"); ok {
		t.Fatal("wrong key must be rejected")
	}
	if _, ok := ParseActionURL("droplet:open?p=C:%5Cevil.exe", key); ok {
		t.Fatal("missing key must be rejected")
	}
	if _, ok := ParseActionURL("droplet:format-disk?k="+key, key); ok {
		t.Fatal("unknown actions must be rejected")
	}
	if ActionURL(key, Action{Kind: ActOpenURL, Arg: "https://hub/#chat-1"}) != "https://hub/#chat-1" {
		t.Fatal("url actions stay plain links")
	}
	// Windows may hand the link over as droplet://stop-ring/?k=…
	if a, ok := ParseActionURL("droplet://stop-ring/?k="+key, key); !ok || a.Kind != ActStopRing {
		t.Fatal("slashes after the scheme should be tolerated")
	}
}

func dibHeader(w, h int32, bpp uint16, compression uint32) []byte {
	var b bytes.Buffer
	for _, v := range []any{uint32(40), w, h, uint16(1), bpp, compression, uint32(0), int32(0), int32(0), uint32(0), uint32(0)} {
		binary.Write(&b, binary.LittleEndian, v)
	}
	return b.Bytes()
}

func TestDIBToPNG24BottomUp(t *testing.T) {
	// 2x2, rows bottom-up, 24-bit rows padded to 8 bytes
	dib := dibHeader(2, 2, 24, 0)
	dib = append(dib, 0, 0, 255, 0, 255, 0, 0, 0)     // bottom row: red, green
	dib = append(dib, 255, 0, 0, 255, 255, 255, 0, 0) // top row: blue, white
	out, err := DIBToPNG(dib)
	if err != nil {
		t.Fatal(err)
	}
	img, err := png.Decode(bytes.NewReader(out))
	if err != nil {
		t.Fatal(err)
	}
	r, g, b, a := img.At(0, 0).RGBA()
	if r != 0 || g != 0 || b != 0xffff || a != 0xffff {
		t.Fatalf("top-left should be blue, got %v %v %v %v", r, g, b, a)
	}
	r, _, _, _ = img.At(0, 1).RGBA()
	if r != 0xffff {
		t.Fatal("bottom-left should be red")
	}
}

func TestDIBToPNG32NoAlphaIsOpaque(t *testing.T) {
	dib := dibHeader(1, -1, 32, 0) // top-down
	dib = append(dib, 10, 20, 30, 0)
	out, err := DIBToPNG(dib)
	if err != nil {
		t.Fatal(err)
	}
	img, _ := png.Decode(bytes.NewReader(out))
	r, g, b, a := img.At(0, 0).RGBA()
	if r>>8 != 30 || g>>8 != 20 || b>>8 != 10 || a != 0xffff {
		t.Fatalf("got %v %v %v %v", r>>8, g>>8, b>>8, a)
	}
}

func TestDIBRejectsGarbage(t *testing.T) {
	if _, err := DIBToPNG([]byte("nope")); err == nil {
		t.Fatal("expected error")
	}
	if _, err := DIBToPNG(dibHeader(1000, 1000, 32, 0)); err == nil {
		t.Fatal("truncated data must be rejected")
	}
}
