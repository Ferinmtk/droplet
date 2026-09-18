package platform

import (
	"encoding/base64"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
)

// Toasts are shown through Windows PowerShell 5.1, which ships with every
// Windows 10/11 and can reach the WinRT notification API without cgo or COM
// activation plumbing. Each toast costs a hidden powershell.exe for ~half a
// second, which is fine at droplet's pace. Clicks and buttons use protocol
// activation (https: links, or droplet: links handled by a new droplet.exe),
// so nothing needs to stay registered as a COM server.

var (
	toastQueue   = make(chan string, 32)
	toastPending sync.WaitGroup
	actionKey    string
)

// SetActionKey sets the secret put into droplet: links (see ActionURL).
func SetActionKey(k string) { actionKey = k }

func init() {
	go func() {
		for script := range toastQueue {
			if err := runPowerShell(script); err != nil {
				log.Printf("toast: %v", err)
			}
			toastPending.Done()
		}
	}()
}

// Notify shows a toast. It never blocks; if toasts pile up, extras are dropped.
func Notify(n Notification) {
	enqueue(toastScript(n), n.Title)
}

func enqueue(script, what string) {
	toastPending.Add(1)
	select {
	case toastQueue <- script:
	default:
		toastPending.Done()
		log.Printf("toast dropped (queue full): %s", what)
	}
}

// FlushNotifications waits (up to timeout) for queued toasts to be shown,
// for short-lived processes that must not exit first.
func FlushNotifications(timeout time.Duration) {
	done := make(chan struct{})
	go func() { toastPending.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(timeout):
	}
}

// ClearNotification removes a toast (by tag) from the screen and Action Center.
func ClearNotification(tag string) {
	script := fmt.Sprintf(`[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null
[Windows.UI.Notifications.ToastNotificationManager]::History.Remove(%s, 'droplet', %s)`, psQuote(tagOf(tag)), psQuote(AppID))
	enqueue(script, "clear "+tag)
}

func tagOf(t string) string {
	if t == "" {
		return ""
	}
	if len(t) > 60 { // WinRT caps tags at 64 characters
		t = t[:60]
	}
	return t
}

func toastScript(n Notification) string {
	var x strings.Builder
	x.WriteString(`<toast`)
	if n.Click != nil {
		fmt.Fprintf(&x, ` activationType="protocol" launch="%s"`, xmlEsc(ActionURL(actionKey, *n.Click)))
	}
	if n.Urgent {
		x.WriteString(` scenario="incomingCall"`)
	}
	x.WriteString(`><visual><binding template="ToastGeneric">`)
	fmt.Fprintf(&x, `<text hint-maxLines="1">%s</text>`, xmlEsc(clip(n.Title, 120)))
	if n.Body != "" {
		fmt.Fprintf(&x, `<text>%s</text>`, xmlEsc(clip(n.Body, 400)))
	}
	x.WriteString(`</binding></visual>`)
	if len(n.Buttons) > 0 {
		x.WriteString(`<actions>`)
		for _, b := range n.Buttons {
			fmt.Fprintf(&x, `<action content="%s" activationType="protocol" arguments="%s"/>`,
				xmlEsc(b.Label), xmlEsc(ActionURL(actionKey, b)))
		}
		x.WriteString(`</actions>`)
	}
	// rings have their own (louder, looping) sound; the rest keep the default chime
	if n.Urgent {
		x.WriteString(`<audio silent="true"/>`)
	}
	x.WriteString(`</toast>`)

	var s strings.Builder
	s.WriteString("$ErrorActionPreference = 'Stop'\n")
	s.WriteString("[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null\n")
	s.WriteString("[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > $null\n")
	s.WriteString("$x = New-Object Windows.Data.Xml.Dom.XmlDocument\n")
	fmt.Fprintf(&s, "$x.LoadXml(%s)\n", psQuote(x.String()))
	s.WriteString("$t = New-Object Windows.UI.Notifications.ToastNotification $x\n")
	if tag := tagOf(n.Tag); tag != "" {
		fmt.Fprintf(&s, "$t.Tag = %s\n$t.Group = 'droplet'\n", psQuote(tag))
	}
	fmt.Fprintf(&s, "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(%s).Show($t)\n", psQuote(AppID))
	return s.String()
}

func clip(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n-1]) + "…"
	}
	return s
}

func xmlEsc(s string) string {
	// curly quotes become character references too: PowerShell treats them as
	// quote marks, and the XML travels inside a single-quoted PowerShell string
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&apos;",
		"\u2018", "&#x2018;", "\u2019", "&#x2019;", "\u201A", "&#x201A;", "\u201B", "&#x201B;").Replace(s)
}

// psQuote makes a PowerShell single-quoted string literal (no interpolation).
func psQuote(s string) string {
	// PowerShell also ends single-quoted strings at curly single quotes; double them all
	return "'" + strings.NewReplacer("'", "''", "\u2018", "\u2018\u2018", "\u2019", "\u2019\u2019",
		"\u201A", "\u201A\u201A", "\u201B", "\u201B\u201B").Replace(s) + "'"
}

func powershellPath() string {
	root := os.Getenv("SystemRoot")
	if root == "" {
		root = `C:\Windows`
	}
	p := filepath.Join(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
	if _, err := os.Stat(p); err == nil {
		return p
	}
	return "powershell.exe"
}

func runPowerShell(script string) error {
	// -EncodedCommand takes base64 UTF-16LE, which sidesteps all quoting of the script
	u := utf16.Encode([]rune(script))
	b := make([]byte, len(u)*2)
	for i, c := range u {
		b[2*i], b[2*i+1] = byte(c), byte(c>>8)
	}
	cmd := exec.Command(powershellPath(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
		"-WindowStyle", "Hidden", "-EncodedCommand", base64.StdEncoding.EncodeToString(b))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

const createNoWindow = 0x08000000
