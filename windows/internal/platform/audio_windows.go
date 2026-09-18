package platform

import (
	"errors"
	"fmt"
	"math"
	"runtime"
	"syscall"
	"unsafe"

	ole "github.com/go-ole/go-ole"
	"golang.org/x/sys/windows"

	"github.com/Ferinmtk/droplet/windows/internal/remote"
)

// The system volume through Core Audio's IAudioEndpointVolume, called by
// vtable slot (no cgo). Each call looks the default output device up
// afresh, so plugging in headphones is followed.

var (
	clsidMMDeviceEnumerator = ole.NewGUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
	iidIMMDeviceEnumerator  = ole.NewGUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
	iidIAudioEndpointVolume = ole.NewGUID("{5CDF2C82-841E-4546-9722-0CF74078229A}")
)

// vtable slots
const (
	slotRelease = 2
	// IMMDeviceEnumerator
	slotGetDefaultAudioEndpoint = 4
	// IMMDevice
	slotActivate = 3
	// IAudioEndpointVolume
	slotSetMasterVolumeLevelScalar = 7
	slotGetMasterVolumeLevelScalar = 9
	slotSetMute                    = 14
	slotGetMute                    = 15

	eRender   = 0
	eConsole  = 0
	clsctxAll = 0x17
)

// method is the address of an interface's method in its vtable.
// obj is a COM pointer: memory Windows owns, which Go's collector never
// moves or frees, hence the round trip through a variable to please vet.
func method(obj uintptr, slot int) uintptr {
	p := *(*unsafe.Pointer)(unsafe.Pointer(&obj))
	vtbl := *(*unsafe.Pointer)(p)
	return *(*uintptr)(unsafe.Add(vtbl, slot*int(unsafe.Sizeof(uintptr(0)))))
}

func release(obj uintptr) {
	if obj != 0 {
		syscall.SyscallN(method(obj, slotRelease), obj)
	}
}

func hresult(hr uintptr, what string) error {
	if int32(hr) < 0 {
		return fmt.Errorf("%s: %w", what, ole.NewError(hr))
	}
	return nil
}

type coreAudio struct{}

// withEndpoint runs fn with the default output device's IAudioEndpointVolume.
func withEndpoint(fn func(ep uintptr) error) error {
	// COM is set up per thread: pin this goroutine to one while we use it
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	switch err := windows.CoInitializeEx(0, windows.COINIT_MULTITHREADED); {
	case err == nil || err == syscall.Errno(1): // S_OK, S_FALSE: ours to undo
		defer windows.CoUninitialize()
	case err == syscall.Errno(0x80010106): // RPC_E_CHANGED_MODE: already STA, usable
	default:
		return fmt.Errorf("COM: %v", err)
	}
	unk, err := ole.CreateInstance(clsidMMDeviceEnumerator, iidIMMDeviceEnumerator)
	if err != nil {
		return fmt.Errorf("no audio device enumerator: %w", err)
	}
	enum := uintptr(unsafe.Pointer(unk))
	defer release(enum)
	var dev uintptr
	hr, _, _ := syscall.SyscallN(method(enum, slotGetDefaultAudioEndpoint), enum, eRender, eConsole, uintptr(unsafe.Pointer(&dev)))
	if err := hresult(hr, "no default output device"); err != nil {
		return err
	}
	defer release(dev)
	var ep uintptr
	hr, _, _ = syscall.SyscallN(method(dev, slotActivate), dev, uintptr(unsafe.Pointer(iidIAudioEndpointVolume)), clsctxAll, 0, uintptr(unsafe.Pointer(&ep)))
	if err := hresult(hr, "no volume control on the output device"); err != nil {
		return err
	}
	if ep == 0 {
		return errors.New("no volume control on the output device")
	}
	defer release(ep)
	return fn(ep)
}

func (coreAudio) Get() (remote.Volume, error) {
	var v remote.Volume
	err := withEndpoint(func(ep uintptr) error {
		var level float32
		var muted int32
		hr, _, _ := syscall.SyscallN(method(ep, slotGetMasterVolumeLevelScalar), ep, uintptr(unsafe.Pointer(&level)))
		if err := hresult(hr, "reading the volume"); err != nil {
			return err
		}
		hr, _, _ = syscall.SyscallN(method(ep, slotGetMute), ep, uintptr(unsafe.Pointer(&muted)))
		if err := hresult(hr, "reading mute"); err != nil {
			return err
		}
		v = remote.Volume{Level: math.Max(0, math.Min(1, float64(level))), Muted: muted != 0}
		return nil
	})
	return v, err
}

func (coreAudio) SetLevel(level float64) error {
	return withEndpoint(func(ep uintptr) error {
		// the float travels in XMM1; Go's Windows syscalls copy each
		// integer argument into the matching XMM register too
		bits := uintptr(math.Float32bits(float32(math.Max(0, math.Min(1, level)))))
		hr, _, _ := syscall.SyscallN(method(ep, slotSetMasterVolumeLevelScalar), ep, bits, 0)
		return hresult(hr, "setting the volume")
	})
}

func (coreAudio) SetMuted(m bool) error {
	return withEndpoint(func(ep uintptr) error {
		var b uintptr
		if m {
			b = 1
		}
		hr, _, _ := syscall.SyscallN(method(ep, slotSetMute), ep, b, 0)
		return hresult(hr, "setting mute")
	})
}
