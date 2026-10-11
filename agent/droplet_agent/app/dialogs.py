"""Small dialogs that ask for one thing: this computer's name, a nickname, a message or a link
for several devices. Each checks what's typed as the agent will, and says why it can't be used
before anything is sent."""

from __future__ import annotations

from PySide6.QtWidgets import QDialog, QLineEdit, QPlainTextEdit

from .widgets import button, hbox, label, primary, text_color, vbox


class AskDialog(QDialog):
    """A heading, a line on what it's for, a field, and OK/Cancel. `check(text)` returns the
    value to use or raises ValueError saying why not; `multiline` for a message."""

    def __init__(self, parent, title: str, heading: str, text: str, *, value: str = "", placeholder: str = "",
                 ok: str = "Save", check=None, multiline: bool = False, note: str = ""):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setMinimumWidth(460)
        self.check = check or (lambda v: v)
        self.value = None
        if multiline:
            self.field = QPlainTextEdit()
            self.field.setPlainText(value)
            self.field.setPlaceholderText(placeholder)
            self.field.setFixedHeight(96)
            self.field.textChanged.connect(self._changed)
        else:
            self.field = QLineEdit(value)
            self.field.setPlaceholderText(placeholder)
            self.field.textChanged.connect(self._changed)
            self.field.returnPressed.connect(self._ok)
        self.why = label(muted=False, wrap=True)
        self.why.setObjectName("why")
        self.why.hide()
        self.note = label(note, muted=True, wrap=True, size=-1)
        self.note.setVisible(bool(note))
        self.ok = primary(ok)
        self.ok.clicked.connect(self._ok)
        cancel = button("Cancel")
        cancel.clicked.connect(self.reject)
        self.setLayout(vbox(label(heading, size=2.5, bold=True, wrap=True), label(text, muted=True, wrap=True),
                            self.field, self.why, self.note, hbox(None, cancel, self.ok),
                            spacing=10, margins=(20, 18, 20, 16)))
        self._changed()

    def text(self) -> str:
        return self.field.toPlainText() if isinstance(self.field, QPlainTextEdit) else self.field.text()

    def _changed(self, *_):
        self.why.hide()
        self.ok.setEnabled(bool(self.text().strip()) or getattr(self, "_empty_ok", False))

    def allow_empty(self):
        self._empty_ok = True
        self._changed()
        return self

    def _ok(self):
        try:
            self.value = self.check(self.text())
        except ValueError as e:
            msg = str(e)
            self.why.setText(msg[:1].upper() + msg[1:] + ("" if msg.endswith(".") else "."))
            self.why.setStyleSheet(f"color: {text_color('bad', self)};")
            self.why.show()
            return
        self.accept()


def rename_dialog(parent, current: str, linked_to_hub: bool) -> AskDialog:
    from ..mesh.trust import check_name
    return AskDialog(
        parent, "Rename this computer", "Rename this computer",
        "Your devices show this name. Those connected now see it at once; the rest when they next connect.",
        value=current, placeholder=current, ok="Rename", check=lambda v: check_name(v, "the name"),
        note="Your hub names its devices, so it's renamed there too, and its rules apply: each name once."
        if linked_to_hub else "Up to 40 characters.")


def nickname_dialog(parent, peer: dict) -> AskDialog:
    from ..mesh.trust import check_name
    name = str(peer.get("name") or "the device")
    d = AskDialog(
        parent, "Nickname", f"What do you call {name}?",
        f"Shown on this computer instead of “{name}”. Only here: it's never sent, and {name} keeps its own name.",
        value=str(peer.get("nickname") or ""), placeholder=name, ok="Save",
        check=lambda v: check_name(v, "a nickname", empty_ok=True),
        note="Leave it empty to go back to its own name.")
    return d.allow_empty()


def link_dialog(parent, names: list[str], value: str = "") -> AskDialog:
    from ..mesh.links import check_url
    who = names[0] if len(names) == 1 else f"{len(names)} devices"
    return AskDialog(
        parent, "Send a link", f"Send a link to {who}",
        "It opens in the browser on your own devices. On someone else's, it waits in Messages with an "
        "Open button.", value=value, placeholder="https://", ok="Send", check=check_url)


def message_dialog(parent, names: list[str]) -> AskDialog:
    def check(v):
        if not v.strip():
            raise ValueError("write something first")
        if len(v.encode()) > 64 * 1024:
            raise ValueError("that's too long for one message (64 KB at most)")
        return v.strip()
    return AskDialog(parent, "Send a message", f"Send a message to {len(names)} devices" if len(names) > 1
                     else f"Send a message to {names[0]}", ", ".join(names), placeholder="Write a message",
                     ok="Send", check=check, multiline=True)
