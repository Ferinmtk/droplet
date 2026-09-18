"""droplet agent: lets your other devices control this Linux computer through droplet.

It holds a WebSocket to the droplet hub and acts on what arrives: pointer and
keyboard input, media keys and volume, locking, screenshots and clipboard
sync. The protocol is docs/remote.md in the droplet repository.
"""

__version__ = "1.0.0"

# Registered with the desktop portal so KDE and GNOME can remember the
# permission they ask for; matches the .desktop file the installer writes.
APP_ID = "io.github.ferinmtk.DropletAgent"
