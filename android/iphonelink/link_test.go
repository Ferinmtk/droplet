package iphonelink

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"math/big"
	"strings"
	"testing"
	"time"
)

type testSigner struct{ key *ecdsa.PrivateKey }

func (t testSigner) SignDigest(digest []byte) ([]byte, error) {
	return ecdsa.SignASN1(rand.Reader, t.key, digest)
}

type testCallbacks struct{}

func (testCallbacks) Opened(*Conn) Receiver { return nil }
func (testCallbacks) Log(string)            {}

// gomobile hands Listen a Java array that's only valid during the call: the
// server must still present the whole certificate once that memory is reused.
func TestListenKeepsItsOwnCopyOfTheCertificate(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test"},
		NotBefore: time.Now(), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(der)
	want := strings.ToUpper(hex.EncodeToString(sum[:]))
	s, err := Listen(0, 1, der, testSigner{key}, testCallbacks{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for i := range der {
		der[i] = 0
	}
	fps, err := s.cert.GetFingerprints()
	if err != nil {
		t.Fatal(err)
	}
	for _, fp := range fps {
		if fp.Algorithm == "sha-256" && strings.ToUpper(strings.ReplaceAll(fp.Value, ":", "")) == want {
			return
		}
	}
	t.Fatal("the certificate changed when the caller's bytes did")
}
