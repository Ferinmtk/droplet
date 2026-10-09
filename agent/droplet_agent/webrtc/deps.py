"""aiortc, without PyAV.

aiortc gives us DTLS (through pyOpenSSL) and SCTP with data channels, in
Python. It also does audio and video, for which it needs PyAV (FFmpeg,
about 100 MB). A data channel never touches it, but aiortc imports it at
the top of several modules. So when PyAV isn't installed, `load()` puts a
stand-in `av` module in its place first: every name in it is an empty
class, which is all those imports need. Media would fail, and we have none.

Install it lightly with:

    pip install --no-deps aiortc
    pip install aioice pyee pylibsrtp pyopenssl google-crc32c

(`pip install 'droplet-agent[iphone]'` is the same, plus PyAV: simpler, heavier.)
"""

from __future__ import annotations

import importlib.abc
import importlib.machinery
import importlib.util
import sys
import types

MISSING_HINT = ("the iPhone link needs aiortc: pip install --no-deps aiortc && "
                "pip install aioice pyee pylibsrtp pyopenssl google-crc32c")


class _Stub(types.ModuleType):
    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        cls = type(name, (), {"__module__": self.__name__})
        setattr(self, name, cls)
        return cls


class _StubFinder(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "av" or fullname.startswith("av."):
            return importlib.machinery.ModuleSpec(fullname, self, is_package=True)
        return None

    def create_module(self, spec):
        m = _Stub(spec.name)
        m.__path__ = []
        m.DROPLET_STUB = True
        return m

    def exec_module(self, module):
        pass


def _have(name: str) -> bool:
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError):
        return False


def available() -> tuple[bool, str]:
    """Whether the iPhone link can run here, and if not, why."""
    for mod in ("aiortc", "aioice", "OpenSSL", "pylibsrtp", "google_crc32c", "pyee"):
        if not _have(mod):
            return False, MISSING_HINT
    return True, ""


def load():
    """Import aiortc's pieces we use; PyAV is stubbed out when it isn't installed."""
    if "av" not in sys.modules and not _have("av"):
        sys.meta_path.insert(0, _StubFinder())
    from aiortc.rtcdtlstransport import RTCCertificate, RTCDtlsFingerprint, RTCDtlsParameters, RTCDtlsTransport
    from aiortc.rtcdatachannel import RTCDataChannel, RTCDataChannelParameters
    from aiortc.rtcsctptransport import RTCSctpCapabilities, RTCSctpTransport
    return types.SimpleNamespace(
        RTCCertificate=RTCCertificate, RTCDtlsFingerprint=RTCDtlsFingerprint,
        RTCDtlsParameters=RTCDtlsParameters, RTCDtlsTransport=RTCDtlsTransport,
        RTCDataChannel=RTCDataChannel, RTCDataChannelParameters=RTCDataChannelParameters,
        RTCSctpCapabilities=RTCSctpCapabilities, RTCSctpTransport=RTCSctpTransport,
    )
