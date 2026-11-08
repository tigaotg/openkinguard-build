"""Bounded ciphertext-only queue storage for OpenWrt family relays.

S4 frames are opaque to the relay. The phone checks all device signatures,
relationships and AEAD tags. The default tmpfs spool survives a service
restart, while never writing normal queue traffic to the router flash.
"""
import base64
import json
import os
import re
import stat
import struct
import tempfile
import time
from pathlib import Path
from uuid import UUID

FP = re.compile(r"[0-9a-f]{64}\Z")
ID = re.compile(r"queued-[0-9a-f]{32}\Z")
KINDS = {"OKG_S1_TEST_SOS_V1", "OKG_S1_CHECKIN_V1",
         "OKG_S1_CHECKIN_CONFIRM_V1"}
MAX_FILES = 256
TTL = 300
MAX_SIZE = 13_500


def encrypted_case_id(data, sender, recipient):
    """Inspect untrusted LAN carrier for S4 ciphertext and a stable message ID."""
    try:
        if not isinstance(data, str) or len(data) > 11_000:
            return None
        frame = base64.b64decode(data + "=" * (-len(data) % 4),
                                 altchars=b"-_", validate=True)
        if not 100 <= len(frame) <= 8192 or struct.unpack_from(">II", frame) != (
                0x4f4b4c31, 1):
            return None
        offset = 8

        def word():
            nonlocal offset
            count = struct.unpack_from(">H", frame, offset)[0]
            offset += 2
            if not 1 <= count <= 512 or offset + count > len(frame):
                raise ValueError("bad UTF field")
            result = frame[offset:offset + count].decode("ascii")
            offset += count
            return result

        message_id, _event, source, _subject, dest = [word() for _ in range(5)]
        if source != "okg-" + sender or dest != "okg-" + recipient:
            return None
        offset += 24
        if word() not in KINDS:
            return None
        size = struct.unpack_from(">I", frame, offset)[0]
        offset += 4
        if not 200 <= size <= 512 or offset + size + 4 > len(frame):
            return None
        payload = frame[offset:offset + size]
        offset += size
        signature_size = struct.unpack_from(">I", frame, offset)[0]
        if not 64 <= signature_size <= 80 or offset + 4 + signature_size != len(frame):
            return None
        if not payload.startswith(b"OKG4|1|"):
            return None
        return message_id if str(UUID(message_id)) == message_id else None
    except (ValueError, UnicodeError, TypeError, OverflowError, struct.error):
        return None


class OfflineQueueStore:
    def __init__(self, directory):
        self.directory = Path(directory)
        if self.directory.is_symlink() or not self.directory.is_dir() or (
                self.directory.stat().st_mode & 0o077):
            raise ValueError("queue directory must be a real private 0700 directory")

    def path(self, identifier):
        if not ID.fullmatch(identifier):
            raise ValueError("invalid queue identifier")
        return self.directory / (identifier + ".json")

    def load(self):
        paths = sorted(self.directory.glob("queued-*.json"))
        if len(paths) > MAX_FILES:
            raise ValueError("offline queue exceeded file limit")
        entries = []
        for path in paths:
            identifier = path.name.removesuffix(".json")
            if not ID.fullmatch(identifier):
                continue
            fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            try:
                st = os.fstat(fd)
                if not stat.S_ISREG(st.st_mode) or (st.st_mode & 0o077) or st.st_size > MAX_SIZE:
                    raise ValueError("unsafe queue file")
                with os.fdopen(fd, "rb", closefd=False) as stream:
                    entry = json.loads(stream.read(MAX_SIZE + 1))
            finally:
                os.close(fd)
            if not isinstance(entry, dict) or entry.get("request_id") != identifier:
                raise ValueError("invalid queue entry")
            sender, recipient = entry.get("sender"), entry.get("recipient")
            if not all(isinstance(fp, str) and FP.fullmatch(fp)
                       for fp in (sender, recipient)):
                raise ValueError("invalid queue identity")
            logical_id = encrypted_case_id(entry.get("data"), sender, recipient)
            if logical_id is None or logical_id != entry.get("logical_id"):
                raise ValueError("only S4 ciphertext may be restored")
            orig_id, expires = entry.get("sender_request_id"), entry.get("expires_at")
            if not isinstance(orig_id, str) or not 1 <= len(orig_id) <= 70 or (
                    isinstance(expires, bool) or not isinstance(expires, (float, int))):
                raise ValueError("invalid queue correlation or expiry")
            if expires <= time.time() or time.time() < 1_577_836_800:
                # OpenWrt may boot before NTP has established wall-clock time.
                # Drop uncertain queued alerts rather than replay ancient SOS.
                self.delete(identifier)
                continue
            if expires > time.time() + TTL + 5:
                raise ValueError("unbounded queue expiry")
            entries.append(entry)
        return entries

    def save(self, entry):
        path = self.path(entry["request_id"])
        sender, recipient = entry["sender"], entry["recipient"]
        logical_id = encrypted_case_id(entry["data"], sender, recipient)
        if logical_id is None or logical_id != entry["logical_id"]:
            raise ValueError("refusing to persist plaintext")
        if not time.time() < entry["expires_at"] <= time.time() + TTL + 5:
            raise ValueError("invalid storage expiry")
        raw = json.dumps(entry, separators=(",", ":"), ensure_ascii=True).encode("ascii")
        if len(raw) > MAX_SIZE:
            raise ValueError("queue item too large")
        fd, temp = tempfile.mkstemp(prefix=".queued-", dir=self.directory)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp, path)
            directory_fd = os.open(self.directory, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)

    def delete(self, identifier):
        try:
            self.path(identifier).unlink()
        except FileNotFoundError:
            pass
