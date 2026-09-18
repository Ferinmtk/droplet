// Package lan finds droplet hubs on the local network over mDNS/DNS-SD
// (docs/local-first.md §1): service _droplet._tcp, whose TXT records carry
// the hub's id, certificate fingerprint, name, plain-HTTP port and tailnet URL.
package lan

import (
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"unicode"

	"github.com/Ferinmtk/droplet/windows/internal/pin"
)

// TXT is a hub announcement's TXT record, validated.
type TXT struct {
	ID          string // 16 lowercase hex characters
	Fingerprint string // SHA-256 of the LAN certificate, 64 lowercase hex characters
	Name        string // the hub machine's name, e.g. "t15"; may be empty
	HTTPPort    int    // plain-HTTP port for browsers; 0 if absent
	Tailnet     string // https URL on the tailnet, or ""
}

// ParseTXT reads DNS-SD TXT strings ("key=value", RFC 6763 §6). Keys are
// case-insensitive and only a key's first occurrence counts. A record
// without a valid id and fingerprint is unusable and is an error; the other
// keys are optional, and a malformed optional value is dropped rather than
// failing the whole announcement.
func ParseTXT(strs []string) (TXT, error) {
	kv := map[string]string{}
	for _, s := range strs {
		k, v, ok := strings.Cut(s, "=")
		k = strings.ToLower(k)
		if !ok || k == "" {
			continue // a boolean attribute, or junk: nothing droplet uses
		}
		if _, dup := kv[k]; !dup {
			kv[k] = v
		}
	}
	var t TXT
	id := strings.ToLower(strings.TrimSpace(kv["id"]))
	if !validID(id) {
		return TXT{}, fmt.Errorf("announcement has no valid hub id (%q)", kv["id"])
	}
	t.ID = id
	fp, ok := pin.Normalize(kv["fp"])
	if !ok {
		return TXT{}, errors.New("announcement has no valid certificate fingerprint")
	}
	t.Fingerprint = fp
	t.Name = cleanName(kv["name"])
	if p, err := strconv.Atoi(strings.TrimSpace(kv["http"])); err == nil && p > 0 && p < 65536 {
		t.HTTPPort = p
	}
	t.Tailnet = cleanTailnet(kv["ts"])
	return t, nil
}

func validID(id string) bool {
	if len(id) != 16 {
		return false
	}
	for _, r := range id {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return false
		}
	}
	return true
}

// cleanName keeps a name printable and short; it's shown in the UI.
func cleanName(s string) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, strings.TrimSpace(s))
	if r := []rune(s); len(r) > 63 {
		s = string(r[:63])
	}
	return s
}

// cleanTailnet accepts only an https URL with a host and nothing else.
func cleanTailnet(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	u, err := url.Parse(s)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return ""
	}
	u.Path = strings.TrimRight(u.Path, "/")
	return u.String()
}
