package platform

import (
	"bytes"
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	"image/png"
)

// DIBToPNG converts a clipboard CF_DIB / CF_DIBV5 bitmap (what a screenshot
// or "Copy image" puts there) to PNG. It handles the 24- and 32-bit layouts
// Windows uses for those; anything else is reported as unsupported.
func DIBToPNG(dib []byte) ([]byte, error) {
	if len(dib) < 40 {
		return nil, errors.New("clipboard image is too short")
	}
	le := binary.LittleEndian
	hdrSize := int(le.Uint32(dib[0:]))
	w := int(int32(le.Uint32(dib[4:])))
	h := int(int32(le.Uint32(dib[8:])))
	bpp := int(le.Uint16(dib[14:]))
	compression := le.Uint32(dib[16:])
	clrUsed := int(le.Uint32(dib[32:]))
	topDown := h < 0
	if topDown {
		h = -h
	}
	if w <= 0 || h <= 0 || w > 1<<15 || h > 1<<15 || hdrSize < 40 || hdrSize > len(dib) {
		return nil, errors.New("clipboard image has a bad header")
	}
	if bpp != 24 && bpp != 32 {
		return nil, errors.New("clipboard image format isn't supported (only 24/32-bit)")
	}
	const biRGB, biBitfields = 0, 3
	masks := [3]uint32{0xff0000, 0xff00, 0xff} // r, g, b
	offset := hdrSize
	switch compression {
	case biRGB:
	case biBitfields:
		if hdrSize == 40 { // masks follow a plain BITMAPINFOHEADER
			if len(dib) < 52 {
				return nil, errors.New("clipboard image is too short")
			}
			offset += 12
		}
		masks = [3]uint32{le.Uint32(dib[40:]), le.Uint32(dib[44:]), le.Uint32(dib[48:])}
	default:
		return nil, errors.New("compressed clipboard images aren't supported")
	}
	offset += clrUsed * 4
	stride := (w*bpp + 31) / 32 * 4
	if offset+stride*h > len(dib) {
		return nil, errors.New("clipboard image is truncated")
	}
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	anyAlpha := false
	step := bpp / 8
	for y := 0; y < h; y++ {
		row := dib[offset+stride*y:]
		dy := h - 1 - y
		if topDown {
			dy = y
		}
		for x := 0; x < w; x++ {
			px := row[x*step:]
			var r, g, b, a uint8
			if bpp == 24 {
				b, g, r, a = px[0], px[1], px[2], 255
			} else {
				v := le.Uint32(px)
				r, g, b = channel(v, masks[0]), channel(v, masks[1]), channel(v, masks[2])
				a = px[3]
				if compression == biBitfields && masks[0]|masks[1]|masks[2] != 0x00ffffff {
					a = 255 // unusual masks: don't guess where alpha lives
				}
				if a != 0 {
					anyAlpha = true
				}
			}
			img.SetNRGBA(x, dy, color.NRGBA{r, g, b, a})
		}
	}
	if bpp == 32 && !anyAlpha {
		// most apps leave the 4th byte at zero, meaning "no alpha", not "invisible"
		for i := 3; i < len(img.Pix); i += 4 {
			img.Pix[i] = 255
		}
	}
	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

func channel(v, mask uint32) uint8 {
	if mask == 0 {
		return 0
	}
	shift := 0
	for mask&1 == 0 {
		mask >>= 1
		shift++
	}
	c := (v >> shift) & mask
	if mask != 0xff && mask > 0 {
		c = c * 255 / mask
	}
	return uint8(c)
}
