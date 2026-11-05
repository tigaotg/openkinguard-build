"""One-use, owner-approved family relay enrollment. No central account.

State is in a private tmpfs directory, accessible to the relay's unprivileged
user and router root only. Only SHA-256 hashes of QR secrets are stored.
Router-owner approvals are separately committed to UCI by the admin command.
"""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import secrets
import tempfile
import time
from pathlib import Path

FP = re.compile(r"[0-9a-f]{64}\Z")
ID = re.compile(r"[0-9a-f]{32}\Z")
INVITE_SECONDS = 300
PENDING_SECONDS = 600
MAX_PENDING = 16


def clean_device_label(raw: str, limit: int = 40) -> str:
    """Device-supplied display text is advisory, not an authenticated identity."""
    if not isinstance(raw, str):
        return ""
    return "".join(char for char in raw if char.isprintable()).strip()[:limit]


class EnrollmentStore:
    def __init__(self, directory: str | Path) -> None:
        self.directory = Path(directory)
        if not self.directory.is_dir():
            raise ValueError("enrollment state directory must already exist")
        if self.directory.stat().st_mode & 0o077:
            raise ValueError("enrollment directory permissions must be 0700")
        self.path = self.directory / "state.json"
        self.lock_path = self.directory / "state.lock"

    def _change(self, callback):
        # Never lock the state file itself: os.replace would replace its inode.
        with self.lock_path.open("a+b") as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            data = json.loads(self.path.read_text()) if self.path.exists() else {}
            now = time.time()
            invites = data.setdefault("invites", {})
            pending = data.setdefault("pending", {})
            for key, value in list(invites.items()):
                if value.get("expires", 0) <= now:
                    del invites[key]
            for key, value in list(pending.items()):
                if value.get("status") != "approved" and value.get("expires", 0) <= now:
                    del pending[key]
            result, modified = callback(data, now)
            if modified:
                fd, tmp = tempfile.mkstemp(prefix="state-", dir=self.directory)
                try:
                    os.fchmod(fd, 0o600)
                    # Preserve the relay UID if root writes a new generation.
                    owner = self.directory.stat()
                    if os.geteuid() == 0:
                        os.fchown(fd, owner.st_uid, owner.st_gid)
                    with os.fdopen(fd, "w") as stream:
                        json.dump(data, stream, separators=(",", ":"))
                        stream.flush()
                        os.fsync(stream.fileno())
                    os.replace(tmp, self.path)
                finally:
                    if os.path.exists(tmp):
                        os.unlink(tmp)
            return result

    def new_invite(self) -> str:
        token = secrets.token_urlsafe(24)
        digest = hashlib.sha256(token.encode("ascii")).hexdigest()
        def update(data, now):
            data["invites"] = {digest: {"expires": now + INVITE_SECONDS}}
            return token, True  # Generating a new QR invalidates all older QRs.
        return self._change(update)

    def submit(self, token: str, fingerprint: str,
               nickname: str = "", model: str = "") -> str:
        if not isinstance(token, str) or len(token) > 100 or not FP.fullmatch(fingerprint):
            raise ValueError("invalid enrollment request")
        digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        def update(data, now):
            if digest not in data["invites"]:
                raise ValueError("expired or already used invitation")
            if sum(item["status"] == "pending" for item in data["pending"].values()) >= MAX_PENDING:
                raise ValueError("enrollment request queue is full")
            del data["invites"][digest]  # One QR grants at most one application.
            request_id = secrets.token_hex(16)
            data["pending"][request_id] = {
                "fingerprint": fingerprint,
                "nickname": clean_device_label(nickname),
                "model": clean_device_label(model),
                "created": now,
                "expires": now + PENDING_SECONDS,
                "status": "pending",
            }
            return request_id, True
        return self._change(update)

    def status(self, request_id: str, fingerprint: str) -> str:
        if not ID.fullmatch(request_id) or not FP.fullmatch(fingerprint):
            return "expired"
        def read(data, now):
            request = data["pending"].get(request_id)
            if not request or request["fingerprint"] != fingerprint:
                return "expired", False
            return request["status"], False
        return self._change(read)

    def list_pending(self) -> list[dict]:
        def read(data, now):
            return [{"id": key, "fingerprint": value["fingerprint"],
                     "created": int(value["created"]),
                     "nickname": value.get("nickname", ""),
                     "model": value.get("model", "")}
                    for key, value in data["pending"].items()
                    if value["status"] == "pending"], False
        return self._change(read)

    def revoke_approval(self, fingerprint: str) -> None:
        """Avoid stale approved tmpfs requests bypassing a removed UCI allowlist."""
        if not FP.fullmatch(fingerprint):
            raise ValueError("invalid fingerprint")
        def update(data, now):
            changed = False
            for key, item in list(data["pending"].items()):
                if item["fingerprint"] == fingerprint and item["status"] == "approved":
                    del data["pending"][key]
                    changed = True
            return None, changed
        self._change(update)

    def purge_device(self, fingerprint: str) -> int:
        """Remove pending, rejected and approved invitation records for this key.

        The durable UCI allowlist must be checked separately before the
        device-registry entry is erased. Fresh enrollment requires a new QR.
        """
        if not FP.fullmatch(fingerprint):
            raise ValueError("invalid fingerprint")
        def update(data, now):
            removed = [
                request_id for request_id, entry in data["pending"].items()
                if entry.get("fingerprint") == fingerprint
            ]
            for request_id in removed:
                del data["pending"][request_id]
            return len(removed), bool(removed)
        return self._change(update)

    def decide(self, request_id: str, approved: bool, persist_approval=None) -> str:
        if not ID.fullmatch(request_id):
            raise ValueError("invalid request id")
        def update(data, now):
            item = data["pending"].get(request_id)
            if not item or item["status"] != "pending":
                raise ValueError("request not found, expired or already decided")
            # Keep the state lock while committing the permanent UCI allowlist.
            # A failed UCI write cannot accidentally produce an approved session.
            if approved and persist_approval is not None:
                persist_approval(item["fingerprint"])
            item["status"] = "approved" if approved else "rejected"
            return item["fingerprint"], True
        return self._change(update)

    def is_approved(self, fingerprint: str) -> bool:
        def read(data, now):
            return any(item["fingerprint"] == fingerprint and
                       item["status"] == "approved"
                       for item in data["pending"].values()), False
        return self._change(read)
