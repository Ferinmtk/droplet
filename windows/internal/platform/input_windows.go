package platform

import (
	"fmt"
	"sync"
	"time"
	"unsafe"

	"github.com/Ferinmtk/droplet/windows/internal/remote"
)

// Input injection with SendInput.
//
// Windows won't deliver injected input to a window of a higher integrity
// level (UIPI): while an elevated app (Task Manager, an installer, an admin
// terminal) is in front, keys and clicks don't reach it, and SendInput
// reports fewer events inserted. Nothing reaches the lock screen or a UAC
// prompt either (the secure desktop). Running droplet elevated lifts the
// first limit; the second is by design.

var (
	pSendInput        = user32.NewProc("SendInput")
	pGetCursorPos     = user32.NewProc("GetCursorPos")
	pGetSystemMetrics = user32.NewProc("GetSystemMetrics")
	pMapVirtualKeyW   = user32.NewProc("MapVirtualKeyW")
)

const (
	inputMouse    = 0
	inputKeyboard = 1

	mouseMove        = 0x0001
	mouseLeftDown    = 0x0002
	mouseLeftUp      = 0x0004
	mouseRightDown   = 0x0008
	mouseRightUp     = 0x0010
	mouseMiddleDown  = 0x0020
	mouseMiddleUp    = 0x0040
	mouseWheel       = 0x0800
	mouseHWheel      = 0x1000
	mouseVirtualDesk = 0x4000
	mouseAbsolute    = 0x8000

	keyExtended = 0x0001
	keyUp       = 0x0002
	keyUnicode  = 0x0004

	smSwapButton      = 23
	smXVirtualScreen  = 76
	smYVirtualScreen  = 77
	smCXVirtualScreen = 78
	smCYVirtualScreen = 79
)

// mouseInput is MOUSEINPUT (32 bytes on 64-bit Windows).
type mouseInput struct {
	dx, dy    int32
	mouseData uint32
	flags     uint32
	time      uint32
	extra     uintptr
}

// keybdInput is KEYBDINPUT, padded to the size of the INPUT union.
type keybdInput struct {
	vk, scan uint16
	flags    uint32
	time     uint32
	extra    uintptr
	_        [8]byte
}

// inputRecord is INPUT: a type tag and the union (40 bytes on 64-bit).
type inputRecord struct {
	typ uint32
	mi  mouseInput // or keybdInput, same size
}

// fail the build if the layouts drift from what SendInput expects
var (
	_ [unsafe.Sizeof(inputRecord{}) - 40]byte
	_ [40 - unsafe.Sizeof(inputRecord{})]byte
	_ [unsafe.Sizeof(keybdInput{}) - unsafe.Sizeof(mouseInput{})]byte
	_ [unsafe.Sizeof(mouseInput{}) - unsafe.Sizeof(keybdInput{})]byte
)

func mouseRec(dx, dy int32, data, flags uint32) inputRecord {
	return inputRecord{typ: inputMouse, mi: mouseInput{dx: dx, dy: dy, mouseData: data, flags: flags}}
}

func keyRec(vk, scan uint16, flags uint32) inputRecord {
	r := inputRecord{typ: inputKeyboard}
	*(*keybdInput)(unsafe.Pointer(&r.mi)) = keybdInput{vk: vk, scan: scan, flags: flags}
	return r
}

type sendInput struct {
	mu  sync.Mutex
	ptr remote.Pointer
}

func metric(i uintptr) int {
	r, _, _ := pGetSystemMetrics.Call(i)
	return int(int32(r))
}

func virtualScreen() remote.Rect {
	return remote.Rect{
		Left: metric(smXVirtualScreen), Top: metric(smYVirtualScreen),
		Width: metric(smCXVirtualScreen), Height: metric(smCYVirtualScreen),
	}
}

func cursorPos() (remote.Point, error) {
	var p struct{ x, y int32 }
	if r, _, err := pGetCursorPos.Call(uintptr(unsafe.Pointer(&p))); r == 0 {
		// e.g. while the secure desktop (lock screen, UAC) is up
		return remote.Point{}, fmt.Errorf("can't read the cursor position: %v", err)
	}
	return remote.Point{X: int(p.x), Y: int(p.y)}, nil
}

var buttonFlags = map[remote.Button][2]uint32{
	remote.ButtonLeft:   {mouseLeftDown, mouseLeftUp},
	remote.ButtonRight:  {mouseRightDown, mouseRightUp},
	remote.ButtonMiddle: {mouseMiddleDown, mouseMiddleUp},
}

// Inject sends the strokes in one SendInput call, so nothing else can
// interleave with, say, a shortcut's modifiers.
func (s *sendInput) Inject(strokes []remote.Stroke) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	recs := make([]inputRecord, 0, len(strokes))
	var screen remote.Rect
	var first, pos remote.Point
	moved := false
	swapped := metric(smSwapButton) != 0 // "left" means the primary button
	for _, st := range strokes {
		switch st.Kind {
		case remote.StrokeMove:
			if !moved {
				c, err := cursorPos()
				if err != nil {
					return err
				}
				screen = virtualScreen()
				first, pos, moved = c, s.ptr.Base(c, time.Now()), true
			}
			pos = remote.Step(pos, st.DX, st.DY, screen)
			nx, ny := remote.Normalize(pos, screen)
			recs = append(recs, mouseRec(nx, ny, 0, mouseMove|mouseAbsolute|mouseVirtualDesk))
		case remote.StrokeButton:
			b := st.Button
			if swapped && b == remote.ButtonLeft {
				b = remote.ButtonRight
			} else if swapped && b == remote.ButtonRight {
				b = remote.ButtonLeft
			}
			f, ok := buttonFlags[b]
			if !ok {
				continue
			}
			flag := f[1]
			if st.Down {
				flag = f[0]
			}
			recs = append(recs, mouseRec(0, 0, 0, flag))
		case remote.StrokeWheel:
			recs = append(recs, mouseRec(0, 0, uint32(int32(st.Delta)), mouseWheel))
		case remote.StrokeHWheel:
			recs = append(recs, mouseRec(0, 0, uint32(int32(st.Delta)), mouseHWheel))
		case remote.StrokeKey:
			scan, _, _ := pMapVirtualKeyW.Call(uintptr(st.Key.VK), 0) // MAPVK_VK_TO_VSC
			var flags uint32
			if st.Key.Extended {
				flags |= keyExtended
			}
			if !st.Down {
				flags |= keyUp
			}
			recs = append(recs, keyRec(st.Key.VK, uint16(scan), flags))
		case remote.StrokeUnicode:
			flags := uint32(keyUnicode)
			if !st.Down {
				flags |= keyUp
			}
			recs = append(recs, keyRec(0, st.Unit, flags))
		}
	}
	if moved {
		s.ptr.Moved(first, pos, time.Now())
	}
	if len(recs) == 0 {
		return nil
	}
	n, _, err := pSendInput.Call(uintptr(len(recs)), uintptr(unsafe.Pointer(&recs[0])), unsafe.Sizeof(recs[0]))
	if int(n) != len(recs) {
		return fmt.Errorf("Windows blocked %d of %d input events (%v): an app running as administrator "+
			"is in front, or the screen is locked", len(recs)-int(n), len(recs), err)
	}
	return nil
}
