"""aiortc, without PyAV.

aiortc gives us DTLS (through pyOpenSSL) and SCTP with data channels, in
Python. It also does audio and video, for which it needs PyAV (FFmpeg,
about 100 MB). A data channel never touches it, but aiortc imports it at
the top of several modules. So when PyAV isn't installed, `load()` puts a
stand-in `av` module in its place first: every name in it is an empty
class, which is all those imports need. Media would fail, and we have none.

So droplet always imports aiortc through `load()`: imported directly with no
PyAV, it fails with "No module named 'av'", and that's expected.

The agent's own dependencies are the light ones aiortc needs (aioice, pyee,
pylibsrtp, pyOpenSSL, google-crc32c). aiortc itself comes with
`pip install --no-deps aiortc`, which install.sh runs and `fix_command()`
spells out (`droplet-agent doctor` offers to run it).
"""

from __future__ import annotations

import importlib.abc
import importlib.machinery
import importlib.util
import shlex
import sys
import types

# what the link imports → the package that brings it (pip's name)
NEEDED = {"aioice": "aioice", "OpenSSL": "pyopenssl", "pylibsrtp": "pylibsrtp",
          "google_crc32c": "google-crc32c", "pyee": "pyee"}
AIORTC = "aiortc>=1.9"


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


def missing() -> list[str]:
    """The packages the iPhone link still needs here (pip's names); [] when it can run."""
    out = [pkg for mod, pkg in NEEDED.items() if not _have(mod)]
    if not _have("aiortc"):
        out.append("aiortc")
    return out


def fix_command(python: str | None = None) -> str:
    """The one command that installs what's missing, leaving PyAV out ("" when nothing is)."""
    need = missing()
    if not need:
        return ""
    py = shlex.quote(python or sys.executable)
    steps = []
    light = [p for p in need if p != "aiortc"]
    if light:
        steps.append(f"{py} -m pip install {' '.join(light)}")
    if "aiortc" in need:
        steps.append(f"{py} -m pip install --no-deps '{AIORTC}'")
    return " && ".join(steps)


def available() -> tuple[bool, str]:
    """Whether the iPhone link can run here, and if not, why, with the command that fixes it."""
    need = missing()
    if not need:
        return True, ""
    return False, (f"the iPhone link isn't installed here (it needs {', '.join(need)}). "
                   f"Install it with: {fix_command()}")


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
