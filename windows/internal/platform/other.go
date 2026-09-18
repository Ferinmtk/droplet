//go:build !windows

package platform

import (
	"errors"
	"fmt"
	"os"
	"time"
)

// Stand-ins for non-Windows builds: the CLI and the headless agent run here
// (for development and tests); desktop effects are printed instead.

var errWindowsOnly = errors.New("only available on Windows")

func SetActionKey(string) {}

func Notify(n Notification) {
	fmt.Fprintf(os.Stderr, "[notify] %s — %s\n", n.Title, n.Body)
}

func ClearNotification(string) {}

func FlushNotifications(time.Duration) {}

func MessageBox(title, text string, _ bool) { fmt.Fprintf(os.Stderr, "%s: %s\n", title, text) }

func AttachConsole() bool { return true }

func FirstInstance() bool { return true }

func OpenURL(u string) error { fmt.Fprintf(os.Stderr, "[open] %s\n", u); return nil }

func OpenPath(p string) error { fmt.Fprintf(os.Stderr, "[open] %s\n", p); return nil }

func ShowInFolder(p string) error { fmt.Fprintf(os.Stderr, "[show] %s\n", p); return nil }

// StartRingSound only reports: no sound off Windows.
func StartRingSound([]byte) error { fmt.Fprintln(os.Stderr, "[ring] sound on"); return nil }

func StopRingSound() { fmt.Fprintln(os.Stderr, "[ring] sound off") }

func ReadClipboard() (Clip, error) { return Clip{}, errWindowsOnly }

func PickFiles(string) ([]string, error) { return nil, errWindowsOnly }

func SetAutostart(string, bool) error { return nil }

func Register(string, string) error { return nil }

func Unregister() error { return nil }

func SyncSendTo(string, []Dest) error { return nil }

func EnsureStartMenu(string) error { return nil }
