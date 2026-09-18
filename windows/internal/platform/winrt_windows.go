package platform

import (
	"errors"
	"fmt"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	ole "github.com/go-ole/go-ole"
	"golang.org/x/sys/windows"
)

// A little WinRT, called directly: toasts and media sessions are WinRT APIs,
// and they're reached the way C++ reaches them, through RoGetActivationFactory
// and interface vtables. No cgo and no helper processes.
//
// Slot numbers and interface ids come from the Windows SDK metadata (as
// generated into microsoft/windows-rs, and Wine's IDL files). Every WinRT
// interface starts with IInspectable's six methods, so its own methods start
// at slot 6.

var (
	combase = windows.NewLazySystemDLL("combase.dll")
	shcore  = windows.NewLazySystemDLL("shcore.dll")

	pRoInitialize                       = combase.NewProc("RoInitialize")
	pRoGetActivationFactory             = combase.NewProc("RoGetActivationFactory")
	pRoActivateInstance                 = combase.NewProc("RoActivateInstance")
	pWindowsCreateString                = combase.NewProc("WindowsCreateString")
	pWindowsDeleteString                = combase.NewProc("WindowsDeleteString")
	pWindowsGetStringRawBuffer          = combase.NewProc("WindowsGetStringRawBuffer")
	pCreateStreamOverRandomAccessStream = shcore.NewProc("CreateStreamOverRandomAccessStream")
)

var (
	iidIAsyncInfo = ole.NewGUID("{00000036-0000-0000-C000-000000000046}")
	iidIStream    = ole.NewGUID("{0000000C-0000-0000-C000-000000000046}")
)

// startWinRT readies the calling goroutine's thread for WinRT calls: it's
// locked to its thread for good and joins the multithreaded apartment,
// which is where WinRT objects that aren't UI live. Call it first on a
// goroutine that only does WinRT work and never ends while it's needed.
func startWinRT() error {
	runtime.LockOSThread()
	hr, _, _ := pRoInitialize.Call(1) // RO_INIT_MULTITHREADED
	const rpcEChangedMode = 0x80010106
	if uint32(hr) == rpcEChangedMode {
		return nil // already in a single-threaded apartment: works too
	}
	return check(hr, "RoInitialize")
}

// --- HSTRING ------------------------------------------------------------------

type hstring uintptr

// newHString makes a WinRT string; free it with free. The length is in
// UTF-16 units, so text outside the BMP (emoji) comes through whole.
func newHString(s string) (hstring, error) {
	u, err := windows.UTF16FromString(s)
	if err != nil {
		return 0, err // a NUL inside s
	}
	var h hstring
	hr, _, _ := pWindowsCreateString.Call(uintptr(unsafe.Pointer(&u[0])), uintptr(len(u)-1), uintptr(unsafe.Pointer(&h)))
	runtime.KeepAlive(u)
	return h, check(hr, "WindowsCreateString")
}

func (h hstring) free() {
	if h != 0 {
		pWindowsDeleteString.Call(uintptr(h))
	}
}

// String copies the text out; a zero HSTRING is the empty string.
func (h hstring) String() string {
	if h == 0 {
		return ""
	}
	var n uint32
	p, _, _ := pWindowsGetStringRawBuffer.Call(uintptr(h), uintptr(unsafe.Pointer(&n)))
	if p == 0 || n == 0 {
		return ""
	}
	// p points into the HSTRING's own memory (not Go's); copy it out now
	ptr := *(*unsafe.Pointer)(unsafe.Pointer(&p))
	return windows.UTF16ToString(unsafe.Slice((*uint16)(ptr), n))
}

// --- interface pointers ---------------------------------------------------------

// check turns a returned HRESULT into an error (nil for S_OK and S_FALSE).
func check(hr uintptr, what string) error {
	if int32(hr) >= 0 {
		return nil
	}
	msg := ""
	switch uint32(hr) {
	case 0x80040154: // REGDB_E_CLASSNOTREG
		msg = " (not available on this Windows)"
	case 0x80070490: // E_ELEMENT_NOT_FOUND
		msg = " (not found)"
	}
	return fmt.Errorf("%s: HRESULT 0x%08X%s", what, uint32(hr), msg)
}

// comPtr is a COM or WinRT interface pointer, released with release.
//
// Methods are called as syscall.SyscallN(o.method(slot), o.this(), args…),
// with any Go pointers converted to uintptr right there in the argument
// list: that's the form in which Go promises to keep them alive and in place
// for the call.
type comPtr struct{ p unsafe.Pointer }

func (o comPtr) this() uintptr { return uintptr(o.p) }

// method is the address of the vtable entry at slot.
func (o comPtr) method(slot int) uintptr {
	vtbl := *(*unsafe.Pointer)(o.p)
	return *(*uintptr)(unsafe.Add(vtbl, slot*int(unsafe.Sizeof(uintptr(0)))))
}

func (o *comPtr) release() {
	if o.p != nil {
		syscall.SyscallN(o.method(slotRelease), o.this())
		o.p = nil
	}
}

func (o comPtr) query(iid *ole.GUID) (comPtr, error) {
	var out comPtr
	hr, _, _ := syscall.SyscallN(o.method(0), o.this(), uintptr(unsafe.Pointer(iid)), uintptr(unsafe.Pointer(&out.p)))
	if err := check(hr, "QueryInterface"); err != nil {
		return comPtr{}, err
	}
	if out.p == nil {
		return comPtr{}, errors.New("QueryInterface: no interface")
	}
	return out, nil
}

// getObj calls a method whose only argument is an interface out-parameter
// (a property getter or a method like RequestAsync). A null result is an
// error unless nullOK.
func (o comPtr) getObj(slot int, what string, nullOK bool) (comPtr, error) {
	var out comPtr
	hr, _, _ := syscall.SyscallN(o.method(slot), o.this(), uintptr(unsafe.Pointer(&out.p)))
	if err := check(hr, what); err != nil {
		return comPtr{}, err
	}
	if out.p == nil && !nullOK {
		return comPtr{}, errors.New(what + ": null")
	}
	return out, nil
}

func (o comPtr) getString(slot int, what string) (string, error) {
	var h hstring
	hr, _, _ := syscall.SyscallN(o.method(slot), o.this(), uintptr(unsafe.Pointer(&h)))
	if err := check(hr, what); err != nil {
		return "", err
	}
	defer h.free()
	return h.String(), nil
}

// getBool reads a WinRT boolean (one byte).
func (o comPtr) getBool(slot int, what string) (bool, error) {
	var b byte
	hr, _, _ := syscall.SyscallN(o.method(slot), o.this(), uintptr(unsafe.Pointer(&b)))
	return b != 0, check(hr, what)
}

func (o comPtr) getInt32(slot int, what string) (int32, error) {
	var v int32
	hr, _, _ := syscall.SyscallN(o.method(slot), o.this(), uintptr(unsafe.Pointer(&v)))
	return v, check(hr, what)
}

// getInt64 reads a 64-bit value: a TimeSpan or DateTime (100 ns ticks) or a UINT64.
func (o comPtr) getInt64(slot int, what string) (int64, error) {
	var v int64
	hr, _, _ := syscall.SyscallN(o.method(slot), o.this(), uintptr(unsafe.Pointer(&v)))
	return v, check(hr, what)
}

// factory returns a runtime class's activation factory (or statics) as iid.
func factory(class string, iid *ole.GUID) (comPtr, error) {
	h, err := newHString(class)
	if err != nil {
		return comPtr{}, err
	}
	defer h.free()
	var out comPtr
	hr, _, _ := pRoGetActivationFactory.Call(uintptr(h), uintptr(unsafe.Pointer(iid)), uintptr(unsafe.Pointer(&out.p)))
	if err := check(hr, class); err != nil {
		return comPtr{}, err
	}
	return out, nil
}

// activate makes an instance of a runtime class with a default constructor,
// as its IInspectable.
func activate(class string) (comPtr, error) {
	h, err := newHString(class)
	if err != nil {
		return comPtr{}, err
	}
	defer h.free()
	var out comPtr
	hr, _, _ := pRoActivateInstance.Call(uintptr(h), uintptr(unsafe.Pointer(&out.p)))
	if err := check(hr, class); err != nil {
		return comPtr{}, err
	}
	return out, nil
}

// --- async operations -----------------------------------------------------------

// AsyncStatus values (IAsyncInfo.Status).
const (
	asyncStarted   = 0
	asyncCompleted = 1
	asyncCanceled  = 2
	asyncError     = 3
)

// await waits for an IAsyncOperation<T> to finish and returns its result,
// an interface pointer. It polls the operation's status rather than
// registering a completion handler, which would mean implementing a COM
// object in Go; a few milliseconds' latency doesn't matter here. op is
// released either way.
func await(op comPtr, timeout time.Duration, what string) (comPtr, error) {
	defer op.release()
	info, err := op.query(iidIAsyncInfo)
	if err != nil {
		return comPtr{}, fmt.Errorf("%s: %w", what, err)
	}
	defer info.release()
	deadline := time.Now().Add(timeout)
	wait := time.Millisecond
	for {
		// IAsyncInfo: Id 6, Status 7, ErrorCode 8, Cancel 9, Close 10
		st, err := info.getInt32(7, what+" status")
		if err != nil {
			return comPtr{}, err
		}
		switch st {
		case asyncCompleted:
			// IAsyncOperation<T>: put_Completed 6, get_Completed 7, GetResults 8
			return op.getObj(8, what, true)
		case asyncCanceled:
			return comPtr{}, errors.New(what + ": cancelled")
		case asyncError:
			code, _ := info.getInt32(8, what+" error")
			return comPtr{}, check(uintptr(uint32(code)), what)
		}
		if time.Now().After(deadline) {
			syscall.SyscallN(info.method(9), info.this()) // Cancel
			return comPtr{}, errors.New(what + ": timed out")
		}
		time.Sleep(wait)
		wait = min(wait*2, 25*time.Millisecond)
	}
}
