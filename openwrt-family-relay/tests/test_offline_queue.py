"""Focused OpenWrt offline spool persistence and fail-closed tests."""
import base64
import os
import pathlib
import struct
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] /
                       "plugin-files/usr/lib/openkinguard-relay"))
from offline_queue import OfflineQueueStore, encrypted_case_id
from relay import Relay


def frame(sender, recipient, message_id=None, encrypted=True):
    msg_id = message_id or str(uuid.uuid4())
    def utf(value):
        binary = value.encode("ascii")
        return struct.pack(">H", len(binary)) + binary
    body = b"OKG4|1|" + b"a" * 210 if encrypted else b"R|plaintext" * 19
    header = [msg_id, str(uuid.uuid4()), "okg-" + sender,
              "okg-user-" + sender, "okg-" + recipient]
    data = struct.pack(">II", 0x4f4b4c31, 1)
    data += b"".join(utf(x) for x in header)
    data += struct.pack(">qqq", 1000, 2000, 0) + utf("OKG_S1_CHECKIN_V1")
    data += struct.pack(">I", len(body)) + body
    data += struct.pack(">I", 72) + b"x" * 72
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


class SpoolTests(unittest.IsolatedAsyncioTestCase):
    async def test_process_restart_recovers_ciphertext_only(self):
        a, b = "a" * 64, "b" * 64
        with tempfile.TemporaryDirectory() as directory:
            first = Relay({a, b}, queue_dir=directory)
            data = frame(a, b)
            self.assertTrue(encrypted_case_id(data, a, b))
            self.assertTrue(await first.enqueue(a, b, data, "origin-1"))
            recovered = Relay({a, b}, queue_dir=directory)
            self.assertEqual(len(recovered.offline), 1)
            self.assertEqual(recovered.offline[0].sender_request_id, "origin-1")
            recovered.offline[0].expires_at = 0
            recovered.prune_offline()
            self.assertEqual(OfflineQueueStore(directory).load(), [])

    async def test_resealed_same_message_id_deduplicates_without_extending_ttl(self):
        a, b = "a" * 64, "b" * 64
        msg_id = str(uuid.uuid4())
        with tempfile.TemporaryDirectory() as directory:
            relay = Relay({a, b}, queue_dir=directory)
            one = frame(a, b, msg_id)
            two = frame(a, b, msg_id)
            self.assertTrue(await relay.enqueue(a, b, one, "req-1"))
            expiry = relay.offline[0].expires_at
            self.assertTrue(await relay.enqueue(a, b, two, "req-2"))
            self.assertEqual(len(relay.offline), 1)
            self.assertEqual(relay.offline[0].expires_at, expiry)
            self.assertEqual(relay.offline[0].sender_request_id, "req-2")
            self.assertEqual(Relay({a, b}, queue_dir=directory).offline[0].data, two)

    async def test_plaintext_never_written_to_spool(self):
        a, b = "a" * 64, "b" * 64
        with tempfile.TemporaryDirectory() as directory:
            relay = Relay({a, b}, queue_dir=directory)
            data = frame(a, b, encrypted=False)
            self.assertIsNone(encrypted_case_id(data, a, b))
            self.assertTrue(await relay.enqueue(a, b, data, "legacy"))
            self.assertEqual(OfflineQueueStore(directory).load(), [])

    async def test_revocation_prunes_saved_notifications(self):
        a, b = "a" * 64, "b" * 64
        with tempfile.TemporaryDirectory() as directory:
            relay = Relay({a, b}, queue_dir=directory)
            self.assertTrue(await relay.enqueue(a, b, frame(a, b), "request"))
            self.assertEqual(Relay({b}, queue_dir=directory).offline, [])
            self.assertEqual(OfflineQueueStore(directory).load(), [])

    def test_world_readable_queue_directory_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            os.chmod(directory, 0o755)
            with self.assertRaises(ValueError):
                OfflineQueueStore(directory)


if __name__ == "__main__":
    unittest.main()
