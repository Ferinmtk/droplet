// Package iphonelink is the Android app's side of the iPhone link
// (docs/iphone.md §1): a UDP port that answers a browser's WebRTC connection
// with no signalling server, as an ICE-lite peer with a stable DTLS
// certificate, the phone's mesh certificate. It's what the Linux and Mac
// agent's webrtc/transport.py does with aiortc, here with Pion.
//
// The app (Kotlin) binds it with gomobile. It gives the mesh certificate and
// a Signer (the key stays in the Android Keystore, which signs each DTLS
// handshake's digest); it gets each browser's data channel once it's open,
// with the SHA-256 of that browser's DTLS certificate, and does the rest
// (docs/iphone.md §3) itself.
package iphonelink

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/pion/datachannel"
	"github.com/pion/ice/v4"
	"github.com/pion/webrtc/v4"
)

// UfragPrefix starts the ICE username fragment a browser picks for this side.
const UfragPrefix = "droplet+v1/"

const (
	sctpPort    = 5000
	maxMessage  = 256 * 1024       // a frame, either way (the browser's answer says the same)
	idle        = 30 * time.Second // without a packet before a connection is dropped
	setupTime   = 20 * time.Second // from the first check to an open channel
	maxSessions = 8
)

// Signer signs a SHA-256 digest with the mesh key: ECDSA, DER-encoded.
type Signer interface {
	SignDigest(digest []byte) ([]byte, error)
}

// Receiver gets what arrives on one browser's channel, in order, on one thread.
type Receiver interface {
	OnText(text string)
	OnBinary(data []byte)
	OnClose()
}

// Callbacks: a browser's channel opened (return who receives from it), and log lines.
type Callbacks interface {
	Opened(c *Conn) Receiver
	Log(msg string)
}

// Server is the UDP port.
type Server struct {
	m        *mux
	port     int
	cert     webrtc.Certificate
	cb       Callbacks
	mu       sync.Mutex
	sessions int
	closed   bool
}

// Listen opens the first free UDP port of port..port+tries-1 (port 0: any) for browsers.
// certDER is the mesh certificate (EC P-256); signer signs with its key.
func Listen(port, tries int, certDER []byte, signer Signer, cb Callbacks) (*Server, error) {
	cert, err := x509.ParseCertificate(certDER)
	if err != nil {
		return nil, fmt.Errorf("the certificate: %w", err)
	}
	pub, ok := cert.PublicKey.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("the certificate's key isn't EC")
	}
	if tries < 1 || port == 0 {
		tries = 1
	}
	var sock *net.UDPConn
	for p := port; p < port+tries; p++ {
		// IPv4 only: the QR code lists IPv4 addresses (LAN and tailnet), and ICE
		// matches a browser to this side by address family
		if sock, err = net.ListenUDP("udp4", &net.UDPAddr{Port: p}); err == nil {
			break
		}
	}
	if err != nil {
		return nil, err
	}
	s := &Server{port: sock.LocalAddr().(*net.UDPAddr).Port, cb: cb,
		cert: webrtc.CertificateFromX509(&keySigner{pub, signer}, cert)}
	s.m = newMux(sock, s.accept)
	return s, nil
}

// Port is the UDP port it listens on.
func (s *Server) Port() int { return s.port }

// Close stops listening and drops every connection.
func (s *Server) Close() {
	s.mu.Lock()
	s.closed = true
	s.mu.Unlock()
	_ = s.m.Close()
}

func (s *Server) log(format string, a ...any) {
	if s.cb != nil {
		s.cb.Log(fmt.Sprintf(format, a...))
	}
}

// accept: a browser's first check, for a ufrag not seen yet. Runs on the mux's reader.
func (s *Server) accept(local, remote string, from net.Addr) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.sessions >= maxSessions {
		return false
	}
	s.sessions++
	go s.connect(local, remote, from)
	return true
}

func (s *Server) done() {
	s.mu.Lock()
	s.sessions--
	s.mu.Unlock()
}

func (s *Server) connect(local, remote string, from net.Addr) {
	address := from.String()
	if ua, ok := from.(*net.UDPAddr); ok {
		address = ua.IP.String()
		if v4 := ua.IP.To4(); v4 != nil {
			address = v4.String()
		}
	}
	se := webrtc.SettingEngine{}
	se.SetLite(true)
	se.SetICECredentials(local, local)
	se.SetICEUDPMux(s.m)
	se.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	se.SetICETimeouts(idle/2, idle, 2*time.Second)
	se.DisableCertificateFingerprintVerification(true) // the app's challenge checks the browser instead
	_ = se.SetAnsweringDTLSRole(webrtc.DTLSRoleServer)
	se.DetachDataChannels()
	se.SetSCTPMaxReceiveBufferSize(4 * 1024 * 1024)
	pc, err := webrtc.NewAPI(webrtc.WithSettingEngine(se)).NewPeerConnection(
		webrtc.Configuration{Certificates: []webrtc.Certificate{s.cert}})
	if err != nil {
		s.log("iphone: can't make a connection for %s: %v", address, err)
		s.m.RemoveConnByUfrag(local)
		s.done()
		return
	}
	c := &Conn{s: s, pc: pc, address: address}
	pc.OnConnectionStateChange(func(st webrtc.PeerConnectionState) {
		if st == webrtc.PeerConnectionStateFailed || st == webrtc.PeerConnectionStateClosed {
			c.Close()
		}
	})
	negotiated, ordered, id := true, true, uint16(0)
	dc, err := pc.CreateDataChannel("droplet", &webrtc.DataChannelInit{Negotiated: &negotiated, ID: &id, Ordered: &ordered})
	if err != nil {
		s.log("iphone: no channel for %s: %v", address, err)
		c.Close()
		return
	}
	c.dc = dc
	dc.OnOpen(func() { c.opened() })
	if err = pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: browserOffer(remote)}); err == nil {
		var answer webrtc.SessionDescription
		if answer, err = pc.CreateAnswer(nil); err == nil {
			err = pc.SetLocalDescription(answer)
		}
	}
	if err != nil {
		s.log("iphone: can't answer %s: %v", address, err)
		c.Close()
		return
	}
	time.AfterFunc(setupTime, func() {
		if !c.isOpen() {
			s.log("iphone: %s didn't open its channel in time", address)
			c.Close()
		}
	})
}

// browserOffer is the browser's side as this one needs it. Only the ufrag is
// real (it's in the browser's checks); the browser's password and DTLS
// fingerprint aren't known here, and aren't needed: an ICE-lite peer never
// sends checks, and the fingerprint isn't verified (the app's challenge binds
// the browser's DTLS certificate instead).
func browserOffer(ufrag string) string {
	fake := strings.TrimSuffix(strings.Repeat("00:", 32), ":")
	return strings.Join([]string{
		"v=0",
		"o=- 0 0 IN IP4 0.0.0.0",
		"s=-",
		"t=0 0",
		"a=group:BUNDLE 0",
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
		"c=IN IP4 0.0.0.0",
		"a=mid:0",
		"a=ice-ufrag:" + ufrag,
		"a=ice-pwd:droplet-browser-password-unused",
		"a=fingerprint:sha-256 " + fake,
		"a=setup:actpass",
		fmt.Sprintf("a=sctp-port:%d", sctpPort),
		fmt.Sprintf("a=max-message-size:%d", maxMessage),
		"",
	}, "\r\n")
}

// Conn is one browser's open channel.
type Conn struct {
	s       *Server
	pc      *webrtc.PeerConnection
	dc      *webrtc.DataChannel
	raw     datachannel.ReadWriteCloser
	address string
	fp      string
	mu      sync.Mutex
	open    bool
	closed  bool
	rcv     Receiver
}

func (c *Conn) isOpen() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.open
}

func (c *Conn) opened() {
	raw, err := c.dc.Detach()
	if err != nil {
		c.s.log("iphone: %v", err)
		c.Close()
		return
	}
	sum := sha256.Sum256(c.pc.SCTP().Transport().GetRemoteCertificate())
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.raw, c.fp, c.open = raw, hex.EncodeToString(sum[:]), true
	c.mu.Unlock()
	rcv := c.s.cb.Opened(c)
	c.mu.Lock()
	c.rcv = rcv
	closed := c.closed
	c.mu.Unlock()
	if closed {
		if rcv != nil {
			rcv.OnClose()
		}
		return
	}
	go c.read(rcv)
}

func (c *Conn) read(rcv Receiver) {
	buf := make([]byte, maxMessage+64)
	for {
		n, isString, err := c.raw.ReadDataChannel(buf)
		if err != nil {
			if !errors.Is(err, io.EOF) && !strings.Contains(err.Error(), "User Initiated Abort") {
				c.s.log("iphone: %s: %v", c.address, err)
			}
			break
		}
		if rcv == nil {
			continue
		}
		if isString {
			rcv.OnText(string(buf[:n]))
		} else {
			rcv.OnBinary(append([]byte(nil), buf[:n]...))
		}
	}
	c.Close()
}

// RemoteFingerprint is the SHA-256 of the browser's DTLS certificate, lowercase hex.
func (c *Conn) RemoteFingerprint() string { return c.fp }

// Address is the browser's IP address.
func (c *Conn) Address() string { return c.address }

// SendText sends one text frame (UTF-8, at most 256 KB).
func (c *Conn) SendText(text string) error { return c.write([]byte(text), true) }

// SendBinary sends one binary frame.
func (c *Conn) SendBinary(data []byte) error { return c.write(data, false) }

func (c *Conn) write(b []byte, isString bool) error {
	c.mu.Lock()
	raw, closed := c.raw, c.closed
	c.mu.Unlock()
	if closed || raw == nil {
		return net.ErrClosed
	}
	if len(b) > maxMessage {
		return errors.New("too large for one frame")
	}
	_, err := raw.WriteDataChannel(b, isString)
	return err
}

// Buffered is how many bytes are waiting to go (the sender keeps it under a limit).
func (c *Conn) Buffered() int64 {
	if c.dc == nil {
		return 0
	}
	return int64(c.dc.BufferedAmount())
}

// Close ends the connection; the receiver's OnClose is called once.
func (c *Conn) Close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	rcv := c.rcv
	c.mu.Unlock()
	go func() {
		_ = c.pc.Close()
		c.s.done()
		if rcv != nil {
			rcv.OnClose()
		}
	}()
}

// keySigner is the mesh key as a crypto.Signer: its public half from the certificate,
// its signatures from the Android Keystore.
type keySigner struct {
	pub    *ecdsa.PublicKey
	signer Signer
}

func (k *keySigner) Public() crypto.PublicKey { return k.pub }

func (k *keySigner) Sign(_ io.Reader, digest []byte, _ crypto.SignerOpts) ([]byte, error) {
	return k.signer.SignDigest(digest)
}
