package poll

import (
	"testing"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/hub"
)

func TestInboxAnnouncesOnce(t *testing.T) {
	now := time.Now()
	files := []hub.File{
		{Name: "b.pdf", Mtime: now.Unix() - 5, From: "slim"},
		{Name: "a.jpg", Mtime: now.Unix() - 10, From: "slim"},
	}
	fresh, seen := Inbox(files, nil, now)
	if len(fresh) != 2 || fresh[0].Name != "a.jpg" {
		t.Fatalf("first look should announce both, oldest first: %+v", fresh)
	}
	fresh, seen = Inbox(files, seen, now)
	if len(fresh) != 0 {
		t.Fatalf("second look should announce nothing: %+v", fresh)
	}
	// a new file arrives; a.jpg is gone
	files = []hub.File{files[0], {Name: "c.txt", Mtime: now.Unix(), From: "phone"}}
	fresh, seen = Inbox(files, seen, now)
	if len(fresh) != 1 || fresh[0].Name != "c.txt" {
		t.Fatalf("only c.txt is new: %+v", fresh)
	}
	if len(seen) != 2 {
		t.Fatalf("seen should drop items no longer in the inbox: %v", seen)
	}
	// same name uploaded again later counts as new
	files = []hub.File{{Name: "c.txt", Mtime: now.Unix() + 100, From: "phone"}}
	fresh, _ = Inbox(files, seen, now)
	if len(fresh) != 1 {
		t.Fatal("a re-sent file with the same name is a new item")
	}
}

func TestInboxWaitsForCompleteUploads(t *testing.T) {
	now := time.Now()
	uploading := hub.File{Name: "big.iso", Mtime: now.Unix()} // no sender label yet
	fresh, seen := Inbox([]hub.File{uploading}, nil, now)
	if len(fresh) != 0 || len(seen) != 0 {
		t.Fatal("an unlabelled fresh file may still be uploading")
	}
	if !Complete(uploading, now.Add(2*time.Minute)) {
		t.Fatal("an unlabelled file that's been still for a while counts as complete")
	}
	uploading.From = "slim"
	if fresh, _ := Inbox([]hub.File{uploading}, seen, now); len(fresh) != 1 {
		t.Fatal("once labelled it should be announced")
	}
}

func TestMessages(t *testing.T) {
	thread := []hub.Message{
		{ID: "1", From: "p", Text: "old", TS: 100},
		{ID: "2", From: "me", Text: "reply", TS: 101},
		{ID: "3", From: "p", Text: "hi", TS: 102},
		{ID: "4", From: "p", Text: "there", TS: 103},
	}
	// hub says 2 unread: only the last two from p
	fresh, seen := Messages(thread, "p", 2, 0)
	if len(fresh) != 2 || fresh[0].Text != "hi" || fresh[1].Text != "there" || seen != 103 {
		t.Fatalf("got %+v seen=%v", fresh, seen)
	}
	// same thread again (e.g. the web app hadn't marked it read): nothing new
	fresh, seen = Messages(thread, "p", 2, seen)
	if len(fresh) != 0 || seen != 103 {
		t.Fatalf("repeat should be silent: %+v", fresh)
	}
	thread = append(thread, hub.Message{ID: "5", From: "p", Text: "again", TS: 104})
	fresh, _ = Messages(thread, "p", 3, seen)
	if len(fresh) != 1 || fresh[0].Text != "again" {
		t.Fatalf("only the new one: %+v", fresh)
	}
}

func TestSameDevices(t *testing.T) {
	a := []hub.Device{{ID: "1", Name: "x"}}
	if !SameDevices(a, []hub.Device{{ID: "1", Name: "x", Online: true}}) {
		t.Fatal("online changes don't need a rebuild")
	}
	if SameDevices(a, []hub.Device{{ID: "1", Name: "y"}}) {
		t.Fatal("renames do")
	}
}
