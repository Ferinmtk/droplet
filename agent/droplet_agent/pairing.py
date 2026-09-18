"""Waiting to be let in: a new device on the LAN asks, and one of yours answers.

`POST /api/device` from the LAN comes back pending, with a four-digit code
that the hub also shows on your other devices. Then `GET /api/me` (with the
new token) says how it went (docs/local-first.md §4):

- `device.pending` set: still waiting;
- `device` without `pending`: allowed in;
- `device: null`: denied, or the request expired.
"""

from __future__ import annotations

import time

from .hub import HubError, PinMismatch

PENDING = "pending"
APPROVED = "approved"
DENIED = "denied"
TIMED_OUT = "timed out"

POLL_EVERY = 2.5
MAX_ERRORS = 12       # in a row, about half a minute: the hub is gone, not just slow
WAIT_AT_MOST = 15 * 60  # the hub keeps the request for a day; setup can pick it up again


def state_of(device: dict | None) -> str:
    """What /api/me's `device` means for a device waiting to be let in."""
    if not isinstance(device, dict):
        return DENIED
    return PENDING if device.get("pending") else APPROVED


def wait_for_approval(check, *, every: float = POLL_EVERY, max_errors: int = MAX_ERRORS,
                      timeout: float = WAIT_AT_MOST, sleep=time.sleep, clock=time.monotonic,
                      on_error=None) -> str:
    """Poll `check()` (→ /api/me's device) until approved, denied or `timeout` seconds pass.

    A hub that can't be reached for a moment doesn't end the wait;
    `max_errors` failures in a row do (the last error is raised). A
    certificate that isn't the pinned one ends it at once.
    """
    end = clock() + timeout
    errors = 0
    while True:
        try:
            state = state_of(check())
            errors = 0
        except PinMismatch:
            raise
        except HubError as e:
            errors += 1
            if errors >= max_errors:
                raise
            if on_error is not None:
                on_error(e)
            state = PENDING
        if state != PENDING:
            return state
        if clock() >= end:
            return TIMED_OUT
        sleep(every)
