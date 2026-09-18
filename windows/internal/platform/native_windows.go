package platform

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")
	winmm    = windows.NewLazySystemDLL("winmm.dll")
	comdlg32 = windows.NewLazySystemDLL("comdlg32.dll")

	pMessageBoxW                = user32.NewProc("MessageBoxW")
	pOpenClipboard              = user32.NewProc("OpenClipboard")
	pCloseClipboard             = user32.NewProc("CloseClipboard")
	pGetClipboardData           = user32.NewProc("GetClipboardData")
	pIsClipboardFormatAvailable = user32.NewProc("IsClipboardFormatAvailable")
	pGlobalLock                 = kernel32.NewProc("GlobalLock")
	pGlobalUnlock               = kernel32.NewProc("GlobalUnlock")
	pGlobalSize                 = kernel32.NewProc("GlobalSize")
	pAttachConsole              = kernel32.NewProc("AttachConsole")
	pDragQueryFileW             = shell32.NewProc("DragQueryFileW")
	pPlaySoundW                 = winmm.NewProc("PlaySoundW")
	pGetOpenFileNameW           = comdlg32.NewProc("GetOpenFileNameW")
	pCommDlgExtendedError       = comdlg32.NewProc("CommDlgExtendedError")
)

// --- messages & console ----------------------------------------------------------

// MessageBox shows a plain dialog; used for errors when there's no console.
func MessageBox(title, text string, isError bool) {
	flags := uintptr(0x40) // MB_ICONINFORMATION
	if isError {
		flags = 0x10 // MB_ICONERROR
	}
	t, _ := windows.UTF16PtrFromString(title)
	m, _ := windows.UTF16PtrFromString(text)
	pMessageBoxW.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), flags|0x10000) // MB_SETFOREGROUND
}

// AttachConsole makes CLI output visible. droplet.exe is a GUI program (so
// the tray app never flashes a console), which means Windows gives it no
// console; borrow the one it was started from, unless output is redirected.
// Reports whether output has somewhere to go.
func AttachConsole() bool {
	if validHandle(windows.Stdout) {
		return true // redirected to a file or pipe: already fine
	}
	const attachParent = ^uintptr(0)
	if r, _, _ := pAttachConsole.Call(attachParent); r == 0 {
		return false
	}
	if f, err := os.OpenFile("CONOUT$", os.O_WRONLY, 0); err == nil {
		os.Stdout, os.Stderr = f, f
	}
	if f, err := os.OpenFile("CONIN$", os.O_RDONLY, 0); err == nil {
		os.Stdin = f
	}
	return true
}

func validHandle(h windows.Handle) bool {
	if h == 0 || h == windows.InvalidHandle {
		return false
	}
	t, err := windows.GetFileType(h)
	return err == nil && t != windows.FILE_TYPE_UNKNOWN
}

// --- single instance -------------------------------------------------------------

var instanceMutex windows.Handle

// FirstInstance takes the per-session "droplet is running" mutex. It returns
// false when another droplet tray is already running.
func FirstInstance() bool {
	name, _ := windows.UTF16PtrFromString(`Local\droplet-companion`)
	h, err := windows.CreateMutex(nil, false, name)
	if errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		if h != 0 {
			windows.CloseHandle(h)
		}
		return false
	}
	instanceMutex = h // held (never closed) for the life of the process
	return true
}

// --- opening things --------------------------------------------------------------

func shellOpen(target string) error {
	return windows.ShellExecute(0, windows.StringToUTF16Ptr("open"), windows.StringToUTF16Ptr(target), nil, nil, windows.SW_SHOWNORMAL)
}

// OpenURL opens a link in the default browser.
func OpenURL(u string) error { return shellOpen(u) }

// OpenPath opens a file with its default app, or a folder in Explorer.
func OpenPath(p string) error { return shellOpen(p) }

// ShowInFolder opens Explorer with the file selected.
func ShowInFolder(p string) error {
	cmd := exec.Command("explorer.exe")
	// explorer parses its own command line; it wants exactly /select,"path"
	cmd.SysProcAttr = &syscall.SysProcAttr{CmdLine: `explorer.exe /select,"` + p + `"`}
	return cmd.Start()
}

// --- ring sound ------------------------------------------------------------------

var (
	ringMu  sync.Mutex
	ringWAV []byte // PlaySound reads from this while it plays: keep it alive
)

// StartRingSound loops the ring tone until StopRingSound.
func StartRingSound(wav []byte) error {
	ringMu.Lock()
	defer ringMu.Unlock()
	ringWAV = wav
	const sndAsync, sndMemory, sndLoop, sndNoDefault = 0x1, 0x4, 0x8, 0x2
	r, _, err := pPlaySoundW.Call(uintptr(unsafe.Pointer(&ringWAV[0])), 0, sndAsync|sndMemory|sndLoop|sndNoDefault)
	if r == 0 {
		return fmt.Errorf("PlaySound: %v", err)
	}
	return nil
}

// StopRingSound silences the ring tone.
func StopRingSound() {
	ringMu.Lock()
	defer ringMu.Unlock()
	pPlaySoundW.Call(0, 0, 0)
}

// --- clipboard -------------------------------------------------------------------

const (
	cfUnicodeText = 13
	cfHDROP       = 15
	cfDIB         = 8
	cfDIBV5       = 17
)

// ReadClipboard returns copied files, an image, or text, in that order of preference.
func ReadClipboard() (Clip, error) {
	// the clipboard is opened per thread
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	var c Clip
	opened := false
	for i := 0; i < 10 && !opened; i++ { // another app may hold it for a moment
		r, _, _ := pOpenClipboard.Call(0)
		opened = r != 0
		if !opened {
			windows.SleepEx(50, false)
		}
	}
	if !opened {
		return c, errors.New("the clipboard is busy; try again")
	}
	defer pCloseClipboard.Call()

	avail := func(f uintptr) bool { r, _, _ := pIsClipboardFormatAvailable.Call(f); return r != 0 }
	switch {
	case avail(cfHDROP):
		h, _, _ := pGetClipboardData.Call(cfHDROP)
		if h == 0 {
			break
		}
		n, _, _ := pDragQueryFileW.Call(h, 0xFFFFFFFF, 0, 0)
		for i := uintptr(0); i < n; i++ {
			size, _, _ := pDragQueryFileW.Call(h, i, 0, 0)
			buf := make([]uint16, size+1)
			pDragQueryFileW.Call(h, i, uintptr(unsafe.Pointer(&buf[0])), size+1)
			c.Files = append(c.Files, windows.UTF16ToString(buf))
		}
	case avail(cfDIBV5) || avail(cfDIB):
		format := uintptr(cfDIB)
		if !avail(cfDIB) {
			format = cfDIBV5
		}
		data, err := globalBytes(format)
		if err != nil {
			return c, err
		}
		if c.PNG, err = DIBToPNG(data); err != nil {
			return c, err
		}
	case avail(cfUnicodeText):
		data, err := globalBytes(cfUnicodeText)
		if err != nil {
			return c, err
		}
		u := unsafe.Slice((*uint16)(unsafe.Pointer(&data[0])), len(data)/2)
		c.Text = windows.UTF16ToString(u)
	}
	return c, nil
}

func globalBytes(format uintptr) ([]byte, error) {
	h, _, _ := pGetClipboardData.Call(format)
	if h == 0 {
		return nil, errors.New("couldn't read the clipboard")
	}
	p, _, _ := pGlobalLock.Call(h)
	if p == 0 {
		return nil, errors.New("couldn't lock the clipboard data")
	}
	defer pGlobalUnlock.Call(h)
	size, _, _ := pGlobalSize.Call(h)
	if size < 2 {
		return nil, errors.New("the clipboard is empty")
	}
	// p is memory owned by the clipboard (not Go); copy it out while it's locked
	ptr := *(*unsafe.Pointer)(unsafe.Pointer(&p))
	return append([]byte(nil), unsafe.Slice((*byte)(ptr), size)...), nil
}

// --- file picker -----------------------------------------------------------------

type openFileName struct {
	lStructSize       uint32
	hwndOwner         uintptr
	hInstance         uintptr
	lpstrFilter       *uint16
	lpstrCustomFilter *uint16
	nMaxCustFilter    uint32
	nFilterIndex      uint32
	lpstrFile         *uint16
	nMaxFile          uint32
	lpstrFileTitle    *uint16
	nMaxFileTitle     uint32
	lpstrInitialDir   *uint16
	lpstrTitle        *uint16
	flags             uint32
	nFileOffset       uint16
	nFileExtension    uint16
	lpstrDefExt       *uint16
	lCustData         uintptr
	lpfnHook          uintptr
	lpTemplateName    *uint16
	pvReserved        uintptr
	dwReserved        uint32
	flagsEx           uint32
}

// PickFiles shows the standard Open dialog with multi-select. An empty
// result with no error means the person cancelled.
func PickFiles(title string) ([]string, error) {
	const (
		ofnAllowMultiSelect = 0x200
		ofnExplorer         = 0x80000
		ofnFileMustExist    = 0x1000
		ofnPathMustExist    = 0x800
		ofnNoChangeDir      = 0x8
		ofnHideReadOnly     = 0x4
	)
	// the dialog is COM-based and wants a single-threaded apartment on its thread
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := windows.CoInitializeEx(0, windows.COINIT_APARTMENTTHREADED); err == nil {
		defer windows.CoUninitialize()
	}
	buf := make([]uint16, 1<<16)
	filter := utf16z("All files (*.*)", "*.*")
	t, _ := windows.UTF16PtrFromString(title)
	ofn := openFileName{
		lpstrFilter: &filter[0],
		lpstrFile:   &buf[0],
		nMaxFile:    uint32(len(buf)),
		lpstrTitle:  t,
		flags:       ofnAllowMultiSelect | ofnExplorer | ofnFileMustExist | ofnPathMustExist | ofnNoChangeDir | ofnHideReadOnly,
	}
	ofn.lStructSize = uint32(unsafe.Sizeof(ofn))
	r, _, _ := pGetOpenFileNameW.Call(uintptr(unsafe.Pointer(&ofn)))
	if r == 0 {
		if code, _, _ := pCommDlgExtendedError.Call(); code != 0 {
			return nil, fmt.Errorf("file dialog failed (code %#x)", code)
		}
		return nil, nil // cancelled
	}
	return splitMulti(buf), nil
}

// utf16z builds a double-NUL-terminated list of strings.
func utf16z(parts ...string) []uint16 {
	var out []uint16
	for _, p := range parts {
		out = append(out, windows.StringToUTF16(p)...)
	}
	return append(out, 0)
}

// splitMulti parses the dialog's result: one full path, or a folder then names.
func splitMulti(buf []uint16) []string {
	var parts []string
	start := 0
	for i, c := range buf {
		if c == 0 {
			if i == start {
				break
			}
			parts = append(parts, windows.UTF16ToString(buf[start:i]))
			start = i + 1
		}
	}
	if len(parts) <= 1 {
		return parts
	}
	var out []string
	for _, name := range parts[1:] {
		out = append(out, filepath.Join(parts[0], name))
	}
	return out
}

// --- registry: autostart, URL scheme, notification identity ------------------------

const runKey = `Software\Microsoft\Windows\CurrentVersion\Run`

// SetAutostart adds or removes droplet from the per-user Run key.
func SetAutostart(exe string, on bool) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if on {
		return k.SetStringValue("droplet", `"`+exe+`"`)
	}
	if err := k.DeleteValue("droplet"); err != nil && !errors.Is(err, registry.ErrNotExist) {
		return err
	}
	return nil
}

// Register sets up what toasts need: an identity (name + icon) for the
// notification's header, and the droplet: URL scheme its buttons call.
// All per-user (HKCU); Unregister undoes it.
func Register(exe, iconPath string) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, `Software\Classes\AppUserModelId\`+AppID, registry.SET_VALUE)
	if err != nil {
		return err
	}
	k.SetStringValue("DisplayName", "droplet")
	if iconPath != "" {
		k.SetStringValue("IconUri", iconPath)
	}
	k.SetStringValue("IconBackgroundColor", "FF0F172A")
	k.Close()

	k, _, err = registry.CreateKey(registry.CURRENT_USER, `Software\Classes\`+Scheme, registry.SET_VALUE)
	if err != nil {
		return err
	}
	k.SetStringValue("", "URL:droplet companion")
	k.SetStringValue("URL Protocol", "")
	k.Close()
	k, _, err = registry.CreateKey(registry.CURRENT_USER, `Software\Classes\`+Scheme+`\DefaultIcon`, registry.SET_VALUE)
	if err == nil {
		k.SetStringValue("", `"`+exe+`",0`)
		k.Close()
	}
	k, _, err = registry.CreateKey(registry.CURRENT_USER, `Software\Classes\`+Scheme+`\shell\open\command`, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	return k.SetStringValue("", `"`+exe+`" "%1"`)
}

// Unregister removes everything Register, SetAutostart and the shortcuts added.
func Unregister() error {
	var errs []error
	for _, key := range []string{
		`Software\Classes\` + Scheme + `\shell\open\command`,
		`Software\Classes\` + Scheme + `\shell\open`,
		`Software\Classes\` + Scheme + `\shell`,
		`Software\Classes\` + Scheme + `\DefaultIcon`,
		`Software\Classes\` + Scheme,
		`Software\Classes\AppUserModelId\` + AppID,
	} {
		if err := registry.DeleteKey(registry.CURRENT_USER, key); err != nil && !errors.Is(err, registry.ErrNotExist) {
			errs = append(errs, err)
		}
	}
	errs = append(errs, SetAutostart("", false))
	errs = append(errs, SyncSendTo("", nil))
	if p, err := startMenuShortcut(); err == nil {
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// --- Explorer: Send To and Start menu ----------------------------------------------

// sendToPrefix marks droplet's own Send To shortcuts, so stale ones can be removed.
const sendToPrefix = "droplet → "

func sendToDir() (string, error) {
	appdata := os.Getenv("APPDATA")
	if appdata == "" {
		return "", errors.New("APPDATA isn't set")
	}
	return filepath.Join(appdata, "Microsoft", "Windows", "SendTo"), nil
}

func startMenuShortcut() (string, error) {
	appdata := os.Getenv("APPDATA")
	if appdata == "" {
		return "", errors.New("APPDATA isn't set")
	}
	return filepath.Join(appdata, "Microsoft", "Windows", "Start Menu", "Programs", "droplet.lnk"), nil
}

// SyncSendTo makes Explorer's "Send to" menu hold exactly one droplet entry
// per destination, removing any left over from devices that are gone.
// With no destinations (or exe ""), it removes them all.
func SyncSendTo(exe string, dests []Dest) error {
	dir, err := sendToDir()
	if err != nil {
		return err
	}
	want := map[string]Dest{}
	if exe != "" {
		for _, d := range dests {
			want[strings.ToLower(sendToPrefix+fileSafe(d.Name)+".lnk")] = d
		}
	}
	existing, _ := filepath.Glob(filepath.Join(dir, sendToPrefix+"*.lnk"))
	var errs []error
	for _, p := range existing {
		if _, ok := want[strings.ToLower(filepath.Base(p))]; ok {
			continue
		}
		if err := os.Remove(p); err != nil {
			errs = append(errs, err)
		}
	}
	// existing ones are rewritten too: the exe may have moved since they were made
	for _, d := range want {
		p := filepath.Join(dir, sendToPrefix+fileSafe(d.Name)+".lnk")
		err := CreateShortcut(Shortcut{
			Path:        p,
			Target:      exe,
			Args:        "send --to " + d.ID,
			Description: "Send to " + d.Name + " with droplet",
			Icon:        exe,
		})
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", filepath.Base(p), err))
		}
	}
	return errors.Join(errs...)
}

// EnsureStartMenu adds a "droplet" Start menu entry carrying droplet's
// AppUserModelID: the documented way for a desktop app to own its toasts,
// and a way to find droplet again.
func EnsureStartMenu(exe string) error {
	p, err := startMenuShortcut()
	if err != nil {
		return err
	}
	return CreateShortcut(Shortcut{Path: p, Target: exe, Description: "droplet companion", Icon: exe, AppID: AppID})
}

// fileSafe strips characters Windows won't allow in a file name.
func fileSafe(s string) string {
	s = strings.Map(func(r rune) rune {
		if r < 32 || strings.ContainsRune(`<>:"/\|?*`, r) {
			return '_'
		}
		return r
	}, s)
	return strings.TrimRight(s, ". ")
}
