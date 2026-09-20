"""Test vectors for the Android TV remote (dev.droplet.app.tv), made by the
androidtvremote2 library itself, the hub's implementation of the protocol.

Every byte string here comes out of the library's own code paths: its
PairingProtocol and RemoteProtocol are driven with a transport that records
what they write, and the pairing secrets are what its async_finish_pairing
computes and sends for certificates made by its own certificate generator.
The Kotlin tests (TvProtocolTest) check that droplet's code produces the
same bytes and reads the same messages.

    <hub venv>/bin/python android/app/src/test/python/tv_vectors.py \
        > android/app/src/test/resources/tv/vectors.json

Run it again only when the library changes; certificates and nonces are
random, so the output differs every run (both old and new are valid).
"""

import asyncio
import base64
import datetime
import importlib.metadata
import json
import os
import secrets
import sys
import tempfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from androidtvremote2.androidtv_remote import AndroidTVRemote
from androidtvremote2.certificate_generator import generate_selfsigned_cert
from androidtvremote2.exceptions import InvalidAuth
from androidtvremote2.pairing import PairingProtocol
from androidtvremote2.polo_pb2 import OuterMessage
from androidtvremote2.remote import RemoteProtocol
from androidtvremote2.remotemessage_pb2 import RemoteMessage
from google.protobuf.internal.encoder import _EncodeVarint


class Recorder:
    """Stands in for the TLS transport: records every write."""

    def __init__(self, server_der: bytes = b""):
        self.out = bytearray()
        self.server_der = server_der

    def write(self, data):
        self.out += data

    def is_closing(self):
        return False

    def close(self):
        pass

    def get_extra_info(self, name):
        if name == "ssl_object":
            der = self.server_der

            class Ssl:
                @staticmethod
                def getpeercert(binary=False):
                    return der

            return Ssl()
        return None

    def take(self) -> str:
        data, self.out = bytes(self.out), bytearray()
        return data.hex()


def framed(msg) -> str:
    data = msg.SerializeToString()
    out = bytearray()
    _EncodeVarint(out.extend, len(data))
    return (bytes(out) + data).hex()


def tv_cert() -> bytes:
    """A server certificate like a TV's (and the fake TV's): RSA 2048, name and MAC in the CN."""
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "atvremote/fake/fake/Vector TV/AA:BB:CC:DD:EE:02")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now)
            .not_valid_after(now + datetime.timedelta(days=3650)).sign(key, hashes.SHA256()))
    return cert.public_bytes(serialization.Encoding.DER)


async def secret_for(certfile: str, server_der: bytes, code: str):
    """What the library's async_finish_pairing sends for [code], or None when it refuses the code."""
    loop = asyncio.get_running_loop()
    proto = PairingProtocol(loop.create_future(), "droplet", certfile, loop)
    rec = Recorder(server_der)
    proto.connection_made(rec)
    task = asyncio.ensure_future(proto.async_finish_pairing(code))
    # the library reads the certificate file in a thread first
    for _ in range(5000):
        await asyncio.sleep(0.001)
        if rec.out or task.done():
            break
    if task.done():
        try:
            task.result()
        except InvalidAuth:
            return None
        raise RuntimeError("finished without sending")
    sent = rec.take()
    ack = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK)
    ack.secret_ack.secret = b"x"
    proto._handle_message(ack.SerializeToString())
    await task
    raw = bytes.fromhex(sent)
    # strip the varint length
    pos = 0
    while raw[pos] & 0x80:
        pos += 1
    msg = OuterMessage()
    msg.ParseFromString(raw[pos + 1:])
    return {"framed": sent, "secret": msg.secret.secret.hex()}


async def pairing_vectors(folder: str) -> list:
    cases = []
    for i in range(3):
        cert_pem, _key_pem = generate_selfsigned_cert("droplet")
        certfile = os.path.join(folder, f"client{i}.pem")
        with open(certfile, "wb") as f:
            f.write(cert_pem)
        server_der = tv_cert()
        good, bad = [], []
        while len(good) < 4:
            nonce = secrets.token_hex(2).upper()
            accepted = []
            for first in range(256):
                code = f"{first:02X}{nonce}"
                r = await secret_for(certfile, server_der, code)
                if r:
                    accepted.append((code, r))
                elif len(bad) < 6 and secrets.randbelow(40) == 0:
                    bad.append(code)
            # exactly one first byte matches the hash
            assert len(accepted) == 1, accepted
            code, r = accepted[0]
            good.append({"code": code, "secret": r["secret"], "framed": r["framed"]})
        # lowercase hex works too (bytes.fromhex and int(..., 16) don't care)
        low = good[0]["code"].lower()
        r = await secret_for(certfile, server_der, low)
        good.append({"code": low, "secret": r["secret"], "framed": r["framed"]})
        cases.append({
            "client_pem": cert_pem.decode(),
            "server_der": base64.b64encode(server_der).decode(),
            "good": good,
            "bad": bad,
        })
    return cases


async def polo_vectors(folder: str) -> dict:
    loop = asyncio.get_running_loop()
    out = {}
    proto = PairingProtocol(loop.create_future(), "droplet on Pixel 7", os.path.join(folder, "client0.pem"), loop)
    rec = Recorder()
    proto.connection_made(rec)
    task = asyncio.ensure_future(proto.async_start_pairing())
    await asyncio.sleep(0)
    out["pairing_request"] = {"client_name": "droplet on Pixel 7", "framed": rec.take()}

    ack = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK)
    ack.pairing_request_ack.server_name = "Living room TV"
    out["in_pairing_request_ack"] = framed(ack)
    proto._handle_message(ack.SerializeToString())
    out["options"] = rec.take()

    opts = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK)
    enc = opts.options.input_encodings.add()
    enc.type = 3
    enc.symbol_length = 6
    opts.options.preferred_role = 1
    out["in_options"] = framed(opts)
    proto._handle_message(opts.SerializeToString())
    out["configuration"] = rec.take()

    cack = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK)
    cack.configuration_ack.SetInParent()
    out["in_configuration_ack"] = framed(cack)
    proto._handle_message(cack.SerializeToString())
    await task

    sack = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK)
    sack.secret_ack.secret = bytes(range(32))
    out["in_secret_ack"] = framed(sack)
    out["in_bad_secret"] = framed(OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_BAD_SECRET))
    out["in_error"] = framed(OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_ERROR))
    return out


async def remote_vectors() -> dict:
    loop = asyncio.get_running_loop()
    out = {}

    def fresh():
        p = RemoteProtocol(loop.create_future(), loop.create_future(), lambda v: None, lambda v: None,
                           lambda v: None, loop, enable_ime=True, enable_voice=False)
        r = Recorder()
        p.connection_made(r)
        return p, r

    # the TV's configure (the fake's features, and everything), and the library's answers
    answers = []
    for features in (1 | 2 | 4 | 32 | 64 | 512, 0x3FF, 1 | 2 | 64):
        p, r = fresh()
        cfg = RemoteMessage()
        cfg.remote_configure.code1 = features
        cfg.remote_configure.device_info.model = "43P635"
        cfg.remote_configure.device_info.vendor = "TCL"
        cfg.remote_configure.device_info.unknown1 = 1
        cfg.remote_configure.device_info.unknown2 = "1"
        cfg.remote_configure.device_info.package_name = "com.google.android.tv.remote.service"
        cfg.remote_configure.device_info.app_version = "5.2.473254133"
        p._handle_message(cfg.SerializeToString())
        reply_cfg = r.take()
        act = RemoteMessage()
        act.remote_set_active.active = 622
        p._handle_message(act.SerializeToString())
        answers.append({"features": features, "in_configure": framed(cfg), "configure": reply_cfg,
                        "in_set_active": framed(act), "set_active": r.take(),
                        "model": p.device_info["model"], "vendor": p.device_info["manufacturer"],
                        "version": p.device_info["sw_version"]})
    out["handshake"] = answers

    p, r = fresh()
    ping = RemoteMessage()
    ping.remote_ping_request.val1 = 300
    ping.remote_ping_request.val2 = 9
    p._handle_message(ping.SerializeToString())
    out["ping"] = {"in": framed(ping), "reply": r.take(), "val1": 300}

    keys = []
    for key, direction, code, dir_no in (("DPAD_UP", "SHORT", 19, 3), ("DPAD_CENTER", "START_LONG", 23, 1),
                                         ("DPAD_CENTER", "END_LONG", 23, 2), ("POWER", "SHORT", 26, 3),
                                         ("VOLUME_MUTE", "SHORT", 164, 3), ("CHANNEL_UP", "SHORT", 166, 3),
                                         ("TV_INPUT", "SHORT", 178, 3), ("0", "SHORT", 7, 3)):
        p.send_key_command(key, direction)
        keys.append({"key": key, "direction": direction, "code": code, "dir": dir_no, "framed": r.take()})
    out["keys"] = keys

    texts = []
    p2, r2 = fresh()
    p2.send_text("hello")
    texts.append({"ime": 0, "field": 0, "text": "hello", "framed": r2.take()})
    batch = RemoteMessage()
    batch.remote_ime_batch_edit.ime_counter = 3
    batch.remote_ime_batch_edit.field_counter = 5
    p2._handle_message(batch.SerializeToString())
    out["in_batch_edit"] = {"framed": framed(batch), "ime": 3, "field": 5}
    for text in ("hello", "a", "Nairobi café ✓", "two words", "emoji \U0001F600"):
        p2.send_text(text)
        texts.append({"ime": 3, "field": 5, "text": text, "framed": r2.take()})
    out["texts"] = texts

    links = []
    for target in ("com.netflix.ninja", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "market://launch?id=com.plexapp.android"):
        # the AndroidTVRemote wrapper adds market://launch?id= to a bare package name
        tvr = AndroidTVRemote("droplet", "c", "k", "127.0.0.1", loop=loop)
        tvr._remote_message_protocol = p
        tvr.send_launch_app_command(target)
        links.append({"target": target, "framed": r.take()})
    out["links"] = links

    ins = {}
    m = RemoteMessage()
    m.remote_start.started = True
    ins["start_on"] = framed(m)
    m = RemoteMessage()
    m.remote_start.started = False
    ins["start_off"] = framed(m)
    m = RemoteMessage()
    m.remote_set_volume_level.volume_level = 12
    m.remote_set_volume_level.volume_max = 100
    m.remote_set_volume_level.volume_muted = True
    m.remote_set_volume_level.player_model = "TCL speaker"
    m.remote_set_volume_level.unknown1 = 3
    ins["volume"] = framed(m)
    m = RemoteMessage()
    m.remote_set_volume_level.volume_level = 0
    m.remote_set_volume_level.volume_max = 60
    ins["volume_zero"] = framed(m)
    m = RemoteMessage()
    m.remote_ime_key_inject.app_info.app_package = "com.google.android.youtube.tv"
    m.remote_ime_key_inject.app_info.counter = 4
    m.remote_ime_key_inject.text_field_status.value = "abc"
    ins["app"] = framed(m)
    m = RemoteMessage()
    m.remote_error.value = True
    m.remote_error.message.remote_key_inject.key_code = 26
    ins["error"] = framed(m)
    m = RemoteMessage()
    m.remote_voice_begin.session_id = 5
    ins["unhandled_voice_begin"] = framed(m)
    out["in"] = ins
    return out


async def main():
    with tempfile.TemporaryDirectory() as folder:
        data = {
            "library": "androidtvremote2 " + importlib.metadata.version("androidtvremote2"),
            "pairing": await pairing_vectors(folder),
            "polo": await polo_vectors(folder),
            "remote": await remote_vectors(),
        }
    json.dump(data, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    asyncio.run(main())
