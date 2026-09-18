"""A pretend Google TV that speaks the Android TV Remote protocol v2, for testing.

It's the TV's side of what androidtvremote2 implements: TLS on two ports,
the "polo" pairing handshake on the upper one (a 6-character code appears
"on screen", here in the terminal and the state file), then remote commands
on the lower one, which only paired clients get into. It keeps a little
state (on/off, the app in front, volume) and reports it like a real TV does.

It's close enough to exercise droplet's tv.py end to end, over real sockets,
with the real library: pairing, a wrong code, keys, text, app links, power,
the TV forgetting droplet. It isn't a real TV. Timing, and whatever a given
TV actually does with a key, still need the real thing.

    python tests/fake_tv.py --client-cert <DROPLET_HOME>/tv/cert.pem [--port 6466] [--state out.json]

The client cert is the one droplet creates on its first pairing; it needn't
exist yet. The fake trusts it on the pairing port (a real TV would take any
client there), and on the command port only once pairing succeeded.
"""

import argparse
import asyncio
import datetime
import hashlib
import json
import os
import secrets
import socket
import ssl
import sys
import tempfile
import threading
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from google.protobuf.internal.encoder import _EncodeVarint

from androidtvremote2.polo_pb2 import Options, OuterMessage
from androidtvremote2.remotemessage_pb2 import RemoteKeyCode, RemoteMessage

NAME = "Fake Google TV"
MAC = "AA:BB:CC:DD:EE:01"
FEATURES = 1 | 2 | 4 | 32 | 64 | 512   # ping, key, ime, power, volume, app link
HOME_APP = "com.google.android.apps.tv.launcherx"


def make_server_cert(folder: Path) -> tuple[str, str]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    # the subject a real TV uses: the library reads the name and MAC out of it
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, f"atvremote/fake/fake/{NAME}/{MAC}")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=30)).sign(key, hashes.SHA256()))
    c, k = folder / "server.pem", folder / "server.key"
    c.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    k.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                                    serialization.NoEncryption()))
    return str(c), str(k)


def rsa_numbers(cert: x509.Certificate) -> tuple[int, int]:
    n = cert.public_key().public_numbers()
    return n.n, n.e


class FakeTV:
    def __init__(self, client_cert: str, state_file: str | None):
        self.client_cert = client_cert
        self.state_file = state_file
        self.tmp = tempfile.TemporaryDirectory()
        self.server_cert, self.server_key = make_server_cert(Path(self.tmp.name))
        self.paired = False
        self.code: str | None = None       # what's "on screen" while pairing
        self.on = True
        self.app = HOME_APP
        self.volume = {"level": 12, "max": 100, "muted": False}
        self.log: list = []                # everything a client did
        self.remotes: set = set()
        self.lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.save()

    # --- TLS: which client certificates each port lets in ---

    def _ctx(self, trust_client: bool) -> ssl.SSLContext:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        # TLS 1.2 so a refused client certificate fails the handshake itself,
        # as it does on the TVs the library was written against
        ctx.maximum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(self.server_cert, self.server_key)
        ctx.verify_mode = ssl.CERT_REQUIRED
        if trust_client and os.path.exists(self.client_cert):
            ctx.load_verify_locations(self.client_cert)
        return ctx

    def pair_context(self) -> ssl.SSLContext:
        ctx = self._ctx(True)
        # re-read the client cert on every connection: droplet makes it on first use
        ctx.sni_callback = lambda sslobj, _name, _ctx: setattr(sslobj, "context", self._ctx(True))
        return ctx

    def save(self):
        if not self.state_file:
            return
        with self.state_lock:  # the command port's threads log too
            tmp = self.state_file + ".tmp"
            with open(tmp, "w") as f:
                json.dump({"paired": self.paired, "code": self.code, "on": self.on, "app": self.app,
                           "volume": self.volume, "log": self.log, "connections": len(self.remotes)}, f, indent=1)
            os.replace(tmp, self.state_file)

    def event(self, *what):
        self.log.append(list(what))
        print("fake tv:", *what, flush=True)
        self.save()

    # --- framing: varint length, then a protobuf message ---

    @staticmethod
    async def read(reader: asyncio.StreamReader, cls):
        shift = size = 0
        while True:
            b = (await reader.readexactly(1))[0]
            size |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        msg = cls()
        msg.ParseFromString(await reader.readexactly(size))
        return msg

    @staticmethod
    def write(writer: asyncio.StreamWriter, msg):
        data = msg.SerializeToString()
        out = bytearray()
        _EncodeVarint(out.extend, len(data))
        writer.write(bytes(out) + data)

    # --- pairing port ---

    async def pairing(self, reader, writer):
        ssl_obj = writer.get_extra_info("ssl_object")
        client = x509.load_der_x509_certificate(ssl_obj.getpeercert(True))
        server = x509.load_pem_x509_certificate(Path(self.server_cert).read_bytes())
        secret = None
        try:
            while True:
                msg = await self.read(reader, OuterMessage)
                out = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK)
                if msg.HasField("pairing_request"):
                    self.event("pairing_request", msg.pairing_request.client_name)
                    out.pairing_request_ack.server_name = NAME
                elif msg.HasField("options"):
                    enc = out.options.input_encodings.add()
                    enc.type = Options.Encoding.ENCODING_TYPE_HEXADECIMAL
                    enc.symbol_length = 6
                    out.options.preferred_role = Options.RoleType.ROLE_TYPE_INPUT
                elif msg.HasField("configuration"):
                    out.configuration_ack.SetInParent()
                    # the code on screen: first byte of the secret, then a random nonce
                    nonce = secrets.token_bytes(2)
                    cm, ce = rsa_numbers(client)
                    sm, se = rsa_numbers(server)
                    h = hashlib.sha256()
                    for part in (f"{cm:X}", f"0{ce:X}", f"{sm:X}", f"0{se:X}"):
                        h.update(bytes.fromhex(part))
                    h.update(nonce)
                    secret = h.digest()
                    self.code = f"{secret[0]:02X}{nonce.hex().upper()}"
                    self.event("code_on_screen", self.code)
                elif msg.HasField("secret"):
                    if secret is None or msg.secret.secret != secret:
                        self.event("bad_secret")
                        out = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_BAD_SECRET)
                        self.write(writer, out)
                        break
                    out.secret_ack.secret = secret
                    self.paired = True
                    self.code = None
                    self.event("paired")
                else:
                    break
                self.write(writer, out)
                await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionError, ssl.SSLError):
            pass
        finally:
            if self.code:
                self.event("pairing_screen_closed")
            self.code = None
            self.save()
            writer.close()

    # --- command port ---

    def status_messages(self) -> list:
        start = RemoteMessage()
        start.remote_start.started = self.on
        vol = RemoteMessage()
        vol.remote_set_volume_level.volume_level = self.volume["level"]
        vol.remote_set_volume_level.volume_max = self.volume["max"]
        vol.remote_set_volume_level.volume_muted = self.volume["muted"]
        app = RemoteMessage()
        app.remote_ime_key_inject.app_info.app_package = self.app
        return [start, vol, app]

    # The command port runs on plain blocking sockets in threads, because a
    # refused client certificate has to reach the client as a TLS alert: that's
    # how the library tells "pair again" from "TV unreachable". asyncio's TLS
    # server aborts the connection before the alert goes out.

    def send(self, sock, msg):
        data = msg.SerializeToString()
        out = bytearray()
        _EncodeVarint(out.extend, len(data))
        with self.lock:
            sock.sendall(bytes(out) + data)

    def broadcast(self, msgs):
        for sock in list(self.remotes):
            for m in msgs:
                try:
                    self.send(sock, m)
                except OSError:
                    pass

    def serve_commands(self, host: str, port: int):
        listener = socket.create_server((host, port), reuse_port=False)
        while True:
            conn, _ = listener.accept()
            threading.Thread(target=self.command_conn, args=(conn,), daemon=True).start()

    def command_conn(self, raw):
        try:
            sock = self._ctx(self.paired).wrap_socket(raw, server_side=True)
        except (ssl.SSLError, OSError) as e:
            self.event("refused_client", type(e).__name__)
            raw.close()
            return
        self.remotes.add(sock)
        self.event("remote_connected")
        cfg = RemoteMessage()
        cfg.remote_configure.code1 = FEATURES
        cfg.remote_configure.device_info.model = "43P"
        cfg.remote_configure.device_info.vendor = "FakeTCL"
        cfg.remote_configure.device_info.package_name = "com.google.android.tv.remote.service"
        cfg.remote_configure.device_info.app_version = "5.2"
        pings = 0
        buf = b""
        try:
            self.send(sock, cfg)
            sock.settimeout(5)   # quiet for 5 s: ping, like a TV does
            while True:
                try:
                    chunk = sock.recv(4096)
                except TimeoutError:
                    pings += 1
                    p = RemoteMessage()
                    p.remote_ping_request.val1 = pings
                    self.send(sock, p)
                    continue
                if not chunk:
                    break
                buf += chunk
                while buf:
                    size = shift = pos = 0
                    while pos < len(buf):
                        b = buf[pos]
                        size |= (b & 0x7F) << shift
                        shift += 7
                        pos += 1
                        if not b & 0x80:
                            break
                    else:
                        break  # length not complete yet
                    if len(buf) < pos + size:
                        break
                    msg = RemoteMessage()
                    msg.ParseFromString(buf[pos:pos + size])
                    buf = buf[pos + size:]
                    self.handle(sock, msg)
        except (OSError, ssl.SSLError):
            pass
        finally:
            self.remotes.discard(sock)
            self.event("remote_disconnected")
            try:
                sock.close()
            except OSError:
                pass

    def handle(self, sock, msg):
        if msg.HasField("remote_configure"):
            act = RemoteMessage()
            act.remote_set_active.active = FEATURES
            self.send(sock, act)
        elif msg.HasField("remote_set_active"):
            for m in self.status_messages():
                self.send(sock, m)
            ime = RemoteMessage()   # a text field is focused, so text can be typed
            ime.remote_ime_batch_edit.ime_counter = 1
            ime.remote_ime_batch_edit.field_counter = 1
            self.send(sock, ime)
        elif msg.HasField("remote_key_inject"):
            self.key(RemoteKeyCode.Name(msg.remote_key_inject.key_code), msg.remote_key_inject.direction)
        elif msg.HasField("remote_ime_batch_edit"):
            for e in msg.remote_ime_batch_edit.edit_info:
                self.event("text", e.text_field_status.value)
        elif msg.HasField("remote_app_link_launch_request"):
            link = msg.remote_app_link_launch_request.app_link
            self.event("launch", link)
            if link.startswith("market://launch?id="):
                self.app = link.split("=", 1)[1]
                self.broadcast(self.status_messages()[2:])

    def key(self, name: str, direction: int):
        self.event("key", name.removeprefix("KEYCODE_"), {1: "START_LONG", 2: "END_LONG", 3: "SHORT"}.get(direction, direction))
        if direction == 1:  # the press is reported once, on the way down
            return
        v = self.volume
        if name == "KEYCODE_POWER":
            self.on = not self.on
            self.broadcast(self.status_messages()[:1])
        elif name == "KEYCODE_VOLUME_UP":
            v["level"], v["muted"] = min(v["max"], v["level"] + 1), False
            self.broadcast(self.status_messages()[1:2])
        elif name == "KEYCODE_VOLUME_DOWN":
            v["level"] = max(0, v["level"] - 1)
            self.broadcast(self.status_messages()[1:2])
        elif name == "KEYCODE_VOLUME_MUTE":
            v["muted"] = not v["muted"]
            self.broadcast(self.status_messages()[1:2])
        elif name == "KEYCODE_HOME":
            self.app = HOME_APP
            self.broadcast(self.status_messages()[2:])

    def forget(self):
        """What "unpair" on the TV does: drop the client, refuse it from now on."""
        self.paired = False
        for sock in list(self.remotes):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.event("forgot_client")


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--client-cert", required=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=6466)
    ap.add_argument("--state")
    ap.add_argument("--paired", action="store_true", help="start already trusting the client cert")
    args = ap.parse_args()
    tv = FakeTV(args.client_cert, args.state)
    tv.paired = args.paired
    threading.Thread(target=tv.serve_commands, args=(args.host, args.port), daemon=True).start()
    pair = await asyncio.start_server(tv.pairing, args.host, args.port + 1, ssl=tv.pair_context())
    print(f"fake tv: listening on {args.host}:{args.port} (commands) and :{args.port + 1} (pairing)", flush=True)
    # SIGUSR1 = the user unpairs droplet in the TV's settings
    import signal
    asyncio.get_running_loop().add_signal_handler(signal.SIGUSR1, tv.forget)
    async with pair:
        await pair.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
