// Command mkicon builds droplet's Windows icons from the web app's PNG icon:
//
//	go run ./tools/mkicon            (from windows/)
//
// It writes assets/droplet.ico (exe and tray), assets/droplet-dim.ico (tray
// while the hub is unreachable) and assets/droplet.png (notification icon).
// The outputs are committed, so building doesn't need this step.
package main

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
	"log"
	"os"
	"path/filepath"

	"golang.org/x/image/draw"
)

var sizes = []int{16, 20, 24, 32, 40, 48, 64, 256}

func main() {
	src := loadPNG(filepath.Join("..", "static", "icon-512.png"))
	must(os.MkdirAll("assets", 0o755))
	must(os.WriteFile(filepath.Join("assets", "droplet.ico"), ico(src), 0o644))
	must(os.WriteFile(filepath.Join("assets", "droplet-dim.ico"), ico(dim(src)), 0o644))
	var b bytes.Buffer
	must(png.Encode(&b, resize(src, 128)))
	must(os.WriteFile(filepath.Join("assets", "droplet.png"), b.Bytes(), 0o644))
}

func loadPNG(p string) image.Image {
	f, err := os.Open(p)
	must(err)
	defer f.Close()
	img, err := png.Decode(f)
	must(err)
	return img
}

func resize(src image.Image, n int) *image.NRGBA {
	dst := image.NewNRGBA(image.Rect(0, 0, n, n))
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Over, nil)
	return dst
}

// dim greys out the drop, for "can't reach the hub".
func dim(src image.Image) image.Image {
	b := src.Bounds()
	out := image.NewNRGBA(b)
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			c := color.NRGBAModel.Convert(src.At(x, y)).(color.NRGBA)
			l := uint8((299*int(c.R) + 587*int(c.G) + 114*int(c.B)) / 1000)
			// keep the dark tile dark, fade the bright drop to mid grey
			if l > 60 {
				l = 60 + (l-60)*2/5
			}
			out.SetNRGBA(x, y, color.NRGBA{l, l, l, c.A})
		}
	}
	return out
}

// ico packs the sizes into one .ico: classic 32-bit DIBs up to 64 px (read
// by every Windows API) and a PNG for 256 px (Vista and later).
func ico(src image.Image) []byte {
	var images [][]byte
	for _, n := range sizes {
		img := resize(src, n)
		if n >= 256 {
			var b bytes.Buffer
			must(png.Encode(&b, img))
			images = append(images, b.Bytes())
		} else {
			images = append(images, dib(img))
		}
	}
	var out bytes.Buffer
	w := func(v any) { must(binary.Write(&out, binary.LittleEndian, v)) }
	w(uint16(0))
	w(uint16(1)) // icon
	w(uint16(len(sizes)))
	offset := 6 + 16*len(sizes)
	for i, n := range sizes {
		dim := uint8(n)
		if n >= 256 {
			dim = 0 // 0 means 256
		}
		w(dim)
		w(dim)
		w(uint8(0)) // palette
		w(uint8(0))
		w(uint16(1))  // planes
		w(uint16(32)) // bpp
		w(uint32(len(images[i])))
		w(uint32(offset))
		offset += len(images[i])
	}
	for _, im := range images {
		out.Write(im)
	}
	return out.Bytes()
}

// dib is a BITMAPINFOHEADER + bottom-up BGRA pixels + an all-zero AND mask.
func dib(img *image.NRGBA) []byte {
	n := img.Bounds().Dx()
	var b bytes.Buffer
	w := func(v any) { must(binary.Write(&b, binary.LittleEndian, v)) }
	maskStride := ((n + 31) / 32) * 4
	w(uint32(40))
	w(int32(n))
	w(int32(n * 2)) // XOR + AND masks
	w(uint16(1))
	w(uint16(32))
	w(uint32(0))
	w(uint32(n*n*4 + maskStride*n))
	w(int32(0))
	w(int32(0))
	w(uint32(0))
	w(uint32(0))
	for y := n - 1; y >= 0; y-- {
		for x := 0; x < n; x++ {
			c := img.NRGBAAt(x, y)
			b.Write([]byte{c.B, c.G, c.R, c.A})
		}
	}
	b.Write(make([]byte, maskStride*n))
	return b.Bytes()
}

func must(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
