// Package sound makes the ring tone: a bright two-note chime, loud enough to
// find a PC across the room, built at startup instead of shipping a WAV file.
package sound

import (
	"bytes"
	"encoding/binary"
	"math"
)

const rate = 22050

// note is one struck chime: a fundamental with a couple of bell-like partials.
type note struct {
	at, freq float64 // start (s), pitch (Hz)
}

// RingWAV returns one 1.6 s cycle of the ring tone as a 16-bit mono WAV,
// meant to be played on a loop.
func RingWAV() []byte {
	const length = 1.6
	notes := []note{{0, 1318.5}, {0.18, 1760}, {0.36, 2093}, {0.72, 1318.5}, {0.9, 1760}, {1.08, 2093}}
	n := int(length * rate)
	mix := make([]float64, n)
	for _, nt := range notes {
		start := int(nt.at * rate)
		for i := start; i < n; i++ {
			t := float64(i-start) / rate
			// fast attack, bell-like decay
			env := math.Min(t/0.004, 1) * math.Exp(-t*7)
			if t > 0.01 && env < 1e-4 {
				break
			}
			s := math.Sin(2*math.Pi*nt.freq*t) +
				0.35*math.Sin(2*math.Pi*nt.freq*2.01*t)*math.Exp(-t*4) +
				0.15*math.Sin(2*math.Pi*nt.freq*3.02*t)*math.Exp(-t*9)
			mix[i] += s * env
		}
	}
	// fade the last 50 ms so the loop point doesn't click
	fade := rate / 20
	for i := 0; i < fade; i++ {
		mix[n-1-i] *= float64(i) / float64(fade)
	}
	peak := 0.0
	for _, v := range mix {
		peak = math.Max(peak, math.Abs(v))
	}
	pcm := make([]int16, n)
	for i, v := range mix {
		pcm[i] = int16(v / peak * 0.95 * math.MaxInt16) // loud, just short of clipping
	}
	return wav(pcm)
}

func wav(pcm []int16) []byte {
	var b bytes.Buffer
	dataLen := uint32(len(pcm) * 2)
	b.WriteString("RIFF")
	binary.Write(&b, binary.LittleEndian, 36+dataLen)
	b.WriteString("WAVEfmt ")
	for _, v := range []any{
		uint32(16), uint16(1), uint16(1), // PCM, mono
		uint32(rate), uint32(rate * 2), // sample rate, byte rate
		uint16(2), uint16(16), // block align, bits
	} {
		binary.Write(&b, binary.LittleEndian, v)
	}
	b.WriteString("data")
	binary.Write(&b, binary.LittleEndian, dataLen)
	binary.Write(&b, binary.LittleEndian, pcm)
	return b.Bytes()
}
