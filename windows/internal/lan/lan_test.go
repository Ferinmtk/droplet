package lan

import (
	"context"
	"net/netip"
	"os"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const (
	testID = "9b16173d305cd15a"
	testFP = "3c10aa55e1f7b3c2d0a9e8f7061524334251607f8e9dacbbcadbe0f1e2d32094"
)

func TestParseTXT(t *testing.T) {
	txt, err := ParseTXT([]string{
		"id=" + strings.ToUpper(testID), "fp=" + testFP, "name=t15", "http=8000",
		"ts=https://t15.tail7375fe.ts.net/", "ID=ffffffffffffffff", "flag",
	})
	if err != nil {
		t.Fatal(err)
	}
	want := TXT{ID: testID, Fingerprint: testFP, Name: "t15", HTTPPort: 8000, Tailnet: "https://t15.tail7375fe.ts.net"}
	if txt != want {
		t.Fatalf("got %+v\nwant %+v", txt, want)
	}
}

func TestParseTXTOptionalValues(t *testing.T) {
	// an empty ts (no tailnet), a bad port and a non-https tailnet are dropped, not fatal
	for _, extra := range [][]string{
		{"ts=", "http=99999"},
		{"ts=http://t15.example", "http=abc"},
		{"ts=https://user@evil.example/", "http=-1"},
	} {
		txt, err := ParseTXT(append([]string{"id=" + testID, "fp=" + testFP}, extra...))
		if err != nil {
			t.Fatalf("%v: %v", extra, err)
		}
		if txt.Tailnet != "" || txt.HTTPPort != 0 {
			t.Fatalf("%v: kept a bad value: %+v", extra, txt)
		}
	}
	txt, _ := ParseTXT([]string{"id=" + testID, "fp=" + testFP, "name=\x07" + strings.Repeat("x", 100)})
	if strings.ContainsRune(txt.Name, 7) || len(txt.Name) != 63 {
		t.Fatalf("name not cleaned: %q", txt.Name)
	}
}

func TestParseTXTRequired(t *testing.T) {
	for name, strs := range map[string][]string{
		"no id":      {"fp=" + testFP},
		"short id":   {"id=9b16", "fp=" + testFP},
		"hex id":     {"id=zzzzzzzzzzzzzzzz", "fp=" + testFP},
		"no fp":      {"id=" + testID},
		"short fp":   {"id=" + testID, "fp=abcd"},
		"non-hex fp": {"id=" + testID, "fp=" + strings.Repeat("x", 64)},
		"empty":      nil,
	} {
		if _, err := ParseTXT(strs); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

// response builds an mDNS answer the way python-zeroconf sends one: the PTR
// as the answer, SRV, TXT and A as additionals.
type rec struct {
	name string
	body dnsmessage.ResourceBody
	ttl  uint32
}

func response(t *testing.T, answers, additionals []rec) []byte {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{Response: true, Authoritative: true})
	b.EnableCompression()
	add := func(rs []rec) {
		for _, r := range rs {
			ttl := r.ttl
			if ttl == 0 {
				ttl = 120
			}
			if ttl == 1 {
				ttl = 0 // goodbye
			}
			h := dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName(r.name), Class: dnsmessage.ClassINET, TTL: ttl}
			var err error
			switch body := r.body.(type) {
			case *dnsmessage.PTRResource:
				err = b.PTRResource(h, *body)
			case *dnsmessage.SRVResource:
				err = b.SRVResource(h, *body)
			case *dnsmessage.TXTResource:
				err = b.TXTResource(h, *body)
			case *dnsmessage.AResource:
				err = b.AResource(h, *body)
			}
			if err != nil {
				t.Fatal(err)
			}
		}
	}
	b.StartAnswers()
	add(answers)
	b.StartAdditionals()
	add(additionals)
	msg, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return msg
}

const inst = "t15-9b1617._droplet._tcp.local."

func ptr() rec {
	return rec{ServiceType, &dnsmessage.PTRResource{PTR: dnsmessage.MustNewName(inst)}, 0}
}
func srv(port uint16) rec {
	return rec{inst, &dnsmessage.SRVResource{Target: dnsmessage.MustNewName("t15.local."), Port: port}, 0}
}
func txtRec(strs ...string) rec { return rec{inst, &dnsmessage.TXTResource{TXT: strs}, 0} }
func aRec(ip string) rec {
	return rec{"t15.local.", &dnsmessage.AResource{A: netip.MustParseAddr(ip).As4()}, 0}
}

var goodTXT = []string{"id=" + testID, "fp=" + testFP, "name=t15", "http=8000", "ts="}

func TestCollectorFullAnswer(t *testing.T) {
	c := newCollector()
	src := netip.MustParseAddr("192.168.100.20")
	c.add(response(t, []rec{ptr()}, []rec{srv(8443), txtRec(goodTXT...), aRec("192.168.100.20")}), src)
	hubs := c.complete()
	if len(hubs) != 1 {
		t.Fatalf("hubs: %+v", hubs)
	}
	h := hubs[0]
	if h.Instance != "t15-9b1617" || h.ID != testID || h.Fingerprint != testFP || h.Port != 8443 || h.HTTPPort != 8000 {
		t.Fatalf("hub: %+v", h)
	}
	if got := h.Endpoints(); len(got) != 1 || got[0] != "192.168.100.20:8443" {
		t.Fatalf("endpoints: %v", got)
	}
}

func TestCollectorPiecemealAndFollowUps(t *testing.T) {
	c := newCollector()
	src := netip.MustParseAddr("192.168.100.20")
	c.add(response(t, []rec{ptr()}, nil), src)
	if len(c.complete()) != 0 {
		t.Fatal("complete without SRV/TXT")
	}
	// the next query asks for what's missing
	q := questions(t, c)
	if !q["SRV "+inst] || !q["TXT "+inst] || !q["PTR "+ServiceType] {
		t.Fatalf("follow-up questions: %v", q)
	}
	c.add(response(t, []rec{srv(8443)}, nil), src)
	if q := questions(t, c); !q["A t15.local."] {
		t.Fatalf("no A question for the target: %v", q)
	}
	c.add(response(t, []rec{txtRec(goodTXT...)}, nil), src)
	// no A record yet: the answer's source address stands in
	hubs := c.complete()
	if len(hubs) != 1 || hubs[0].Addrs[0] != src {
		t.Fatalf("hubs: %+v", hubs)
	}
	c.add(response(t, []rec{aRec("192.168.100.21")}, nil), src)
	if hubs := c.complete(); hubs[0].Addrs[0] != netip.MustParseAddr("192.168.100.21") {
		t.Fatalf("A record not preferred: %+v", hubs[0].Addrs)
	}
}

func TestCollectorIgnoresJunk(t *testing.T) {
	c := newCollector()
	src := netip.MustParseAddr("192.168.100.20")
	c.add([]byte("not dns"), src)
	// a query, not a response
	q, _ := newCollector().query()
	c.add(q, src)
	// another service type's records
	other := "printer._ipp._tcp.local."
	c.add(response(t, []rec{{"_ipp._tcp.local.", &dnsmessage.PTRResource{PTR: dnsmessage.MustNewName(other)}, 0}},
		[]rec{{other, &dnsmessage.TXTResource{TXT: goodTXT}, 0}}), src)
	// our service with a bad TXT
	c.add(response(t, []rec{ptr()}, []rec{srv(8443), txtRec("id=nope"), aRec("192.168.100.20")}), src)
	if hubs := c.complete(); len(hubs) != 0 {
		t.Fatalf("junk became hubs: %+v", hubs)
	}
	// a goodbye (TTL 0) doesn't add anything
	c2 := newCollector()
	g := ptr()
	g.ttl = 1
	c2.add(response(t, []rec{g}, nil), src)
	if len(c2.instances()) != 0 {
		t.Fatal("goodbye recorded")
	}
}

func questions(t *testing.T, c *collector) map[string]bool {
	t.Helper()
	msg, err := c.query()
	if err != nil {
		t.Fatal(err)
	}
	var p dnsmessage.Parser
	if _, err := p.Start(msg); err != nil {
		t.Fatal(err)
	}
	qs, err := p.AllQuestions()
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]bool{}
	for _, q := range qs {
		out[strings.TrimPrefix(q.Type.String(), "Type")+" "+q.Name.String()] = true
	}
	return out
}

// TestLiveBrowse looks at the real network. DROPLET_MDNS_LIVE=1 to run.
func TestLiveBrowse(t *testing.T) {
	if os.Getenv("DROPLET_MDNS_LIVE") == "" {
		t.Skip("set DROPLET_MDNS_LIVE=1 to browse the real LAN")
	}
	start := time.Now()
	hubs, err := BrowseAll(context.Background(), 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	for _, h := range hubs {
		t.Logf("found %s fp=%s… ts=%q", h, h.Fingerprint[:12], h.Tailnet)
	}
	t.Logf("%d hub(s) in %s", len(hubs), time.Since(start).Round(time.Millisecond))
	if len(hubs) == 0 {
		t.Fatal("no hubs found")
	}
}
