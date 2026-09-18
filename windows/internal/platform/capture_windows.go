package platform

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/png"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	gdi32 = windows.NewLazySystemDLL("gdi32.dll")

	pGetDC                  = user32.NewProc("GetDC")
	pReleaseDC              = user32.NewProc("ReleaseDC")
	pLockWorkStation        = user32.NewProc("LockWorkStation")
	pCreateCompatibleDC     = gdi32.NewProc("CreateCompatibleDC")
	pCreateCompatibleBitmap = gdi32.NewProc("CreateCompatibleBitmap")
	pSelectObject           = gdi32.NewProc("SelectObject")
	pBitBlt                 = gdi32.NewProc("BitBlt")
	pGetDIBits              = gdi32.NewProc("GetDIBits")
	pDeleteObject           = gdi32.NewProc("DeleteObject")
	pDeleteDC               = gdi32.NewProc("DeleteDC")
)

// LockScreen locks the PC, as Win+L does.
func LockScreen() error {
	if r, _, err := pLockWorkStation.Call(); r == 0 {
		return fmt.Errorf("LockWorkStation: %v", err)
	}
	return nil
}

type bitmapInfoHeader struct {
	size          uint32
	width, height int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xPelsPerMeter int32
	yPelsPerMeter int32
	clrUsed       uint32
	clrImportant  uint32
}

// CaptureScreen grabs every monitor (the whole virtual screen) as a PNG.
// The app is per-monitor DPI aware (see winres.json), so this is in real
// pixels, however each monitor is scaled.
func CaptureScreen() ([]byte, error) {
	vs := virtualScreen()
	w, h := vs.Width, vs.Height
	if w <= 0 || h <= 0 {
		return nil, errors.New("no screen to capture")
	}
	screen, _, _ := pGetDC.Call(0)
	if screen == 0 {
		return nil, errors.New("can't read the screen")
	}
	defer pReleaseDC.Call(0, screen)
	mem, _, _ := pCreateCompatibleDC.Call(screen)
	if mem == 0 {
		return nil, errors.New("CreateCompatibleDC failed")
	}
	defer pDeleteDC.Call(mem)
	bmp, _, _ := pCreateCompatibleBitmap.Call(screen, uintptr(w), uintptr(h))
	if bmp == 0 {
		return nil, fmt.Errorf("can't make a %d×%d bitmap", w, h)
	}
	defer pDeleteObject.Call(bmp)

	const srcCopy, captureBlt = 0x00CC0020, 0x40000000 // CAPTUREBLT: include layered windows
	old, _, _ := pSelectObject.Call(mem, bmp)
	blt := func(rop uintptr) (uintptr, error) {
		r, _, err := pBitBlt.Call(mem, 0, 0, uintptr(w), uintptr(h), screen,
			uintptr(int32(vs.Left)), uintptr(int32(vs.Top)), rop)
		return r, err
	}
	r, err := blt(srcCopy | captureBlt)
	if r == 0 {
		// some display drivers refuse CAPTUREBLT; without it, layered
		// (translucent) windows may be missing, which beats no picture
		r, err = blt(srcCopy)
	}
	pSelectObject.Call(mem, old) // GetDIBits wants the bitmap out of the DC
	if r == 0 {
		return nil, fmt.Errorf("BitBlt: %v", err)
	}

	bi := bitmapInfoHeader{width: int32(w), height: -int32(h), planes: 1, bitCount: 32} // top-down, BI_RGB
	bi.size = uint32(unsafe.Sizeof(bi))
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	// BITMAPINFO is the header plus a colour table, which 32-bit BI_RGB doesn't use
	var info struct {
		hdr    bitmapInfoHeader
		colors [4]uint32
	}
	info.hdr = bi
	lines, _, err := pGetDIBits.Call(mem, bmp, 0, uintptr(h), uintptr(unsafe.Pointer(&img.Pix[0])),
		uintptr(unsafe.Pointer(&info)), 0) // DIB_RGB_COLORS
	if int(lines) != h {
		return nil, fmt.Errorf("GetDIBits: %v", err)
	}
	// BGRA from Windows, with no meaningful alpha → RGBA, opaque
	p := img.Pix
	for i := 0; i+3 < len(p); i += 4 {
		p[i], p[i+2], p[i+3] = p[i+2], p[i], 0xff
	}
	var out bytes.Buffer
	enc := png.Encoder{CompressionLevel: png.BestSpeed}
	if err := enc.Encode(&out, img); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
