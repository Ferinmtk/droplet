"""Direct pairing, with no hub: both screens show the same 4-digit code.

The initiator I pairs with the responder R. HTTPS on R's mesh port; I
presents **no** client certificate (R's TLS would refuse one it doesn't
trust yet), and instead proves it holds its key with a signature.

1. I → R  `POST /mesh/pair`
   `{"v":1, "id", "name", "os", "cert": <I's certificate, PEM>, "commit": hex(SHA-256(nA))}`
   where nA is 32 random bytes. R answers
   `{"v":1, "request": <id>, "nonce": hex(nB), "id", "name", "os", "fp": R's fingerprint}`
   with nB 32 random bytes of its own. I checks the certificate R presented
   in TLS has that fingerprint (and, when I found R over mDNS or the
   roster, the fingerprint it expected).
2. I → R  `POST /mesh/pair/<request>/confirm`  `{"nonce": hex(nA), "sig": base64(signature)}`
   R checks SHA-256(nA) matches the commitment, and the signature: ECDSA
   P-256 with SHA-256 (DER-encoded) by the key in I's certificate, over the
   transcript below. Only then does R show the request and the code.
3. I polls `GET /mesh/pair/<request>` → `{"state": "waiting"|"accepted"|"denied"|"expired"}`,
   and may give up with `POST /mesh/pair/<request>/cancel`.

    transcript = "droplet-pair-v1\\n" fpI "\\n" fpR "\\n" hex(nA) "\\n" hex(nB)      (ASCII, lowercase hex)
    code       = first 8 bytes of SHA-256("droplet-pair-code-v1\\n" + transcript), big-endian, mod 10000, 4 digits

Why the commitment: a 4-digit code alone can be ground. An attacker in the
middle could make key pairs until its two codes match. Here I is bound to nA
before it sees nB, and R picks nB before it sees nA, so the attacker has one
guess in 10,000 per attempt, and each attempt shows the owner a request.
The signature covers R's fingerprint as I saw it, so it can't be replayed to
anyone else, and nobody can pair with a certificate whose key they don't hold.

Each side trusts the other only when its owner has said yes: R's owner with
`droplet-agent pair --accept`, I's owner by confirming the code matches.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import http.client
import json
import re
import secrets
import threading
import time

from . import PROTOCOL_VERSION
from .identity import Identity, PEER_ID, fingerprint, pem_to_der, sign, verify
from .tlsctx import client_context, peer_fingerprint

REQUEST_TTL = 300        # seconds a request stays open
MAX_OPEN = 3             # open incoming requests at once
MAX_PER_MINUTE = 20      # pairing POSTs accepted per minute
NONCE = re.compile(r"^[0-9a-f]{64}$")

WAITING, ACCEPTED, DENIED, EXPIRED, CANCELLED = "waiting", "accepted", "denied", "expired", "cancelled"


def new_nonce() -> str:
    return secrets.token_hex(32)


def commitment(nonce_hex: str) -> str:
    return hashlib.sha256(bytes.fromhex(nonce_hex)).hexdigest()


def transcript(fp_initiator: str, fp_responder: str, nonce_i: str, nonce_r: str) -> bytes:
    return "\n".join(["droplet-pair-v1", fp_initiator, fp_responder, nonce_i, nonce_r]).encode("ascii")


def code(fp_initiator: str, fp_responder: str, nonce_i: str, nonce_r: str) -> str:
    h = hashlib.sha256(b"droplet-pair-code-v1\n" + transcript(fp_initiator, fp_responder, nonce_i, nonce_r))
    return f"{int.from_bytes(h.digest()[:8], 'big') % 10000:04d}"


class PairError(Exception):
    pass


# --- the responder --------------------------------------------------------------

class Incoming:
    """Pairing requests other devices made to this one."""

    def __init__(self, identity: Identity, *, clock=time.time):
        self.identity = identity
        self.clock = clock
        self._lock = threading.Lock()
        self._reqs: dict[str, dict] = {}
        self._posts: list[float] = []
        self.on_ready = None   # called with the request once it's confirmed and waiting for the owner

    def _prune(self):
        now = self.clock()
        for rid, r in list(self._reqs.items()):
            if r["state"] in ("new", WAITING) and now - r["created"] > REQUEST_TTL:
                r["state"] = EXPIRED
            if now - r["created"] > REQUEST_TTL * 2:
                del self._reqs[rid]

    def open(self, body: dict, my_id: str, my_name: str) -> tuple[int, dict]:
        """Step 1. Returns (HTTP status, JSON body)."""
        if not isinstance(body, dict):
            return 400, {"error": "expected a JSON object"}
        peer_id, commit = body.get("id"), body.get("commit")
        if not isinstance(peer_id, str) or not PEER_ID.match(peer_id):
            return 400, {"error": "bad id"}
        if not isinstance(commit, str) or not NONCE.match(commit):
            return 400, {"error": "bad commit"}
        try:
            der = pem_to_der(body.get("cert"))
        except ValueError as e:
            return 400, {"error": f"bad certificate: {e}"}
        fp = fingerprint(der)
        if fp == self.identity.fp:
            return 409, {"error": "that's this device"}
        with self._lock:
            now = self.clock()
            self._posts = [t for t in self._posts if now - t < 60]
            if len(self._posts) >= MAX_PER_MINUTE:
                return 429, {"error": "too many pairing requests; wait a minute"}
            self._posts.append(now)
            self._prune()
            if sum(1 for r in self._reqs.values() if r["state"] in ("new", WAITING)) >= MAX_OPEN:
                return 429, {"error": "too many pairing requests are waiting; answer those first"}
            rid = secrets.token_hex(16)
            nonce_r = new_nonce()
            name = " ".join(str(body.get("name") or "").split())
            self._reqs[rid] = {
                "request": rid, "state": "new", "created": now, "id": peer_id,
                "name": "".join(c for c in name if c.isprintable())[:64] or peer_id,
                "os": "".join(c for c in str(body.get("os") or "") if c.isalnum())[:20],
                "fp": fp, "der": der, "commit": commit, "nonce_r": nonce_r, "code": None,
            }
        return 200, {"v": PROTOCOL_VERSION, "request": rid, "nonce": nonce_r, "id": my_id, "name": my_name,
                     "os": "linux", "fp": self.identity.fp}

    def confirm(self, rid: str, body: dict) -> tuple[int, dict]:
        """Step 2: the reveal and the signature."""
        with self._lock:
            self._prune()
            r = self._reqs.get(rid)
            if r is None or r["state"] != "new":
                return 404, {"error": "no such request"}
            nonce_i = body.get("nonce") if isinstance(body, dict) else None
            sig = body.get("sig") if isinstance(body, dict) else None
            ok = isinstance(nonce_i, str) and NONCE.match(nonce_i) and hmac.compare_digest(commitment(nonce_i),
                                                                                          r["commit"])
            if ok:
                try:
                    raw_sig = base64.b64decode(sig, validate=True) if isinstance(sig, str) else b""
                except ValueError:
                    raw_sig = b""
                ok = verify(r["der"], raw_sig, transcript(r["fp"], self.identity.fp, nonce_i, r["nonce_r"]))
            if not ok:
                del self._reqs[rid]
                return 403, {"error": "the pairing proof didn't check out"}
            r["code"] = code(r["fp"], self.identity.fp, nonce_i, r["nonce_r"])
            r["state"] = WAITING
            public = self._public(r)
        if self.on_ready:
            self.on_ready(public)
        return 200, {"state": WAITING}

    def status(self, rid: str) -> tuple[int, dict]:
        with self._lock:
            self._prune()
            r = self._reqs.get(rid)
            if r is None:
                return 404, {"state": EXPIRED}
            state = r["state"] if r["state"] != "new" else WAITING
            return 200, {"state": state}

    def cancel(self, rid: str) -> tuple[int, dict]:
        with self._lock:
            r = self._reqs.get(rid)
            if r is None:
                return 404, {"state": EXPIRED}
            if r["state"] in ("new", WAITING):
                r["state"] = CANCELLED
            return 200, {"state": r["state"]}

    def _public(self, r: dict) -> dict:
        return {k: r[k] for k in ("request", "id", "name", "os", "fp", "code", "created", "state")}

    def waiting(self) -> list[dict]:
        with self._lock:
            self._prune()
            return [self._public(r) for r in sorted(self._reqs.values(), key=lambda r: r["created"])
                    if r["state"] == WAITING]

    def answer(self, rid: str, accept: bool) -> dict | None:
        """The owner's answer. Returns the request (with its certificate DER) if it was waiting."""
        with self._lock:
            self._prune()
            r = self._reqs.get(rid)
            if r is None or r["state"] != WAITING:
                return None
            r["state"] = ACCEPTED if accept else DENIED
            out = self._public(r)
            out["der"] = r["der"]
            return out


# --- the initiator --------------------------------------------------------------

def _call(conn: http.client.HTTPSConnection, method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    conn.request(method, path, body=data, headers=headers)
    resp = conn.getresponse()
    raw = resp.read(65536)
    try:
        out = json.loads(raw) if raw else {}
    except ValueError:
        out = {}
    return resp.status, out if isinstance(out, dict) else {}


class Outgoing:
    """This device asking another to pair. Steps 1 and 2 happen in start()."""

    def __init__(self, identity: Identity, my_id: str, my_name: str, host: str, port: int,
                 expect_fp: str | None, timeout: float = 8):
        self.identity, self.my_id, self.my_name = identity, my_id, my_name
        self.host, self.port, self.expect_fp, self.timeout = host, port, expect_fp, timeout
        self.request = None
        self.code = None
        self.peer: dict = {}
        self.der: bytes | None = None
        self.local_ok = False       # the owner here confirmed the code
        self.state = "new"

    def _conn(self) -> http.client.HTTPSConnection:
        # no client certificate: the responder doesn't trust us yet
        ctx = client_context(None, self.expect_fp or (self.peer.get("fp") if self.peer else None))
        conn = http.client.HTTPSConnection(self.host, self.port, context=ctx, timeout=self.timeout)
        conn.connect()
        return conn

    def start(self):
        nonce_i = new_nonce()
        conn = self._conn()
        try:
            der = conn.sock.getpeercert(binary_form=True)
            tls_fp = peer_fingerprint(conn.sock)
            status, out = _call(conn, "POST", "/mesh/pair", {
                "v": PROTOCOL_VERSION, "id": self.my_id, "name": self.my_name, "os": "linux",
                "cert": self.identity.cert_pem, "commit": commitment(nonce_i)})
            if status != 200:
                raise PairError(out.get("error") or f"the peer answered {status}")
            if out.get("fp") != tls_fp:
                raise PairError("the peer's answer doesn't match the certificate it presented")
            nonce_r, rid = out.get("nonce"), out.get("request")
            if not (isinstance(nonce_r, str) and NONCE.match(nonce_r) and isinstance(rid, str)
                    and re.fullmatch(r"[0-9a-f]{32}", rid)):
                raise PairError("the peer's answer was malformed")
            peer_id = out.get("id")
            if not isinstance(peer_id, str) or not PEER_ID.match(peer_id):
                raise PairError("the peer sent a bad id")
            sig = sign(self.identity, transcript(self.identity.fp, tls_fp, nonce_i, nonce_r))
            status, got = _call(conn, "POST", f"/mesh/pair/{rid}/confirm",
                                {"nonce": nonce_i, "sig": base64.b64encode(sig).decode()})
            if status != 200:
                raise PairError(got.get("error") or f"the peer answered {status}")
        finally:
            conn.close()
        self.request, self.der = rid, der
        self.peer = {"id": peer_id, "name": str(out.get("name") or peer_id)[:64], "fp": tls_fp,
                     "os": str(out.get("os") or "")[:20]}
        self.code = code(self.identity.fp, tls_fp, nonce_i, nonce_r)
        self.state = WAITING

    def poll(self) -> str:
        conn = self._conn()
        try:
            status, out = _call(conn, "GET", f"/mesh/pair/{self.request}")
        finally:
            conn.close()
        state = out.get("state") if isinstance(out.get("state"), str) else EXPIRED
        if state not in (WAITING, ACCEPTED, DENIED, EXPIRED, CANCELLED):
            state = EXPIRED
        return state

    def cancel(self):
        try:
            conn = self._conn()
            try:
                _call(conn, "POST", f"/mesh/pair/{self.request}/cancel", {})
            finally:
                conn.close()
        except Exception:
            pass
