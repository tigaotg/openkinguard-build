"""Socket-free approval, replay and proof-of-possession regression checks."""
import asyncio
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] /
                       "plugin-files/usr/lib/openkinguard-relay"))
from enrollment import EnrollmentStore, MAX_APPROVED
from relay import Relay, JOIN_CONTEXT, enc, dec
import manage_join
from unittest.mock import Mock, patch


class EnrollmentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = EnrollmentStore(self.tmp.name)
        self.fp = "a" * 64

    def test_qr_one_use_and_revoke_previous_invite(self):
        old = self.store.new_invite()
        current = self.store.new_invite()
        with self.assertRaises(ValueError):
            self.store.submit(old, self.fp)
        request_id = self.store.submit(current, self.fp)
        self.assertEqual(self.store.status(request_id, self.fp), "pending")
        with self.assertRaises(ValueError):
            self.store.submit(current, "b" * 64)

    def test_owner_approval_is_required_and_permanent_in_runtime(self):
        request_id = self.store.submit(self.store.new_invite(), self.fp)
        self.assertFalse(self.store.is_approved(self.fp))
        persisted = []
        self.store.decide(request_id, True, persist_approval=persisted.append)
        self.assertEqual(persisted, [self.fp])
        self.assertTrue(self.store.is_approved(self.fp))
        self.assertEqual(self.store.status(request_id, self.fp), "approved")
        with self.assertRaises(ValueError):
            self.store.decide(request_id, True)

    def test_failed_uci_commit_cannot_approve(self):
        request_id = self.store.submit(self.store.new_invite(), self.fp)
        with self.assertRaises(OSError):
            self.store.decide(request_id, True, persist_approval=lambda _: (
                (_ for _ in ()).throw(OSError("storage unavailable"))))
        self.assertEqual(self.store.status(request_id, self.fp), "pending")

    def test_rejection_is_final_and_fingerprint_scoped(self):
        request_id = self.store.submit(self.store.new_invite(), self.fp)
        self.store.decide(request_id, False)
        self.assertEqual(self.store.status(request_id, self.fp), "rejected")
        self.assertEqual(self.store.status(request_id, "b" * 64), "expired")
        self.assertFalse(self.store.is_approved(self.fp))

    def test_approved_runtime_cache_is_bounded(self):
        persisted = []
        first_request = None
        first_fingerprint = None
        last_request = None
        last_fingerprint = None
        for index in range(MAX_APPROVED + 2):
            fingerprint = f"{index:064x}"
            request_id = self.store.submit(self.store.new_invite(), fingerprint)
            self.store.decide(request_id, True, persist_approval=persisted.append)
            if index == 0:
                first_request, first_fingerprint = request_id, fingerprint
            last_request, last_fingerprint = request_id, fingerprint
        # The hard cap must hold immediately after the last mutation, not
        # only after a later read happens to prune the cache.
        state = json.loads((pathlib.Path(self.tmp.name) / "state.json").read_text())
        approved = [item for item in state["pending"].values()
                    if item["status"] == "approved"]
        self.assertLessEqual(len(approved), MAX_APPROVED)
        self.assertFalse(self.store.is_approved(first_fingerprint))
        self.assertTrue(self.store.is_approved(last_fingerprint))
        self.assertEqual(self.store.status(first_request, first_fingerprint), "expired")
        self.assertEqual(len(persisted), MAX_APPROVED + 2)

    def test_owner_revocation_removes_ephemeral_approval(self):
        request_id = self.store.submit(self.store.new_invite(), self.fp,
                                       "家人的荣耀手机", "Honor 7X")
        item = self.store.list_pending()[0]
        self.assertEqual(item["nickname"], "家人的荣耀手机")
        self.assertEqual(item["model"], "Honor 7X")
        self.store.decide(request_id, True)
        self.assertTrue(self.store.is_approved(self.fp))
        self.store.revoke_approval(self.fp)
        self.assertFalse(self.store.is_approved(self.fp))
        self.assertEqual(self.store.status(request_id, self.fp), "expired")


class RevocationSafetyTests(unittest.TestCase):
    def test_revoke_stops_before_allowlist_mutation_and_restarts_afterward(self):
        fingerprint = "c" * 64
        store = Mock()
        registry = Mock()
        calls = []

        def checked(*args):
            calls.append(args)
            return ""

        with patch.object(manage_join, "allowed_fingerprints", return_value={fingerprint}), \
             patch.object(manage_join, "checked_call", side_effect=checked):
            result = manage_join.change_device("revoke", fingerprint, store, registry)

        self.assertEqual(result["status"], "revoked")
        self.assertEqual(calls[0], ("/etc/init.d/openkinguard-relay", "stop"))
        self.assertLess(calls.index(("uci", "-q", "del_list",
            "openkinguard-relay.main.allowed_device=" + fingerprint)),
            calls.index(("uci", "commit", "openkinguard-relay")))
        self.assertEqual(calls[-1], ("/etc/init.d/openkinguard-relay", "restart"))
        store.revoke_approval.assert_called_once_with(fingerprint)
        registry.revoke.assert_called_once_with(fingerprint)

    def test_transient_approval_failure_leaves_relay_stopped(self):
        fingerprint = "d" * 64
        store = Mock()
        store.revoke_approval.side_effect = OSError("state file locked")
        registry = Mock()
        calls = []

        def checked(*args):
            calls.append(args)
            return ""

        with patch.object(manage_join, "allowed_fingerprints", return_value={fingerprint}), \
             patch.object(manage_join, "checked_call", side_effect=checked):
            with self.assertRaises(OSError):
                manage_join.change_device("revoke", fingerprint, store, registry)

        self.assertEqual(calls[0], ("/etc/init.d/openkinguard-relay", "stop"))
        self.assertNotIn(("/etc/init.d/openkinguard-relay", "restart"), calls)
        registry.revoke.assert_not_called()

    def test_registry_failure_does_not_skip_relay_reload(self):
        fingerprint = "e" * 64
        store = Mock()
        registry = Mock()
        registry.revoke.side_effect = OSError("registry storage full")
        calls = []

        def checked(*args):
            calls.append(args)
            return ""

        with patch.object(manage_join, "allowed_fingerprints", return_value={fingerprint}), \
             patch.object(manage_join, "checked_call", side_effect=checked):
            with self.assertRaises(OSError):
                manage_join.change_device("revoke", fingerprint, store, registry)

        self.assertEqual(calls[0], ("/etc/init.d/openkinguard-relay", "stop"))
        self.assertEqual(calls[-1], ("/etc/init.d/openkinguard-relay", "restart"))

    def test_installed_version_is_read_from_runtime_marker(self):
        self.assertEqual(manage_join.installed_version(), "0.3.7")


class ScriptedJoinSocket:
    path = "/join"

    def __init__(self, store, token, key, valid_signature=True):
        self.store, self.token, self.key = store, token, key
        self.valid_signature = valid_signature
        self.messages = []
        self.closed = None

    async def send(self, message):
        self.messages.append(json.loads(message))

    async def recv(self):
        if len(self.messages) == 1:
            nonce = dec(self.messages[0]["n"], 32)
            pub = self.key.public_key().public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
            signature = self.key.sign(JOIN_CONTEXT + nonce + hashlib.sha256(
                self.token.encode()).digest(), ec.ECDSA(hashes.SHA256()))
            if not self.valid_signature:
                signature = b"bad"
            return json.dumps({"t": "join", "token": self.token, "key": enc(pub),
                               "sig": enc(signature)})
        if len(self.messages) == 2:
            return json.dumps({"t": "status", "id": self.messages[1]["id"]})
        raise RuntimeError("unexpected extra receive")

    async def close(self, code, reason):
        self.closed = code


class JoinProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = EnrollmentStore(self.tmp.name)
        self.relay = Relay(set(), enrollment=self.store)
        self.key = ec.generate_private_key(ec.SECP256R1())

    async def test_signed_join_stays_pending_without_owner_action(self):
        sock = ScriptedJoinSocket(self.store, self.store.new_invite(), self.key)
        # Exercise handshake until the first status response without a real port.
        with self.assertRaises(RuntimeError):
            await self.relay.handle_join(sock)
        self.assertEqual([item["t"] for item in sock.messages],
                         ["join_challenge", "pending", "pending"])
        self.assertFalse(self.relay.allowed)
        self.assertEqual(len(self.store.list_pending()), 1)

    async def test_bad_signature_cannot_spend_invite(self):
        token = self.store.new_invite()
        sock = ScriptedJoinSocket(self.store, token, self.key, valid_signature=False)
        with self.assertRaises(Exception):
            await self.relay.handle_join(sock)
        self.assertEqual(self.store.list_pending(), [])
        fp = hashlib.sha256(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
        self.assertEqual(len(self.store.submit(token, fp)), 32)


if __name__ == "__main__":
    unittest.main()
