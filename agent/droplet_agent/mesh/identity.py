"""This device's mesh identity: an EC P-256 key and a long-lived self-signed certificate.

The fingerprint, the SHA-256 of the certificate's DER encoding, *is* the
peer's identity (docs/mesh.md §1). It's made once and kept in the agent's
config directory, owner-only; it only changes if those files are deleted.

The peer id is the hub's device id when this computer is linked to a hub,
so the web app, the hub and the mesh all name it the same way, and
otherwise a random 16-hex id made once and kept beside the key.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
import secrets
import ssl
from dataclasses import dataclass
from pathlib import Path

PEER_ID = re.compile(r"^[0-9a-f]{8,64}$")
FINGERPRINT = re.compile(r"^[0-9a-f]{64}$")

# Validity: from a fixed date in the past, so a peer whose clock is a little
# (or a lot) behind still accepts it, to about 30 years out. OpenSSL checks
# the dates of a trust anchor too, so the certificate must never lapse in use.
NOT_BEFORE = datetime.datetime(2020, 1, 1, tzinfo=datetime.timezone.utc)
LIFETIME = datetime.timedelta(days=365 * 30)


def fingerprint(der: bytes) -> str:
    """SHA-256 of a DER certificate, lowercase hex."""
    return hashlib.sha256(der).hexdigest()


def normalize_fingerprint(fp) -> str | None:
    if not isinstance(fp, str):
        return None
    fp = fp.replace(":", "").strip().lower()
    return fp if FINGERPRINT.match(fp) else None


def pem_to_der(pem: str) -> bytes:
    """The DER of a single PEM certificate. Raises ValueError if it isn't one."""
    if not isinstance(pem, str) or pem.count("-----BEGIN CERTIFICATE-----") != 1:
        raise ValueError("not a single PEM certificate")
    try:
        der = ssl.PEM_cert_to_DER_cert(pem.strip() + "\n")
    except (ValueError, TypeError) as e:
        raise ValueError(f"not a PEM certificate: {e}") from e
    # parse it for real: a PEM wrapper around garbage must not be trusted
    from cryptography import x509
    try:
        x509.load_der_x509_certificate(der)
    except Exception as e:
        raise ValueError(f"not a valid certificate: {e}") from e
    return der


def der_to_pem(der: bytes) -> str:
    return ssl.DER_cert_to_PEM_cert(der)


@dataclass
class Identity:
    key_path: Path
    cert_path: Path
    cert_pem: str
    der: bytes
    fp: str
    local_id: str      # the random id, used when not linked to a hub

    def peer_id(self, hub_device_id: str | None) -> str:
        if hub_device_id and PEER_ID.match(hub_device_id):
            return hub_device_id
        return self.local_id


def _write_private(path: Path, data: bytes):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.chmod(path, 0o600)


def _generate(key_path: Path, cert_path: Path, local_id: str):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    # a unique subject: a trust store holding many peers must never mix two up
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, f"droplet-peer-{local_id}")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(min(NOT_BEFORE, now))
        .not_valid_after(now + LIFETIME)
        # not a CA: it can't be used to vouch for any other certificate
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                     data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                     crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH, ExtendedKeyUsageOID.CLIENT_AUTH]),
                       critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .sign(key, hashes.SHA256())
    )
    # the key first, then the certificate: a certificate with no key would be useless
    _write_private(key_path, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                               serialization.NoEncryption()))
    _write_private(cert_path, cert.public_bytes(serialization.Encoding.PEM))


def load_or_create(directory: Path) -> Identity:
    """This device's identity, made on first use. `directory` is made 700."""
    directory.mkdir(parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    key_path, cert_path, meta_path = directory / "key.pem", directory / "cert.pem", directory / "identity.json"
    try:
        meta = json.loads(meta_path.read_text())
    except (OSError, ValueError):
        meta = {}
    local_id = meta.get("id") if isinstance(meta, dict) else None
    if not isinstance(local_id, str) or not re.fullmatch(r"[0-9a-f]{16}", local_id):
        local_id = secrets.token_hex(8)
        _write_private(meta_path, (json.dumps({"id": local_id}) + "\n").encode())
    if not (key_path.exists() and cert_path.exists()):
        _generate(key_path, cert_path, local_id)
    for p in (key_path, cert_path, meta_path):
        if p.stat().st_mode & 0o077:
            os.chmod(p, 0o600)
    pem = cert_path.read_text()
    der = pem_to_der(pem)
    # the key and the certificate must belong together
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.load_cert_chain(str(cert_path), str(key_path))
    return Identity(key_path, cert_path, der_to_pem(der), der, fingerprint(der), local_id)


def sign(identity: Identity, data: bytes) -> bytes:
    """ECDSA-SHA256 over data, DER-encoded (the usual X9.62 form, as Java and OpenSSL give it)."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = serialization.load_pem_private_key(identity.key_path.read_bytes(), password=None)
    return key.sign(data, ec.ECDSA(hashes.SHA256()))


def verify(cert_der: bytes, signature: bytes, data: bytes) -> bool:
    """Whether `signature` over `data` was made by the key in `cert_der`.

    EC keys: ECDSA-SHA256, DER signature. RSA keys (accepted for leniency):
    PKCS#1 v1.5 with SHA-256.
    """
    from cryptography import x509
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

    try:
        pub = x509.load_der_x509_certificate(cert_der).public_key()
        if isinstance(pub, ec.EllipticCurvePublicKey):
            pub.verify(signature, data, ec.ECDSA(hashes.SHA256()))
        elif isinstance(pub, rsa.RSAPublicKey):
            pub.verify(signature, data, padding.PKCS1v15(), hashes.SHA256())
        else:
            return False
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False
