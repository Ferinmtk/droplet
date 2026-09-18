package sound

import (
	"encoding/binary"
	"testing"
)

func TestRingWAV(t *testing.T) {
	w := RingWAV()
	if string(w[0:4]) != "RIFF" || string(w[8:12]) != "WAVE" || string(w[36:40]) != "data" {
		t.Fatal("bad WAV header")
	}
	if int(binary.LittleEndian.Uint32(w[4:]))+8 != len(w) {
		t.Fatal("RIFF size mismatch")
	}
	if binary.LittleEndian.Uint32(w[24:]) != 22050 {
		t.Fatal("sample rate")
	}
	// loud: peak near full scale
	peak := 0
	for i := 44; i+1 < len(w); i += 2 {
		v := int(int16(binary.LittleEndian.Uint16(w[i:])))
		if v < 0 {
			v = -v
		}
		if v > peak {
			peak = v
		}
	}
	if peak < 30000 {
		t.Fatalf("ring should be loud, peak %d", peak)
	}
}
