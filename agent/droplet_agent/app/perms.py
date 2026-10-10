"""Your device, or someone else's: the choice when pairing, and each device's Permissions.

The switches are the agent's (mesh/perms.py): this computer enforces them, both ways,
whatever the other device does.
"""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QCheckBox, QDialog, QPushButton, QSizePolicy, QWidget

WIDTH = 560    # the Permissions dialog's

from . import model
from .widgets import button, icon, icon_label, label, primary, vbox, hbox


class Choice(QPushButton):
    """A big button: a title, and one line on what it means. Checkable, for the dialog."""

    def __init__(self, key: str, heading: str, text: str, icon_names=()):
        super().__init__()
        self.key = key
        self.setObjectName("choice")
        self.setCheckable(False)
        self.setAutoDefault(False)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
        self.heading = label(heading, size=2, bold=True)
        self.text = label(text, muted=True, wrap=True)
        parts = [self.heading, self.text]
        for w in parts:
            w.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        pic = icon_label(icon(*icon_names), 32) if icon_names else None
        if pic is not None:
            pic.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.setLayout(hbox(*([pic] if pic else []), vbox(*parts, spacing=3), spacing=14,
                            margins=(16, 12, 16, 12)))
        self.setAccessibleName(f"{heading}. {text}")
        self.setMinimumHeight(76)

    def sizeHint(self):
        return self.layout().sizeHint()


CHOICE_ICONS = {"own": ("user-home", "computer", "go-home"), "other": ("system-users", "user-identity", "contact-new")}


class RelationChoice(QWidget):
    """"My device" or "Someone else's", two big choices with the defaults in a line each."""

    chosen = Signal(str)

    def __init__(self):
        super().__init__()
        self.buttons = {}
        for key, heading, text in model.RELATION_CHOICES:
            b = Choice(key, heading, text, CHOICE_ICONS[key])
            b.clicked.connect(lambda _=False, k=key: self.chosen.emit(k))
            self.buttons[key] = b
        self.setLayout(vbox(*self.buttons.values(), spacing=10))
        self.setMaximumWidth(520)


class RelationDialog(QDialog):
    """Accepting a device that asks to pair: is it yours, or someone else's? `relation` once chosen."""

    def __init__(self, parent, name: str, code: str = ""):
        super().__init__(parent)
        self.setWindowTitle(f"Pair with {name}")
        self.relation: str | None = None
        head = label(f"Is {name} your device, or someone else's?", size=3, bold=True, wrap=True)
        sub = label((f"Code {model.spaced_code(code)} matches. " if code else "")
                    + "This decides what it may do here; change it any time under Permissions.",
                    muted=True, wrap=True)
        choice = RelationChoice()
        choice.chosen.connect(self._chose)
        cancel = button("Cancel")
        cancel.clicked.connect(self.reject)
        self.choice = choice
        self.setLayout(vbox(head, sub, 6, choice, hbox(None, cancel), spacing=10, margins=(20, 18, 20, 16)))
        self.setMinimumWidth(460)

    def _chose(self, relation: str):
        self.relation = relation
        self.accept()


class PermissionsDialog(QDialog):
    """A device's switches: what it may do, with a line on each, and whose device it is."""

    def __init__(self, parent, peer: dict, capabilities: list | None = None):
        super().__init__(parent)
        from ..mesh import perms
        name = str(peer.get("name") or "this device")
        self.peer = peer
        self.setWindowTitle(f"Permissions for {name}")
        caps = [c for c in (capabilities or list(perms.CAPABILITIES)) if c in perms.CAPABILITIES]
        allow = dict(perms.clean_allow(peer.get("allow"), peer.get("relation") or "own"))
        self.relation = peer.get("relation") or "own"

        head = label(f"What {name} may do", size=3, bold=True, wrap=True)
        sub = label("This computer enforces these, both ways: nothing switched off is sent to it or taken "
                    "from it.", muted=True, wrap=True)
        self.own = button("My device")
        self.other = button("Someone else's")
        for b in (self.own, self.other):
            b.setCheckable(True)
            b.setObjectName("segment")
        self.own.clicked.connect(lambda: self._relation("own"))
        self.other.clicked.connect(lambda: self._relation("other"))
        rel_note = label("Choosing one sets its defaults below.", muted=True)

        self.boxes: dict[str, QCheckBox] = {}
        rows = []
        for c in caps:
            cb = QCheckBox(perms.CAPABILITIES[c])
            cb.setChecked(bool(allow.get(c, True)))
            self.boxes[c] = cb
            why = label(perms.EXPLAIN[c], muted=True, wrap=True)
            why.setContentsMargins(26, 0, 0, 4)
            rows += [cb, why]
        save = primary("Save")
        cancel = button("Cancel")
        save.clicked.connect(self.accept)
        cancel.clicked.connect(self.reject)
        # wrapped lines as wide as the dialog, so each gets the height it needs and none is clipped
        for w in (head, sub, *rows[1::2]):
            w.setMinimumWidth(WIDTH - 40 - (26 if w in rows else 0))
        self.setLayout(vbox(head, sub, 8, hbox(label("Whose device:", bold=True), self.own, self.other, None),
                            rel_note, 8, *rows, 8, hbox(None, cancel, save), spacing=4, margins=(20, 18, 20, 16)))
        self._mark()
        self.resize(self.sizeHint())

    def _mark(self):
        self.own.setChecked(self.relation == "own")
        self.other.setChecked(self.relation == "other")

    def _relation(self, relation: str):
        from ..mesh import perms
        self.relation = relation
        for c, cb in self.boxes.items():
            cb.setChecked(perms.defaults(relation)[c])
        self._mark()

    def values(self) -> dict:
        """The control request that saves it."""
        return {"cmd": "perm-set", "peer": self.peer.get("fp"), "relation": self.relation,
                "allow": {c: cb.isChecked() for c, cb in self.boxes.items()}}
