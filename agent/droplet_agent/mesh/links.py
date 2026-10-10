"""Links sent to another device (docs/mesh.md §9.10).

Only web addresses travel as links, and only those are ever opened: `http` and `https`,
with a host, no spaces or control characters, and at most MAX_URL characters. Anything
else (`file:`, `javascript:`, `data:`, an app's own scheme) is refused when it's sent and
when it arrives, and never opened.
"""

from __future__ import annotations

import re
from urllib.parse import urlsplit

MAX_URL = 2048
SCHEMES = ("http", "https")


def check_url(v) -> str:
    """The URL, trimmed, if it's one that may be sent and opened; else ValueError saying why."""
    if not isinstance(v, str):
        raise ValueError("a link must be text")
    url = v.strip()
    if not url:
        raise ValueError("the link is empty")
    if len(url) > MAX_URL:
        raise ValueError(f"that link is too long ({MAX_URL} characters at most)")
    if any(ch.isspace() or not ch.isprintable() for ch in url):
        raise ValueError("a link can't hold spaces or control characters")
    try:
        parts = urlsplit(url)
    except ValueError:
        raise ValueError("that isn't a web address") from None
    if parts.scheme.lower() not in SCHEMES:
        raise ValueError("only web links (http:// or https://) can be sent and opened")
    if not parts.hostname:
        raise ValueError("that link has no address in it")
    return url


def is_url(v) -> bool:
    try:
        check_url(v)
        return True
    except ValueError:
        return False


def only_url(text) -> str | None:
    """A chat message that's nothing but a web link: the link (for an Open button), else None."""
    if not isinstance(text, str):
        return None
    t = text.strip()
    if not re.match(r"(?i)https?://", t):
        return None
    return t if is_url(t) else None
