package platform

import (
	"errors"
	"fmt"
	"runtime"
	"sync"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

// The clipboard for live sync: change detection by sequence number,
// reading text (respecting apps that mark a copy as private), and writing.

var (
	pGetClipboardSequenceNumber  = user32.NewProc("GetClipboardSequenceNumber")
	pRegisterClipboardFormatW    = user32.NewProc("RegisterClipboardFormatW")
	pEmptyClipboard              = user32.NewProc("EmptyClipboard")
	pSetClipboardData            = user32.NewProc("SetClipboardData")
	pCreateWindowExW             = user32.NewProc("CreateWindowExW")
	pPeekMessageW                = user32.NewProc("PeekMessageW")
	pTranslateMessage            = user32.NewProc("TranslateMessage")
	pDispatchMessageW            = user32.NewProc("DispatchMessageW")
	pMsgWaitForMultipleObjectsEx = user32.NewProc("MsgWaitForMultipleObjectsEx")
	pGlobalAlloc                 = kernel32.NewProc("GlobalAlloc")
	pGlobalFree                  = kernel32.NewProc("GlobalFree")
)

type syncClipboard struct{}

func (syncClipboard) Seq() uint32 {
	r, _, _ := pGetClipboardSequenceNumber.Call()
	return uint32(r)
}

var (
	privateOnce     sync.Once
	fmtExclude      uintptr // ExcludeClipboardContentFromMonitorProcessing
	fmtViewerIgnore uintptr // Clipboard Viewer Ignore
	fmtNoCloud      uintptr // CanUploadToCloudClipboard
	fmtNoHistory    uintptr // CanIncludeInClipboardHistory
)

func registerFormat(name string) uintptr {
	p, _ := windows.UTF16PtrFromString(name)
	r, _, _ := pRegisterClipboardFormatW.Call(uintptr(unsafe.Pointer(p)))
	return r
}

// openClipboard opens the clipboard, retrying while another app holds it.
func openClipboard(owner uintptr) error {
	for i := 0; i < 10; i++ {
		if r, _, _ := pOpenClipboard.Call(owner); r != 0 {
			return nil
		}
		windows.SleepEx(30, false)
	}
	return errors.New("the clipboard is busy")
}

// ReadText returns the clipboard's text, unless the app that copied it
// flagged it as private: password managers set these formats so clipboard
// history, cloud sync and tools like this one leave the copy alone.
func (syncClipboard) ReadText() (string, bool) {
	privateOnce.Do(func() {
		fmtExclude = registerFormat("ExcludeClipboardContentFromMonitorProcessing")
		fmtViewerIgnore = registerFormat("Clipboard Viewer Ignore")
		fmtNoCloud = registerFormat("CanUploadToCloudClipboard")
		fmtNoHistory = registerFormat("CanIncludeInClipboardHistory")
	})
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if openClipboard(0) != nil {
		return "", false
	}
	defer pCloseClipboard.Call()
	avail := func(f uintptr) bool { r, _, _ := pIsClipboardFormatAvailable.Call(f); return f != 0 && r != 0 }
	if avail(fmtExclude) || avail(fmtViewerIgnore) {
		return "", false
	}
	for _, f := range []uintptr{fmtNoCloud, fmtNoHistory} {
		if avail(f) {
			// a DWORD; 0 means "don't"
			if b, err := globalBytes(f); err == nil && len(b) >= 4 && b[0]|b[1]|b[2]|b[3] == 0 {
				return "", false
			}
		}
	}
	if !avail(cfUnicodeText) {
		return "", false
	}
	data, err := globalBytes(cfUnicodeText)
	if err != nil || len(data) < 2 {
		return "", false
	}
	u := unsafe.Slice((*uint16)(unsafe.Pointer(&data[0])), len(data)/2)
	t := windows.UTF16ToString(u)
	return t, t != ""
}

// WriteText puts text on the clipboard. The clipboard wants an owner
// window, and Windows sends that window messages (WM_DESTROYCLIPBOARD…)
// which must be answered, so writes happen on a thread that owns a hidden
// message-only window and keeps its messages flowing.
func (syncClipboard) WriteText(text string) error {
	return clipOwner.do(func(hwnd uintptr) error {
		u := append(utf16.Encode([]rune(text)), 0)
		if err := openClipboard(hwnd); err != nil {
			return err
		}
		defer pCloseClipboard.Call()
		if r, _, err := pEmptyClipboard.Call(); r == 0 {
			return fmt.Errorf("EmptyClipboard: %v", err)
		}
		const gmemMoveable = 0x2
		h, _, err := pGlobalAlloc.Call(gmemMoveable, uintptr(len(u)*2))
		if h == 0 {
			return fmt.Errorf("GlobalAlloc: %v", err)
		}
		p, _, _ := pGlobalLock.Call(h)
		if p == 0 {
			pGlobalFree.Call(h)
			return errors.New("GlobalLock failed")
		}
		dst := unsafe.Slice((*uint16)(*(*unsafe.Pointer)(unsafe.Pointer(&p))), len(u))
		copy(dst, u)
		pGlobalUnlock.Call(h)
		if r, _, err := pSetClipboardData.Call(cfUnicodeText, h); r == 0 {
			pGlobalFree.Call(h) // still ours when SetClipboardData fails
			return fmt.Errorf("SetClipboardData: %v", err)
		}
		return nil
	})
}

// ownerThread runs jobs on one locked OS thread that owns a message-only
// window and pumps its messages.
type ownerThread struct {
	once  sync.Once
	err   error
	event windows.Handle
	jobs  chan func(hwnd uintptr)
}

var clipOwner = &ownerThread{jobs: make(chan func(uintptr), 8)}

func (o *ownerThread) do(job func(hwnd uintptr) error) error {
	o.once.Do(o.start)
	if o.err != nil {
		return o.err
	}
	done := make(chan error, 1)
	o.jobs <- func(h uintptr) { done <- job(h) }
	windows.SetEvent(o.event)
	return <-done
}

func (o *ownerThread) start() {
	ev, err := windows.CreateEvent(nil, 0, 0, nil) // auto-reset
	if err != nil {
		o.err = err
		return
	}
	o.event = ev
	ready := make(chan error)
	go func() {
		runtime.LockOSThread() // never unlocked: this thread owns the window
		class, _ := windows.UTF16PtrFromString("STATIC")
		title, _ := windows.UTF16PtrFromString("droplet clipboard")
		const hwndMessage = ^uintptr(2) // HWND_MESSAGE (-3)
		hwnd, _, err := pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(class)), uintptr(unsafe.Pointer(title)),
			0, 0, 0, 0, 0, hwndMessage, 0, 0, 0)
		if hwnd == 0 {
			ready <- fmt.Errorf("clipboard window: %v", err)
			return
		}
		ready <- nil
		var msg [8]uint64 // MSG is 48 bytes on 64-bit; 8-byte aligned
		const infinite, qsAllInput, mwmoInputAvailable, pmRemove = 0xFFFFFFFF, 0x04FF, 0x4, 0x1
		for {
			pMsgWaitForMultipleObjectsEx.Call(1, uintptr(unsafe.Pointer(&o.event)), infinite, qsAllInput, mwmoInputAvailable)
			for {
				r, _, _ := pPeekMessageW.Call(uintptr(unsafe.Pointer(&msg[0])), 0, 0, 0, pmRemove)
				if r == 0 {
					break
				}
				pTranslateMessage.Call(uintptr(unsafe.Pointer(&msg[0])))
				pDispatchMessageW.Call(uintptr(unsafe.Pointer(&msg[0])))
			}
			for more := true; more; {
				select {
				case job := <-o.jobs:
					job(hwnd)
				default:
					more = false
				}
			}
		}
	}()
	o.err = <-ready
}
