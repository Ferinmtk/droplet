package iphonelink

import (
	"errors"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/pion/stun/v4"
)

// mux shares one UDP socket between the browsers connected to it. A browser
// is known by the ICE username fragment it chose for this side
// ("droplet+v1/…", the first half of its checks' USERNAME), and then by the
// addresses its checks came from. Pion's ICE agents read and write through
// the muxConn each gets from GetConn, as with its own UDPMuxDefault; this one
// also says when a fragment it hasn't seen arrives, so a connection can be
// made for it.
type mux struct {
	sock   *net.UDPConn
	onNew  func(local, remote string, from net.Addr) bool // starts a connection; false refuses it
	mu     sync.Mutex
	ufrags map[string]*muxConn
	addrs  map[string]*muxConn
	closed bool
}

func newMux(sock *net.UDPConn, onNew func(local, remote string, from net.Addr) bool) *mux {
	m := &mux{sock: sock, onNew: onNew, ufrags: map[string]*muxConn{}, addrs: map[string]*muxConn{}}
	go m.read()
	return m
}

func (m *mux) read() {
	buf := make([]byte, 65536)
	for {
		n, from, err := m.sock.ReadFrom(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		pkt := append([]byte(nil), buf[:n]...)
		// as a.b.c.d, never ::ffff:a.b.c.d, or ICE takes it for an IPv6 address
		if ua, ok := from.(*net.UDPAddr); ok {
			if v4 := ua.IP.To4(); v4 != nil {
				from = &net.UDPAddr{IP: v4, Port: ua.Port}
			}
		}
		key := from.String()
		m.mu.Lock()
		c := m.addrs[key]
		m.mu.Unlock()
		if c == nil {
			local, remote, ok := checkUsername(pkt)
			if !ok {
				continue // nothing but a STUN check may open a connection
			}
			m.mu.Lock()
			c = m.ufrags[local]
			fresh := c == nil
			if fresh && !m.closed {
				c = newMuxConn(m, local)
				m.ufrags[local] = c
			}
			if c != nil {
				m.addrs[key] = c
			}
			m.mu.Unlock()
			if c == nil {
				continue
			}
			if fresh && !m.onNew(local, remote, from) {
				c.Close()
				continue
			}
		}
		c.push(pkt, from)
	}
}

// checkUsername: the two ufrags of a STUN binding request from a browser,
// "<this side's>:<the browser's>", when this side's is one of ours.
func checkUsername(b []byte) (local, remote string, ok bool) {
	if !stun.IsMessage(b) {
		return "", "", false
	}
	msg := &stun.Message{Raw: b}
	if msg.Decode() != nil || msg.Type != stun.BindingRequest {
		return "", "", false
	}
	var u stun.Username
	if u.GetFrom(msg) != nil {
		return "", "", false
	}
	local, remote, found := strings.Cut(u.String(), ":")
	if !found || !strings.HasPrefix(local, UfragPrefix) || len(local) != len(UfragPrefix)+24 || remote == "" {
		return "", "", false
	}
	return local, remote, true
}

// --- ice.UDPMux ---------------------------------------------------------------

func (m *mux) GetConn(ufrag string, _ net.Addr) (net.PacketConn, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return nil, net.ErrClosed
	}
	if c := m.ufrags[ufrag]; c != nil {
		return c, nil
	}
	c := newMuxConn(m, ufrag)
	m.ufrags[ufrag] = c
	return c, nil
}

func (m *mux) RemoveConnByUfrag(ufrag string) {
	m.mu.Lock()
	c := m.ufrags[ufrag]
	m.mu.Unlock()
	if c != nil {
		c.Close()
	}
}

func (m *mux) GetListenAddresses() []net.Addr { return []net.Addr{m.sock.LocalAddr()} }

func (m *mux) Close() error {
	m.mu.Lock()
	m.closed = true
	conns := make([]*muxConn, 0, len(m.ufrags))
	for _, c := range m.ufrags {
		conns = append(conns, c)
	}
	m.mu.Unlock()
	for _, c := range conns {
		c.Close()
	}
	return m.sock.Close()
}

func (m *mux) forget(c *muxConn) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.ufrags[c.ufrag] == c {
		delete(m.ufrags, c.ufrag)
	}
	for k, v := range m.addrs {
		if v == c {
			delete(m.addrs, k)
		}
	}
}

// --- one browser's share of the socket -----------------------------------------

type packet struct {
	b    []byte
	from net.Addr
}

type muxConn struct {
	m        *mux
	ufrag    string
	in       chan packet
	done     chan struct{}
	once     sync.Once
	mu       sync.Mutex
	deadline time.Time
	wake     chan struct{}
}

func newMuxConn(m *mux, ufrag string) *muxConn {
	return &muxConn{m: m, ufrag: ufrag, in: make(chan packet, 256), done: make(chan struct{}), wake: make(chan struct{}, 1)}
}

func (c *muxConn) push(b []byte, from net.Addr) {
	select {
	case c.in <- packet{b, from}:
	case <-c.done:
	default: // full: drop it, as a network would
	}
}

func (c *muxConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		c.mu.Lock()
		d := c.deadline
		c.mu.Unlock()
		var t *time.Timer
		var timer <-chan time.Time
		if !d.IsZero() {
			wait := time.Until(d)
			if wait <= 0 {
				return 0, nil, timeoutError{}
			}
			t = time.NewTimer(wait)
			timer = t.C
		}
		stop := func() {
			if t != nil {
				t.Stop()
			}
		}
		select {
		case pk := <-c.in:
			stop()
			return copy(p, pk.b), pk.from, nil
		case <-c.done:
			stop()
			return 0, nil, net.ErrClosed
		case <-timer:
			return 0, nil, timeoutError{}
		case <-c.wake: // the deadline changed
			stop()
		}
	}
}

func (c *muxConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	select {
	case <-c.done:
		return 0, net.ErrClosed
	default:
	}
	return c.m.sock.WriteTo(p, addr)
}

func (c *muxConn) Close() error {
	c.once.Do(func() {
		close(c.done)
		c.m.forget(c)
	})
	return nil
}

func (c *muxConn) LocalAddr() net.Addr { return c.m.sock.LocalAddr() }

func (c *muxConn) SetDeadline(t time.Time) error { return c.SetReadDeadline(t) }

func (c *muxConn) SetReadDeadline(t time.Time) error {
	c.mu.Lock()
	c.deadline = t
	c.mu.Unlock()
	select {
	case c.wake <- struct{}{}:
	default:
	}
	return nil
}

func (c *muxConn) SetWriteDeadline(time.Time) error { return nil }

type timeoutError struct{}

func (timeoutError) Error() string   { return "i/o timeout" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }
