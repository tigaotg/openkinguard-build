"""Router-only display metadata for explicitly approved family devices.

The UCI allowed_device list is the sole durable connection authorization.
This private JSON index only remembers labels, timestamps and revoked devices,
so an owner can restore a previously authorized identity without a new QR.
"""
from __future__ import annotations

import fcntl
import json
import os
import re
import tempfile
import time
from pathlib import Path

from enrollment import clean_device_label

FP = re.compile(r"[0-9a-f]{64}\Z")
MAX_DEVICES = 128


class DeviceRegistry:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    def _change(self, update):
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.path.parent, 0o700)
        with self.path.with_suffix(".lock").open("a+b") as lock:
            os.fchmod(lock.fileno(), 0o600)
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            devices = json.loads(self.path.read_text()).get("devices", {}) \
                if self.path.exists() else {}
            if not isinstance(devices, dict):
                raise ValueError("device registry is invalid")
            result, changed = update(devices, int(time.time()))
            if changed:
                if len(devices) > MAX_DEVICES:
                    raise ValueError("device registry is full")
                fd, tmp = tempfile.mkstemp(prefix="devices-", dir=self.path.parent)
                try:
                    os.fchmod(fd, 0o600)
                    with os.fdopen(fd, "w") as stream:
                        json.dump({"version": 1, "devices": devices}, stream,
                                  ensure_ascii=False, separators=(",", ":"))
                        stream.flush()
                        os.fsync(stream.fileno())
                    os.replace(tmp, self.path)
                finally:
                    if os.path.exists(tmp):
                        os.unlink(tmp)
            return result

    def approve(self, fingerprint: str, nickname: str = "", model: str = "") -> None:
        if not FP.fullmatch(fingerprint):
            raise ValueError("invalid fingerprint")
        def update(devices, now):
            old = devices.get(fingerprint, {})
            devices[fingerprint] = {
                **old,
                "nickname": clean_device_label(nickname) or old.get("nickname", ""),
                "model": clean_device_label(model) or old.get("model", ""),
                "boundAt": old.get("boundAt") or now,
                "restoredAt": now if old.get("status") == "revoked" else old.get("restoredAt"),
                "status": "approved",
                "revokedAt": None,
            }
            return None, True
        self._change(update)

    def revoke(self, fingerprint: str) -> None:
        if not FP.fullmatch(fingerprint):
            raise ValueError("invalid fingerprint")
        def update(devices, now):
            old = devices.get(fingerprint, {})
            devices[fingerprint] = {**old, "status": "revoked", "revokedAt": now,
                                    "boundAt": old.get("boundAt")}
            return None, True
        self._change(update)

    def is_revoked(self, fingerprint: str) -> bool:
        return self._change(lambda devices, now: (
            devices.get(fingerprint, {}).get("status") == "revoked", False))

    def was_approved(self, fingerprint: str) -> bool:
        """Supports undo even if an owner removed a formerly approved UCI row by hand."""
        return self._change(lambda devices, now: (
            fingerprint in devices and devices[fingerprint].get("status") in
            ("approved", "revoked"), False))

    def ensure_legacy(self, fingerprint: str) -> None:
        """Manually allowed older devices have no recorded binding timestamp."""
        if not FP.fullmatch(fingerprint):
            raise ValueError("invalid fingerprint")
        def update(devices, now):
            if fingerprint in devices:
                return None, False
            devices[fingerprint] = {"nickname": "", "model": "", "note": "",
                                    "boundAt": None, "status": "approved"}
            return None, True
        self._change(update)

    def set_note(self, fingerprint: str, note: str) -> None:
        if not FP.fullmatch(fingerprint) or not isinstance(note, str) or len(note) > 60:
            raise ValueError("invalid device note")
        def update(devices, now):
            if fingerprint not in devices:
                raise ValueError("device not recorded")
            devices[fingerprint]["note"] = clean_device_label(note, 32)
            return None, True
        self._change(update)

    def list_devices(self, allowed: set[str]) -> dict:
        def read(devices, now):
            visible = []
            for fingerprint in sorted(allowed | set(devices)):
                if not FP.fullmatch(fingerprint):
                    continue
                old = devices.get(fingerprint, {})
                visible.append({
                    "fingerprint": fingerprint,
                    "nickname": old.get("nickname", ""),
                    "model": old.get("model", ""),
                    "note": old.get("note", ""),
                    "boundAt": old.get("boundAt"),
                    "restoredAt": old.get("restoredAt"),
                    "revokedAt": old.get("revokedAt"),
                    # Authorization is always the current UCI allowlist, not JSON.
                    "status": "approved" if fingerprint in allowed else "revoked",
                })
            return {
                "approved": [item for item in visible if item["status"] == "approved"],
                "revoked": [item for item in visible if item["status"] == "revoked"],
            }, False
        return self._change(read)
