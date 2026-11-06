// echo is a test peer for the iPhone link's transport (test_browser.mjs): it
// listens like the app does, with a throwaway certificate, prints
// {"port","fp"} on one line, and echoes every frame back. "fp?" is answered
// with the browser's DTLS fingerprint as this side saw it.
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"time"

	"dev.droplet/iphonelink"
)

type signer struct{ key *ecdsa.PrivateKey }

func (s signer) SignDigest(d []byte) ([]byte, error) { return ecdsa.SignASN1(rand.Reader, s.key, d) }

type echo struct{ c *iphonelink.Conn }

func (e echo) OnText(t string) {
	if t == "fp?" {
		_ = e.c.SendText(e.c.RemoteFingerprint())
		return
	}
	_ = e.c.SendText(t)
}
func (e echo) OnBinary(b []byte) { _ = e.c.SendBinary(b) }
func (e echo) OnClose()          { fmt.Fprintln(os.Stderr, "closed") }

type cb struct{}

func (cb) Opened(c *iphonelink.Conn) iphonelink.Receiver {
	fmt.Fprintln(os.Stderr, "opened from", c.Address())
	return echo{c}
}
func (cb) Log(m string) { fmt.Fprintln(os.Stderr, m) }

func main() {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "droplet"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, &tpl, &tpl, &key.PublicKey, key)
	if err != nil {
		panic(err)
	}
	s, err := iphonelink.Listen(0, 1, der, signer{key}, cb{})
	if err != nil {
		panic(err)
	}
	sum := sha256.Sum256(der)
	out, _ := json.Marshal(map[string]any{"port": s.Port(), "fp": hex.EncodeToString(sum[:])})
	fmt.Println(string(out))
	select {}
}
