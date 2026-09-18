package platform

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"unsafe"

	ole "github.com/go-ole/go-ole"
	"golang.org/x/sys/windows"
)

// Shortcut is a .lnk file to create.
type Shortcut struct {
	Path, Target, Args, Description, Icon string
	// AppID, when set, stamps System.AppUserModel.ID on the shortcut
	AppID string
}

var (
	clsidShellLink    = ole.NewGUID("{00021401-0000-0000-C000-000000000046}")
	iidIShellLinkW    = ole.NewGUID("{000214F9-0000-0000-C000-000000000046}")
	iidIPersistFile   = ole.NewGUID("{0000010b-0000-0000-C000-000000000046}")
	iidIPropertyStore = ole.NewGUID("{886d8eeb-8cf2-4446-8d02-cdba1dbdcf99}")
	pkeyAppUserModel  = propertyKey{fmtid: *ole.NewGUID("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}"), pid: 5}
)

type propertyKey struct {
	fmtid ole.GUID
	pid   uint32
}

// propVariant is PROPVARIANT holding a VT_LPWSTR (24 bytes on 64-bit).
type propVariant struct {
	vt                    uint16
	reserved1, res2, res3 uint16
	val                   uintptr
	_                     uintptr
}

// comObject is a raw COM interface pointer; methods are called by vtable slot.
type comObject struct{ p *ole.IUnknown }

func (o comObject) call(slot int, args ...uintptr) error {
	fn := *(*uintptr)(unsafe.Add(unsafe.Pointer(o.p.RawVTable), slot*int(unsafe.Sizeof(uintptr(0)))))
	hr, _, _ := syscall.SyscallN(fn, append([]uintptr{uintptr(unsafe.Pointer(o.p))}, args...)...)
	if int32(hr) < 0 {
		return ole.NewError(hr)
	}
	return nil
}

// strings16 holds UTF-16 copies of strings passed to COM as raw uintptrs, which
// alone wouldn't stop the garbage collector freeing them mid-call.
type strings16 struct{ keep []*uint16 }

func (k *strings16) ptr(s string) uintptr {
	p, _ := windows.UTF16PtrFromString(s)
	k.keep = append(k.keep, p)
	return uintptr(unsafe.Pointer(p))
}

// CreateShortcut writes a .lnk through the shell's own IShellLink, the same
// way Explorer does, so it gets the right icon and behaves like any other shortcut.
func CreateShortcut(s Shortcut) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	// S_OK or S_FALSE (already initialised) both need a matching uninit; any
	// other failure means COM is already up in another mode, which also works
	err := ole.CoInitializeEx(0, ole.COINIT_APARTMENTTHREADED)
	var oe *ole.OleError
	if err == nil || (errors.As(err, &oe) && oe.Code() == 1) {
		defer ole.CoUninitialize()
	}

	unk, err := ole.CreateInstance(clsidShellLink, iidIShellLinkW)
	if err != nil {
		return fmt.Errorf("IShellLink: %w", err)
	}
	link := comObject{unk}
	defer unk.Release()
	var str strings16
	defer runtime.KeepAlive(&str)
	wstr := str.ptr

	// IShellLinkW slots: SetDescription 7, SetArguments 11, SetIconLocation 17, SetPath 20, SetWorkingDirectory 9
	if err := link.call(20, wstr(s.Target)); err != nil {
		return fmt.Errorf("SetPath: %w", err)
	}
	if err := link.call(9, wstr(filepath.Dir(s.Target))); err != nil {
		return err
	}
	if s.Args != "" {
		if err := link.call(11, wstr(s.Args)); err != nil {
			return err
		}
	}
	if s.Description != "" {
		if err := link.call(7, wstr(s.Description)); err != nil {
			return err
		}
	}
	if s.Icon != "" {
		if err := link.call(17, wstr(s.Icon), 0); err != nil {
			return err
		}
	}
	if s.AppID != "" {
		if err := setAppID(unk, s.AppID); err != nil {
			return fmt.Errorf("AppUserModelID: %w", err)
		}
	}

	pf, err := unk.QueryInterface(iidIPersistFile)
	if err != nil {
		return fmt.Errorf("IPersistFile: %w", err)
	}
	defer pf.Release()
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o755); err != nil {
		return err
	}
	// IPersistFile slot 6: Save(path, remember)
	return comObject{&pf.IUnknown}.call(6, wstr(s.Path), 1)
}

func setAppID(link *ole.IUnknown, id string) error {
	ps, err := link.QueryInterface(iidIPropertyStore)
	if err != nil {
		return err
	}
	defer ps.Release()
	store := comObject{&ps.IUnknown}
	var str strings16
	defer runtime.KeepAlive(&str)
	v := propVariant{vt: 31 /* VT_LPWSTR */, val: str.ptr(id)}
	// IPropertyStore slots: SetValue 6, Commit 7
	if err := store.call(6, uintptr(unsafe.Pointer(&pkeyAppUserModel)), uintptr(unsafe.Pointer(&v))); err != nil {
		return err
	}
	runtime.KeepAlive(v)
	return store.call(7)
}
