package platform

import (
	"fmt"
	"log"
	"sync"
	"syscall"
	"time"
	"unsafe"

	ole "github.com/go-ole/go-ole"
)

// Toasts go straight to Windows' notification API
// (Windows.UI.Notifications, a WinRT API) from one worker goroutine. They're
// shown under droplet's AppUserModelID, registered by Register, which gives
// them droplet's name and icon.
//
// If notifications can't be shown (an old or stripped-down Windows, or
// Wine), the error is logged and droplet carries on without them.

var (
	iidIToastNotificationManagerStatics  = ole.NewGUID("{50AC103F-D235-4598-BBEF-98FE4D1A3AD4}")
	iidIToastNotificationManagerStatics2 = ole.NewGUID("{7AB93C52-0E48-4750-BA9D-1A4113981847}")
	iidIToastNotificationFactory         = ole.NewGUID("{04124B20-82C6-4229-B109-FD9ED4662B53}")
	iidIToastNotification2               = ole.NewGUID("{9DFB9FD1-143A-490E-90BF-B9FBA7132DE7}")
	iidIXmlDocument                      = ole.NewGUID("{F7F3A506-1E87-42D6-BCFB-B8C809FA5494}")
	iidIXmlDocumentIO                    = ole.NewGUID("{6CD0E74E-EE65-4489-9EBF-CA43E87BA637}")
)

const toastGroup = "droplet"

type toastJob struct {
	what string
	run  func(*toaster) error
}

var (
	toastQueue   = make(chan toastJob, 32)
	toastPending sync.WaitGroup
	toastStart   sync.Once
	actionKey    string
)

// SetActionKey sets the secret put into droplet: links (see ActionURL).
func SetActionKey(k string) { actionKey = k }

// Notify shows a toast. It never blocks; if toasts pile up, extras are dropped.
func Notify(n Notification) {
	enqueue(toastJob{n.Title, func(t *toaster) error { return t.show(n) }})
}

// ClearNotification removes a toast (by tag) from the screen and Action Center.
func ClearNotification(tag string) {
	tag = toastTag(tag)
	if tag == "" {
		return
	}
	enqueue(toastJob{"clear " + tag, func(t *toaster) error { return t.remove(tag) }})
}

func enqueue(j toastJob) {
	toastStart.Do(func() { go toastWorker() })
	toastPending.Add(1)
	select {
	case toastQueue <- j:
	default:
		toastPending.Done()
		log.Printf("toast dropped (queue full): %s", j.what)
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

func toastWorker() {
	// WinRT objects are tied to the apartment they were made in: this
	// goroutine keeps its thread, and does all the notification work
	initErr := startWinRT()
	t := &toaster{}
	for j := range toastQueue {
		err := initErr
		if err == nil {
			err = t.safely(j)
		}
		if err != nil {
			log.Printf("toast (%s): %v", j.what, err)
		}
		toastPending.Done()
	}
}

// toaster holds what every toast needs, made on first use and kept.
type toaster struct {
	notifier comPtr // IToastNotifier for droplet's AppUserModelID
	factory  comPtr // IToastNotificationFactory
	history  comPtr // IToastNotificationHistory
}

// safely runs a job, turning a Go panic into an error: a notification
// that fails must not take droplet down with it.
func (t *toaster) safely(j toastJob) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic: %v", r)
		}
	}()
	return j.run(t)
}

func (t *toaster) ready() error {
	if t.notifier.p != nil {
		return nil
	}
	statics, err := factory("Windows.UI.Notifications.ToastNotificationManager", iidIToastNotificationManagerStatics)
	if err != nil {
		return err
	}
	defer statics.release()
	id, err := newHString(AppID)
	if err != nil {
		return err
	}
	defer id.free()
	var notifier comPtr
	// IToastNotificationManagerStatics: CreateToastNotifier 6, CreateToastNotifierWithId 7
	hr, _, _ := syscall.SyscallN(statics.method(7), statics.this(), uintptr(id), uintptr(unsafe.Pointer(&notifier.p)))
	if err := check(hr, "CreateToastNotifierWithId"); err != nil {
		return err
	}
	f, err := factory("Windows.UI.Notifications.ToastNotification", iidIToastNotificationFactory)
	if err != nil {
		notifier.release()
		return err
	}
	t.notifier, t.factory = notifier, f
	return nil
}

func (t *toaster) show(n Notification) error {
	if err := t.ready(); err != nil {
		return err
	}
	doc, err := activate("Windows.Data.Xml.Dom.XmlDocument")
	if err != nil {
		return err
	}
	defer doc.release()
	docIO, err := doc.query(iidIXmlDocumentIO)
	if err != nil {
		return err
	}
	defer docIO.release()
	x, err := newHString(toastXML(n, actionKey))
	if err != nil {
		return err
	}
	defer x.free()
	// IXmlDocumentIO: LoadXml 6
	hr, _, _ := syscall.SyscallN(docIO.method(6), docIO.this(), uintptr(x))
	if err := check(hr, "LoadXml"); err != nil {
		return err
	}
	xdoc, err := doc.query(iidIXmlDocument)
	if err != nil {
		return err
	}
	defer xdoc.release()

	var toast comPtr
	// IToastNotificationFactory: CreateToastNotification 6
	hr, _, _ = syscall.SyscallN(t.factory.method(6), t.factory.this(), xdoc.this(), uintptr(unsafe.Pointer(&toast.p)))
	if err := check(hr, "CreateToastNotification"); err != nil {
		return err
	}
	defer toast.release()

	if tag := toastTag(n.Tag); tag != "" {
		if err := setTag(toast, tag); err != nil {
			return err
		}
	}
	// IToastNotifier: Show 6
	hr, _, _ = syscall.SyscallN(t.notifier.method(6), t.notifier.this(), toast.this())
	return check(hr, "Show")
}

// setTag gives a toast a tag and droplet's group, so a later one with the
// same tag replaces it and ClearNotification can find it.
func setTag(toast comPtr, tag string) error {
	t2, err := toast.query(iidIToastNotification2)
	if err != nil {
		return err
	}
	defer t2.release()
	ht, err := newHString(tag)
	if err != nil {
		return err
	}
	defer ht.free()
	hg, err := newHString(toastGroup)
	if err != nil {
		return err
	}
	defer hg.free()
	// IToastNotification2: put_Tag 6, get_Tag 7, put_Group 8
	hr, _, _ := syscall.SyscallN(t2.method(6), t2.this(), uintptr(ht))
	if err := check(hr, "put_Tag"); err != nil {
		return err
	}
	hr, _, _ = syscall.SyscallN(t2.method(8), t2.this(), uintptr(hg))
	return check(hr, "put_Group")
}

func (t *toaster) remove(tag string) error {
	if t.history.p == nil {
		statics2, err := factory("Windows.UI.Notifications.ToastNotificationManager", iidIToastNotificationManagerStatics2)
		if err != nil {
			return err
		}
		defer statics2.release()
		// IToastNotificationManagerStatics2: get_History 6
		if t.history, err = statics2.getObj(6, "History", false); err != nil {
			return err
		}
	}
	ht, err := newHString(tag)
	if err != nil {
		return err
	}
	defer ht.free()
	hg, err := newHString(toastGroup)
	if err != nil {
		return err
	}
	defer hg.free()
	id, err := newHString(AppID)
	if err != nil {
		return err
	}
	defer id.free()
	// IToastNotificationHistory: RemoveGroupedTagWithId(tag, group, appId) 8
	hr, _, _ := syscall.SyscallN(t.history.method(8), t.history.this(), uintptr(ht), uintptr(hg), uintptr(id))
	return check(hr, "RemoveGroupedTagWithId")
}
