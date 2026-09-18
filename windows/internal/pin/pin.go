// Package pin trusts the hub's self-signed LAN certificate by its
// fingerprint (docs/local-first.md §2): a TLS connection is accepted if and
// only if the server's leaf certificate hashes (SHA-256 over its DER
// encoding) to the pinned value. Hostname and CA checks are skipped for this
// connection only, which is why nothing outside this package ever sets
// InsecureSkipVerify; the tailnet route keeps Go's normal verification.
package pin

import (
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"
)

// MismatchError is a certificate that isn't the pinned one: either not our
// hub, or the hub's certificate was regenerated. It is never retried with
// the new certificate by itself; the person has to re-pair.
type MismatchError struct {
	Want string // the pinned fingerprint
	Got  string // what the server presented ("" when it presented nothing)
}

func (e *MismatchError) Error() string {
	if e.Got == "" {
		return "the hub presented no certificate"
	}
	return fmt.Sprintf("the hub's identity changed: its certificate is %s…, not the %s… it was paired with",
		short(e.Got), short(e.Want))
}

func short(fp string) string {
	if len(fp) > 12 {
		return fp[:12]
	}
	return fp
}

// ErrNoPin is returned for a connection attempted without a valid pin.
// An empty pin must never mean "accept anything".
var ErrNoPin = errors.New("no certificate fingerprint to check the hub against")

// Fingerprint is the SHA-256 of a DER certificate, as lowercase hex.
func Fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

// Normalize accepts a fingerprint as lowercase or uppercase hex, with or
// without colons, and returns it as 64 lowercase hex characters. ok is false
// for anything else.
func Normalize(fp string) (string, bool) {
	s := strings.ToLower(strings.ReplaceAll(strings.TrimSpace(fp), ":", ""))
	if len(s) != 64 {
		return "", false
	}
	if _, err := hex.DecodeString(s); err != nil {
		return "", false
	}
	return s, true
}

// Check compares a presented leaf certificate (DER) with the pin.
func Check(want string, leaf []byte) error {
	w, ok := Normalize(want)
	if !ok {
		return ErrNoPin
	}
	if len(leaf) == 0 {
		return &MismatchError{Want: w}
	}
	got := Fingerprint(leaf)
	if subtle.ConstantTimeCompare([]byte(got), []byte(w)) != 1 {
		return &MismatchError{Want: w, Got: got}
	}
	return nil
}

// TLSConfig is the client configuration for a pinned connection. Normal
// verification is off (the certificate is self-signed and its name is an IP
// that changes), and replaced by the fingerprint check, which runs on every
// handshake:
//   - VerifyPeerCertificate sees the raw certificates of a full handshake;
//   - VerifyConnection also runs on resumed sessions, where the former
//     doesn't. No session cache is set, so there are none, but the check
//     mustn't depend on that.
func TLSConfig(want string) *tls.Config {
	return &tls.Config{
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: true, // replaced by the pin check below, never on its own
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return Check(want, nil)
			}
			return Check(want, raw[0])
		},
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) == 0 {
				return Check(want, nil)
			}
			return Check(want, cs.PeerCertificates[0].Raw)
		},
		// the hub speaks HTTP/1.1; saying so keeps WebSocket upgrades working
		NextProtos: []string{"http/1.1"},
	}
}

// Transport is an HTTP transport for the hub on the LAN, pinned to want.
// It never uses a proxy: the hub is on the local network.
func Transport(want string) *http.Transport {
	return &http.Transport{
		Proxy:                 nil,
		DialContext:           (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		TLSClientConfig:       TLSConfig(want),
		TLSHandshakeTimeout:   10 * time.Second,
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          8,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       60 * time.Second,
		ExpectContinueTimeout: time.Second,
	}
}

// AsMismatch finds a MismatchError in err's chain.
func AsMismatch(err error) (*MismatchError, bool) {
	var me *MismatchError
	if errors.As(err, &me) {
		return me, true
	}
	return nil, false
}
