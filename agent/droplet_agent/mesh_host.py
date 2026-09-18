"""Connects the mesh (mesh/) to the rest of the agent: the Host the mesh node calls.

The mesh reaches the agent's handlers and the hub only through this, so the
mesh package stays separable.
"""

from __future__ import annotations

import logging
import threading

from pathlib import Path

from . import config, hub
from .mesh.node import Host

log = logging.getLogger("droplet_agent.mesh")

ROSTER_EVERY = 600   # fetch the roster this often while connected, besides on every change


class AgentHost(Host):
    def __init__(self, agent, cfg: dict):
        self.agent = agent
        self.cfg = cfg
        self.connection = None     # set when there's a hub
        self.node = None           # set once the node exists
        self._roster_lock = threading.Lock()
        self._roster_again = threading.Event()

    # --- who we are ---------------------------------------------------------------

    def linked(self) -> bool:
        return config.is_set_up(self.cfg)

    def mesh_caps(self) -> list[str]:
        return sorted(self.agent.advertised)

    def device_name(self) -> str:
        return (self.cfg.get("device") or {}).get("name") or self.agent.host

    def hub_device_id(self) -> str | None:
        return (self.cfg.get("device") or {}).get("id") or None if self.linked() else None

    def hub_id(self) -> str | None:
        return (self.cfg.get("hub_identity") or {}).get("id") or None if self.linked() else None

    # --- the agent's handlers --------------------------------------------------------

    def dispatch_remote(self, msg: dict, source) -> None:
        self.agent.dispatch(msg, source)

    def last_states(self) -> dict:
        return dict(self.agent.last_state)

    # --- the hub -------------------------------------------------------------------

    def hub_connected(self) -> bool:
        c = self.connection
        return bool(c is not None and c.ws is not None)

    def hub_online(self, device_id: str) -> bool:
        return device_id in self.agent.hub_devices

    def hub_send(self, msg: dict) -> bool:
        return self.agent.send(msg)

    def _route(self):
        return self.agent.route or self.cfg["hub"]

    def hub_text(self, device_id: str, body: str) -> None:
        hub.send_text(self._route(), self.cfg["token"], device_id, body)

    def hub_upload(self, device_id: str, path: Path, name: str, mime: str) -> None:
        hub.upload_file(self._route(), self.cfg["token"], device_id, path, name, mime)

    def hub_ring(self, device_id: str, stop: bool) -> None:
        hub.ring(self._route(), self.cfg["token"], device_id, stop)

    # --- the roster -------------------------------------------------------------------

    def hub_up(self):
        """Connected to the hub: announce this device, fetch the roster, and deliver what waits."""
        if self.node is not None:
            self.node.refresh_announcement()
        self.roster_changed()

    def roster_changed(self):
        threading.Thread(target=self._sync_roster, name="mesh-roster", daemon=True).start()

    def _sync_roster(self):
        if self.node is None or not self.linked():
            return
        if not self._roster_lock.acquire(blocking=False):
            self._roster_again.set()   # one is running: it runs once more after
            return
        try:
            while True:
                self._roster_again.clear()
                try:
                    hub.mesh_announce(self._route(), self.cfg["token"], self.node.announce_body())
                    data = hub.mesh_roster(self._route(), self.cfg["token"])
                    self.node.apply_roster(data, self.hub_id() or "")
                except hub.HubError as e:
                    # the cached roster keeps working
                    log.warning("mesh: couldn't update the roster from the hub: %s", e)
                except ValueError as e:
                    log.warning("mesh: the hub's roster: %s", e)
                if not self._roster_again.is_set():
                    break
        finally:
            self._roster_lock.release()

    def roster_loop(self, stop: threading.Event):
        while not stop.wait(ROSTER_EVERY):
            if self.hub_connected():
                self._sync_roster()


def start_mesh(agent, cfg: dict, *, dry_run: bool):
    """Build and start the mesh node for this agent. Returns (node, host)."""
    from . import env
    from .mesh.discovery import lan_addresses
    from .mesh.node import MeshNode

    m = cfg.get("mesh") or {}
    host = AgentHost(agent, cfg)
    node = MeshNode(
        host,
        config_dir=config.mesh_config_dir(),
        data_dir=config.mesh_data_dir(),
        downloads=config.downloads_dir(cfg),
        port=m.get("port") if isinstance(m.get("port"), int) else None,
        max_rate=m.get("max_rate") if isinstance(m.get("max_rate"), int) and m.get("max_rate") > 0 else 0,
        dry_run=dry_run,
        announce=m.get("announce", True) is not False,
        local_addresses=lan_addresses,
        session_env=env.session_env(),
    )
    host.node = node
    agent.peers_broadcast = node.broadcast
    agent.on_roster = host.roster_changed
    agent.on_hub_up = host.hub_up
    node.start()
    threading.Thread(target=host.roster_loop, args=(agent.stop,), name="mesh-roster-loop", daemon=True).start()
    return node, host


