package pin

import (
	"crypto/tls"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func tlsServer(t *testing.T) (*httptest.Server, string) {
	t.Helper()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "hello")
	}))
	t.Cleanup(srv.Close)
	return srv, Fingerprint(srv.Certificate().Raw)
}

func get(t *testing.T, tr http.RoundTripper, url string) (string, error) {
	t.Helper()
	resp, err := (&http.Client{Transport: tr}).Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return string(b), nil
}

func TestPinnedAccept(t *testing.T) {
	srv, fp := tlsServer(t)
	body, err := get(t, Transport(fp), srv.URL)
	if err != nil || body != "hello" {
		t.Fatalf("pinned GET: %q, %v", body, err)
	}
	// the same pin written differently still matches
	upper := strings.ToUpper(fp[:2]) + ":" + fp[2:]
	if _, err := get(t, Transport(upper), srv.URL); err != nil {
		t.Fatalf("pin with colons/uppercase: %v", err)
	}
}

func TestPinnedRejectsOtherCertificate(t *testing.T) {
	srv, _ := tlsServer(t)
	other := strings.Repeat("ab", 32) // any pin but the server's
	_, err := get(t, Transport(other), srv.URL)
	if err == nil {
		t.Fatal("a different certificate was accepted")
	}
	me, ok := AsMismatch(err)
	if !ok {
		t.Fatalf("want a MismatchError in the chain, got %T: %v", err, err)
	}
	if me.Want != other || me.Got != Fingerprint(srv.Certificate().Raw) {
		t.Fatalf("mismatch details: %+v", me)
	}
	if !strings.Contains(me.Error(), "identity changed") {
		t.Fatalf("message: %s", me.Error())
	}
}

func TestEmptyOrBadPinRejectsEverything(t *testing.T) {
	srv, _ := tlsServer(t)
	for _, p := range []string{"", "zz", strings.Repeat("g", 64), strings.Repeat("a", 63)} {
		_, err := get(t, Transport(p), srv.URL)
		if err == nil || !errors.Is(err, ErrNoPin) {
			t.Fatalf("pin %q: want ErrNoPin, got %v", p, err)
		}
	}
}

func TestResumedSessionsAreStillChecked(t *testing.T) {
	srv, fp := tlsServer(t)
	// even if someone adds a session cache later, a resumed session with a
	// different pin must fail (VerifyConnection runs on resumption)
	cfg := TLSConfig(fp)
	cfg.ClientSessionCache = tls.NewLRUClientSessionCache(4)
	tr := &http.Transport{TLSClientConfig: cfg, DisableKeepAlives: true}
	if _, err := get(t, tr, srv.URL); err != nil {
		t.Fatal(err)
	}
	bad := TLSConfig(strings.Repeat("cd", 32))
	bad.ClientSessionCache = cfg.ClientSessionCache
	tr2 := &http.Transport{TLSClientConfig: bad, DisableKeepAlives: true}
	if _, err := get(t, tr2, srv.URL); err == nil {
		t.Fatal("resumed session skipped the pin check")
	}
}

func TestNormalTransportStillVerifies(t *testing.T) {
	// the pinned transport must not leak into the default one
	srv, fp := tlsServer(t)
	_ = Transport(fp)
	if _, err := get(t, http.DefaultTransport, srv.URL); err == nil {
		t.Fatal("the default transport accepted a self-signed certificate")
	}
}

func TestNormalizeAndCheck(t *testing.T) {
	good := strings.Repeat("0a", 32)
	if n, ok := Normalize(" " + strings.ToUpper(good) + " "); !ok || n != good {
		t.Fatalf("normalize: %q %v", n, ok)
	}
	if err := Check(good, nil); err == nil {
		t.Fatal("no certificate must not pass")
	}
	der := []byte("not really a certificate")
	if err := Check(Fingerprint(der), der); err != nil {
		t.Fatal(err)
	}
}
