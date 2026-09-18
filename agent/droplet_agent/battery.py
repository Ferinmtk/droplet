"""Battery level for the "battery" state, from /sys/class/power_supply."""

from __future__ import annotations

from pathlib import Path

ROOT = Path("/sys/class/power_supply")


def _read(p: Path) -> str | None:
    try:
        return p.read_text().strip()
    except OSError:
        return None


def read(root: Path = ROOT) -> dict | None:
    """{"level": 0..100, "charging": bool}, or None when there's no system battery.

    "charging" means plugged in: a battery held at a charge threshold
    reports "Not charging" while on mains, and that still counts.
    """
    batteries, ac_online = [], None
    try:
        supplies = sorted(root.iterdir())
    except OSError:
        return None
    for d in supplies:
        kind = _read(d / "type")
        if kind == "Mains":
            online = _read(d / "online")
            if online is not None:
                ac_online = bool(ac_online) or online == "1"
        elif kind == "Battery" and _read(d / "scope") != "Device":
            # scope "Device" is a mouse's or headset's battery, not the computer's
            batteries.append(d)
    levels, weights, statuses = [], [], []
    for b in batteries:
        cap = _read(b / "capacity")
        if cap is None or not cap.lstrip("-").isdigit():
            continue
        full = _read(b / "energy_full") or _read(b / "charge_full")
        levels.append(max(0, min(100, int(cap))))
        weights.append(int(full) if full and full.isdigit() and int(full) > 0 else 1)
        statuses.append(_read(b / "status") or "")
    if not levels:
        return None
    level = round(sum(lv * w for lv, w in zip(levels, weights)) / sum(weights))
    charging = any(s == "Charging" for s in statuses) or (
        bool(ac_online) and all(s in ("Full", "Not charging", "Charging") for s in statuses))
    return {"level": level, "charging": charging}
