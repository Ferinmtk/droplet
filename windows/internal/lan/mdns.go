package lan

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/ipv4"
)

// ServiceType is the DNS-SD service droplet hubs announce.
const ServiceType = "_droplet._tcp.local."

var (
	mdnsGroup = &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: 5353}
	// tailnet addresses (100.64.0.0/10): Tailscale carries no multicast
	cgnat = netip.MustParsePrefix("100.64.0.0/10")
)

// Hub is one hub found on the network.
type Hub struct {
	Instance string       // the service instance, e.g. "droplet-9b1617"
	Port     int          // LAN HTTPS port
	Addrs    []netip.Addr // IPv4 addresses
	TXT
}

// Endpoints are the hub's LAN HTTPS addresses as "ip:port".
func (h Hub) Endpoints() []string {
	out := make([]string, 0, len(h.Addrs))
	for _, a := range h.Addrs {
		out = append(out, netip.AddrPortFrom(a, uint16(h.Port)).String())
	}
	return out
}

// Browse looks for hubs for up to wait. It asks with one-shot ("legacy
// unicast", RFC 6762 §5.1 and §6.7) queries: they're sent to the mDNS group
// from an ordinary ephemeral port, and responders answer straight back to
// that port. So nothing binds 5353 (which Windows' own mDNS service holds),
// no multicast group is joined, and no inbound firewall rule is needed: the
// replies are responses to our own traffic.
//
// found, if set, is called once per hub as soon as its records are complete;
// returning true stops the search early. Browse returns every complete hub
// seen. An error means no query could be sent at all.
func Browse(ctx context.Context, wait time.Duration, found func(Hub) (stop bool)) ([]Hub, error) {
	conns, err := openConns()
	if len(conns) == 0 {
		if err == nil {
			err = errors.New("no network interface to search the LAN on")
		}
		return nil, fmt.Errorf("can't search the LAN: %w", err)
	}
	ctx, cancel := context.WithTimeout(ctx, wait)

	type packet struct {
		data []byte
		src  netip.Addr
	}
	packets := make(chan packet, 32)
	var wg sync.WaitGroup
	for _, c := range conns {
		wg.Add(1)
		go func(c *net.UDPConn) {
			defer wg.Done()
			buf := make([]byte, 9000)
			for {
				n, from, err := c.ReadFromUDPAddrPort(buf)
				if err != nil {
					return // closed when Browse returns
				}
				if from.Port() != 5353 {
					continue // responders answer from the mDNS port
				}
				data := append([]byte(nil), buf[:n]...)
				select {
				case packets <- packet{data, from.Addr().Unmap()}:
				case <-ctx.Done():
					return
				}
			}
		}(c.UDPConn)
	}
	defer func() {
		cancel() // readers blocked handing over a packet give up
		for _, c := range conns {
			c.Close() // readers blocked in a read return
		}
		wg.Wait()
	}()

	col := newCollector()
	reported := map[string]bool{}
	send := func() error {
		q, err := col.query()
		if err != nil {
			return err
		}
		sent := 0
		for _, c := range conns {
			if _, err := c.WriteTo(q, nil, mdnsGroup); err == nil {
				sent++
			}
		}
		if sent == 0 {
			return errors.New("couldn't send an mDNS query on any network interface")
		}
		return nil
	}
	if err := send(); err != nil {
		return nil, err
	}
	// ask again a couple of times: UDP gets lost, and follow-up questions
	// fill in records a responder left out of its first answer
	resend := time.NewTicker(350 * time.Millisecond)
	defer resend.Stop()
	resends := 0
	for {
		select {
		case <-ctx.Done():
			return col.complete(), nil
		case <-resend.C:
			if resends < 3 {
				resends++
				_ = send()
			}
		case p := <-packets:
			col.add(p.data, p.src)
			for _, h := range col.complete() {
				if reported[h.Instance] {
					continue
				}
				reported[h.Instance] = true
				if found != nil && found(h) {
					return col.complete(), nil
				}
			}
		}
	}
}

// BrowseAll is Browse without early stopping.
func BrowseAll(ctx context.Context, wait time.Duration) ([]Hub, error) {
	return Browse(ctx, wait, nil)
}

type conn struct {
	*net.UDPConn
	pc *ipv4.PacketConn
}

func (c conn) WriteTo(b []byte, cm *ipv4.ControlMessage, dst net.Addr) (int, error) {
	return c.pc.WriteTo(b, cm, dst)
}

// openConns opens one UDP socket per multicast-capable IPv4 interface, each
// sending its queries out of that interface (a PC can be on Wi-Fi and
// Ethernet, with VPN and Hyper-V adapters besides).
func openConns() ([]conn, error) {
	var out []conn
	ifaces, err := net.Interfaces()
	if err == nil {
		for i := range ifaces {
			ifi := ifaces[i]
			if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagMulticast == 0 ||
				ifi.Flags&net.FlagLoopback != 0 || ifi.Flags&net.FlagPointToPoint != 0 {
				continue
			}
			ip := interfaceIPv4(&ifi)
			if !ip.IsValid() {
				continue
			}
			c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: ip.AsSlice()})
			if err != nil {
				continue
			}
			pc := ipv4.NewPacketConn(c)
			if err := pc.SetMulticastInterface(&ifi); err != nil {
				c.Close()
				continue
			}
			_ = pc.SetMulticastTTL(255)
			_ = pc.SetMulticastLoopback(true) // a hub on this same machine answers too
			out = append(out, conn{c, pc})
		}
	}
	if len(out) == 0 {
		// no usable interface list: let the system pick the route
		c, err := net.ListenUDP("udp4", &net.UDPAddr{})
		if err != nil {
			return nil, err // e.g. Wine, which can't open Go's UDP sockets
		}
		out = append(out, conn{c, ipv4.NewPacketConn(c)})
	}
	return out, nil
}

func interfaceIPv4(ifi *net.Interface) netip.Addr {
	addrs, err := ifi.Addrs()
	if err != nil {
		return netip.Addr{}
	}
	for _, a := range addrs {
		pfx, err := netip.ParsePrefix(a.String())
		if err != nil {
			continue
		}
		ip := pfx.Addr().Unmap()
		if ip.Is4() && !ip.IsLoopback() && !cgnat.Contains(ip) {
			return ip
		}
	}
	return netip.Addr{}
}

// --- parsing answers -----------------------------------------------------------

type srvRec struct {
	target string
	port   int
}

// collector accumulates mDNS answers into hubs. It is fed whole messages,
// so it can be tested without a network.
type collector struct {
	ptr   map[string]bool         // instance names (lowercase, fully qualified)
	srv   map[string]srvRec       // instance -> target and port
	txt   map[string][]string     // instance -> TXT strings
	addrs map[string][]netip.Addr // host -> IPv4 addresses
	src   map[string][]netip.Addr // instance -> where its records came from
	label map[string]string       // instance -> its name as announced (case kept)
}

func newCollector() *collector {
	return &collector{
		ptr: map[string]bool{}, srv: map[string]srvRec{}, txt: map[string][]string{},
		addrs: map[string][]netip.Addr{}, src: map[string][]netip.Addr{}, label: map[string]string{},
	}
}

func isInstance(name string) bool {
	return strings.HasSuffix(name, "."+ServiceType) && len(name) > len(ServiceType)+1
}

// add reads one mDNS message received from src.
func (c *collector) add(msg []byte, src netip.Addr) {
	var p dnsmessage.Parser
	h, err := p.Start(msg)
	if err != nil || !h.Response {
		return
	}
	if err := p.SkipAllQuestions(); err != nil {
		return
	}
	var all []dnsmessage.Resource
	if rs, err := p.AllAnswers(); err == nil {
		all = append(all, rs...)
	} else {
		return
	}
	if err := p.SkipAllAuthorities(); err == nil {
		if rs, err := p.AllAdditionals(); err == nil {
			all = append(all, rs...)
		}
	}
	for _, r := range all {
		if r.Header.TTL == 0 {
			continue // a goodbye: the record is being withdrawn
		}
		name := strings.ToLower(r.Header.Name.String())
		switch b := r.Body.(type) {
		case *dnsmessage.PTRResource:
			inst := strings.ToLower(b.PTR.String())
			if name == ServiceType && isInstance(inst) {
				c.ptr[inst] = true
				c.label[inst] = b.PTR.String()
				c.addSrc(inst, src)
			}
		case *dnsmessage.SRVResource:
			if isInstance(name) {
				c.srv[name] = srvRec{target: strings.ToLower(b.Target.String()), port: int(b.Port)}
				if _, ok := c.label[name]; !ok {
					c.label[name] = r.Header.Name.String()
				}
				c.addSrc(name, src)
			}
		case *dnsmessage.TXTResource:
			if isInstance(name) {
				c.txt[name] = append([]string(nil), b.TXT...)
				c.addSrc(name, src)
			}
		case *dnsmessage.AResource:
			ip := netip.AddrFrom4(b.A)
			if ip.IsUnspecified() || ip.IsMulticast() || ip.IsLoopback() && !src.IsLoopback() {
				continue
			}
			c.addrs[name] = appendAddr(c.addrs[name], ip)
		}
	}
}

func (c *collector) addSrc(inst string, src netip.Addr) {
	if src.IsValid() && src.Is4() {
		c.src[inst] = appendAddr(c.src[inst], src)
	}
}

func appendAddr(list []netip.Addr, a netip.Addr) []netip.Addr {
	for _, x := range list {
		if x == a {
			return list
		}
	}
	return append(list, a)
}

// instances are every instance name seen, sorted.
func (c *collector) instances() []string {
	seen := map[string]bool{}
	for k := range c.ptr {
		seen[k] = true
	}
	for k := range c.srv {
		seen[k] = true
	}
	for k := range c.txt {
		seen[k] = true
	}
	out := make([]string, 0, len(seen))
	for k := range seen {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// complete are the hubs whose records are all in, with valid TXT.
func (c *collector) complete() []Hub {
	var out []Hub
	for _, inst := range c.instances() {
		s, ok := c.srv[inst]
		if !ok || s.port <= 0 {
			continue
		}
		strs, ok := c.txt[inst]
		if !ok {
			continue
		}
		t, err := ParseTXT(strs)
		if err != nil {
			continue
		}
		addrs := c.addrs[s.target]
		if len(addrs) == 0 {
			// no A record: the address the answer came from is the hub's
			addrs = c.src[inst]
		}
		if len(addrs) == 0 {
			continue
		}
		out = append(out, Hub{
			Instance: instanceLabel(c.label[inst]),
			Port:     s.port,
			Addrs:    append([]netip.Addr(nil), addrs...),
			TXT:      t,
		})
	}
	return out
}

// query builds the next question set: the service's PTR, plus SRV and TXT
// for instances missing them, and A for targets without an address.
func (c *collector) query() ([]byte, error) {
	b := dnsmessage.NewBuilder(make([]byte, 0, 512), dnsmessage.Header{})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	ask := func(name string, t dnsmessage.Type) error {
		n, err := dnsmessage.NewName(name)
		if err != nil {
			return nil // a name too long to ask about: skip it
		}
		return b.Question(dnsmessage.Question{Name: n, Type: t, Class: dnsmessage.ClassINET})
	}
	if err := ask(ServiceType, dnsmessage.TypePTR); err != nil {
		return nil, err
	}
	asked := 0
	for _, inst := range c.instances() {
		if asked >= 8 {
			break
		}
		name := c.label[inst]
		if name == "" {
			name = inst
		}
		if _, ok := c.srv[inst]; !ok {
			if err := ask(name, dnsmessage.TypeSRV); err != nil {
				return nil, err
			}
			asked++
		}
		if _, ok := c.txt[inst]; !ok {
			if err := ask(name, dnsmessage.TypeTXT); err != nil {
				return nil, err
			}
			asked++
		}
		if s, ok := c.srv[inst]; ok && len(c.addrs[s.target]) == 0 && s.target != "" {
			if err := ask(s.target, dnsmessage.TypeA); err != nil {
				return nil, err
			}
			asked++
		}
	}
	return b.Finish()
}

// instanceLabel is an instance name without the service type, as shown to people.
func instanceLabel(name string) string {
	if len(name) > len(ServiceType)+1 && strings.EqualFold(name[len(name)-len(ServiceType)-1:], "."+ServiceType) {
		return name[:len(name)-len(ServiceType)-1]
	}
	return name
}

// String is a short description, for logs.
func (h Hub) String() string {
	name := h.Name
	if name == "" {
		name = h.Instance
	}
	return name + " (" + h.ID + ") at " + strings.Join(h.Endpoints(), ", ") + " http:" + strconv.Itoa(h.HTTPPort)
}
