"""droplet mesh: devices talk to each other directly; the hub is an optional helper.

The design is docs/mesh.md. This package is the Linux agent's peer, and the
reference other platforms follow:

- identity.py   the long-lived key and self-signed certificate; the fingerprint is the peer
- tlsctx.py     mutual TLS: server and client contexts, the fingerprint check
- trust.py      the trust list (roster peers and directly paired peers)
- discovery.py  `_droplet-peer._tcp` over mDNS: announcing and browsing
- wslink.py     a WebSocket over an established TLS socket (websockets' Sans-I/O layer)
- server.py     the mesh port: /mesh (WebSocket), /mesh/files/<id>, /mesh/pair
- pairing.py    direct pairing: commitments, the 4-digit code, the signature
- files.py      file offers, serving with Range, downloading with resume
- outbox.py     messages kept locally until a route appears
- node.py       ties it together: links, dispatch, routing, the roster
- control.py    the local socket the CLI uses to talk to the running agent
- desktop.py    notifications and the ring sound

It depends on the rest of the agent only through the `Host` interface in
node.py, so it can be lifted out whole.
"""

PROTOCOL_VERSION = 1
SERVICE = "_droplet-peer._tcp.local."
DEFAULT_PORT = 1739
PORT_RANGE = range(1739, 1750)   # 1739, or the next free one up to 1749
