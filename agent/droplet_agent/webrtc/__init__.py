"""WebRTC Direct: an iPhone (or any browser) talks to this computer with no server in between.

iPhones can't run droplet's mesh (no app), so the droplet web app, installed
from the website to the Home Screen, connects straight to a paired computer
over a WebRTC data channel. There's no signalling server: the technique is
libp2p's "WebRTC Direct" (its v2 credentials, so the browser's own offer is
never modified):

- This computer runs an **ICE-lite** responder on a known UDP port, with a
  **stable DTLS certificate**: the mesh's own (so the DTLS fingerprint *is*
  the mesh fingerprint).
- The browser learns the address, port and fingerprint once, by scanning a
  QR code (`droplet-agent pair --qr`, or the app's Pair page), and writes
  this computer's SDP answer itself: `a=ice-lite`, `a=setup:passive`, the
  fingerprint from the QR, one host candidate, and ICE credentials derived
  from its own offer (see `transport.py`).
- DTLS pins this computer's certificate in the browser. The browser's own
  DTLS certificate is new each time, so the app proves who it is inside the
  channel instead: a persistent ECDSA P-256 key (WebCrypto, non-extractable,
  in IndexedDB) signs a challenge bound to both DTLS fingerprints
  (`protocol.py`). Pairing is the mesh's own: commitments, a signature and
  the 4-digit code on both screens (docs/mesh.md §9.3), plus a one-time
  token from the QR.

Modules:

- deps.py       whether aiortc is there, and running it without PyAV (no media)
- transport.py  the UDP port: ICE-lite, DTLS (aiortc), SCTP and the data channel
- protocol.py   what goes over the channel: pairing, the challenge, text, files
- qr.py         the QR payload, and drawing it in a terminal
- bridge.py     a connected browser as a mesh link, so it's a peer like any other

The wire protocol is docs/iphone.md.
"""

PROTOCOL_VERSION = 1
DEFAULT_PORT = 1739     # UDP; the mesh's TCP port number, so one firewall rule pair covers both
UFRAG_PREFIX = "droplet+v1/"
