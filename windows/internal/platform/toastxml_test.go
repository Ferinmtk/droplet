package platform

import (
	"encoding/xml"
	"io"
	"strings"
	"testing"
	"time"
	"unicode/utf16"
)

// wellFormed parses s as XML, as Windows will before showing it.
func wellFormed(t *testing.T, s string) {
	t.Helper()
	d := xml.NewDecoder(strings.NewReader(s))
	for {
		_, err := d.Token()
		if err == io.EOF {
			return
		}
		if err != nil {
			t.Fatalf("not well-formed XML (%v):\n%s", err, s)
		}
	}
}

func TestToastXMLPlain(t *testing.T) {
	got := toastXML(Notification{Title: "report.pdf", Body: "from phone"}, "k")
	want := `<toast><visual><binding template="ToastGeneric"><text hint-maxLines="1">report.pdf</text>` +
		`<text>from phone</text></binding></visual></toast>`
	if got != want {
		t.Fatalf("got  %s\nwant %s", got, want)
	}
	wellFormed(t, got)
}

func TestToastXMLRingWithButtons(t *testing.T) {
	n := Notification{
		Title:   "phone is ringing",
		Urgent:  true,
		Click:   &Action{Kind: ActOpenURL, Arg: "https://hub/?a=1&b=2"},
		Buttons: []Action{{Label: "Stop ringing", Kind: ActStopRing}, {Label: "Open", Kind: ActOpenFolder, Arg: `C:\Users\m\Downloads\droplet`}},
	}
	got := toastXML(n, "secret")
	wellFormed(t, got)
	for _, want := range []string{
		`<toast activationType="protocol" launch="https://hub/?a=1&amp;b=2" scenario="incomingCall">`,
		`<action content="Stop ringing" activationType="protocol" arguments="droplet:stop-ring?k=secret"/>`,
		`arguments="droplet:folder?k=secret&amp;p=C%3A%5CUsers%5Cm%5CDownloads%5Cdroplet"`,
		`<audio silent="true"/></toast>`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %s in\n%s", want, got)
		}
	}
	// the button's link must come back out of the XML as droplet can parse it
	var doc struct {
		Actions []struct {
			Arguments string `xml:"arguments,attr"`
		} `xml:"actions>action"`
	}
	if err := xml.Unmarshal([]byte(got), &doc); err != nil || len(doc.Actions) != 2 {
		t.Fatalf("%v %+v", err, doc)
	}
	if a, ok := ParseActionURL(doc.Actions[1].Arguments, "secret"); !ok || a.Arg != `C:\Users\m\Downloads\droplet` {
		t.Fatalf("%q -> %+v %v", doc.Actions[1].Arguments, a, ok)
	}
}

func TestToastXMLEscapesAndDropsControlCharacters(t *testing.T) {
	body := "a<b>&\"c\" 'd' \x00\x01\x1b\ufffe ok\ttab\nline 😀 \xff"
	got := toastXML(Notification{Title: "</text><x>", Body: body}, "k")
	wellFormed(t, got)
	var doc struct {
		Texts []string `xml:"visual>binding>text"`
	}
	if err := xml.Unmarshal([]byte(got), &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Texts) != 2 || doc.Texts[0] != "</text><x>" {
		t.Fatalf("%q", doc.Texts)
	}
	if want := "a<b>&\"c\" 'd'  ok\ttab\nline 😀 \ufffd"; doc.Texts[1] != want {
		t.Fatalf("body %q, want %q", doc.Texts[1], want)
	}
}

func TestToastXMLClipsLongText(t *testing.T) {
	got := toastXML(Notification{Title: strings.Repeat("é", 500), Body: strings.Repeat("b", 1000)}, "k")
	var doc struct {
		Texts []string `xml:"visual>binding>text"`
	}
	if err := xml.Unmarshal([]byte(got), &doc); err != nil {
		t.Fatal(err)
	}
	if n := len([]rune(doc.Texts[0])); n != 120 || !strings.HasSuffix(doc.Texts[0], "…") {
		t.Fatalf("title %d runes", n)
	}
	if n := len([]rune(doc.Texts[1])); n != 400 {
		t.Fatalf("body %d runes", n)
	}
}

func TestToastTag(t *testing.T) {
	if toastTag("ring") != "ring" || toastTag("") != "" {
		t.Fatal("short tags stay as they are")
	}
	long := strings.Repeat("x", 100)
	if got := toastTag(long); got != long[:60] {
		t.Fatalf("%q", got)
	}
	// counted in UTF-16 units, cut between characters
	emoji := strings.Repeat("😀", 40) // 80 units
	got := toastTag(emoji)
	if n := len(utf16.Encode([]rune(got))); n != 60 || got != strings.Repeat("😀", 30) {
		t.Fatalf("%d units: %q", n, got)
	}
}

func TestPlaybackStatus(t *testing.T) {
	for v, want := range map[int32]string{0: "Closed", 3: "Stopped", 4: "Playing", 5: "Paused", 42: "Stopped"} {
		if got := playbackStatus(v); got != want {
			t.Errorf("%d: %s, want %s", v, got, want)
		}
	}
}

func TestTimeline(t *testing.T) {
	const s = 10_000_000 // ticks per second
	now := time.Date(2026, 9, 18, 12, 0, 0, 0, time.UTC)
	ticks := func(tm time.Time) int64 { return tm.UnixNano()/100 + 116444736000000000 }

	l, p := timeline(5*s, 205*s, 17*s, ticks(now.Add(-3*time.Second)), false, now)
	if l != 200 || p != 12 {
		t.Fatalf("paused: %v %v", l, p)
	}
	// playing: moved on by the 3 s since the app reported it
	if _, p := timeline(5*s, 205*s, 17*s, ticks(now.Add(-3*time.Second)), true, now); p != 15 {
		t.Fatalf("playing: %v", p)
	}
	// an unset or nonsense update time doesn't move it (nor overflow)
	for _, u := range []int64{0, 1, ticks(time.Date(1999, 1, 1, 0, 0, 0, 0, time.UTC)), ticks(now.Add(time.Hour)), 1<<63 - 1} {
		if _, p := timeline(0, 100*s, 7*s, u, true, now); p != 7 {
			t.Fatalf("update %d: %v", u, p)
		}
	}
}
