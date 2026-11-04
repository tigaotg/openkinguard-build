"""Router owner undo/revoke metadata regressions. No real UCI changes."""
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] /
                       "plugin-files/usr/lib/openkinguard-relay"))
from device_registry import DeviceRegistry
from enrollment import EnrollmentStore
from manage_join import change_device


class RegistryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = pathlib.Path(self.tmp.name) / "private" / "devices.json"
        self.reg = DeviceRegistry(self.path)
        self.fp = "a" * 64

    def test_revoke_and_restore_keep_identity_and_first_approval_time(self):
        self.reg.approve(self.fp, "奶奶手机", "Honor 7X")
        first = self.reg.list_devices({self.fp})["approved"][0]
        self.reg.set_note(self.fp, "家里的备用机")
        self.reg.revoke(self.fp)
        self.assertTrue(self.reg.is_revoked(self.fp))
        revoked = DeviceRegistry(self.path).list_devices(set())
        self.assertEqual(revoked["approved"], [])
        self.assertEqual(revoked["revoked"][0]["note"], "家里的备用机")
        self.reg.approve(self.fp)
        restored = self.reg.list_devices({self.fp})["approved"][0]
        self.assertEqual(restored["boundAt"], first["boundAt"])
        self.assertEqual(restored["model"], "Honor 7X")
        self.assertEqual(restored["note"], "家里的备用机")
        self.assertIsNotNone(restored["restoredAt"])

    def test_existing_manual_allowlist_has_unknown_original_date(self):
        legacy = self.reg.list_devices({self.fp})["approved"][0]
        self.assertIsNone(legacy["boundAt"])
        self.reg.ensure_legacy(self.fp)
        self.reg.set_note(self.fp, "前厅手机")
        self.assertEqual(self.reg.list_devices({self.fp})["approved"][0]["note"], "前厅手机")

    def test_display_metadata_does_not_grant_access(self):
        self.reg.approve(self.fp, "自报设备", "示例型号")
        self.assertEqual(self.reg.list_devices(set())["approved"], [])
        self.assertEqual(len(self.reg.list_devices(set())["revoked"]), 1)


class OwnerRevokeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        private = pathlib.Path(self.tmp.name) / "join"
        private.mkdir(mode=0o700)
        self.store = EnrollmentStore(private)
        self.reg = DeviceRegistry(pathlib.Path(self.tmp.name) / "config" / "devices.json")
        self.fp = "b" * 64
        self.reg.approve(self.fp, "家人", "型号")
        request = self.store.submit(self.store.new_invite(), self.fp)
        self.store.decide(request, True)
        self.allowed = {self.fp}
        self.calls = []

    def uci(self, *args):
        self.calls.append(args)
        if args == ("uci", "-q", "get", "openkinguard-relay.main.allowed_device"):
            return " ".join(self.allowed)
        if args[:3] == ("uci", "-q", "del_list"):
            self.allowed.remove(args[3].split("=")[-1])
        elif args[:3] == ("uci", "-q", "add_list"):
            self.allowed.add(args[3].split("=")[-1])
        return ""

    def test_revoke_disconnects_and_restore_only_previously_approved(self):
        with patch("manage_join.checked_call", side_effect=self.uci):
            removed = change_device("revoke", self.fp, self.store, self.reg)
            self.assertEqual(removed["status"], "revoked")
            self.assertNotIn(self.fp, self.allowed)
            self.assertFalse(self.store.is_approved(self.fp))
            self.assertIn(("/etc/init.d/openkinguard-relay", "restart"), self.calls)
            restored = change_device("restore", self.fp, self.store, self.reg)
            self.assertEqual(restored["status"], "approved")
            self.assertIn(self.fp, self.allowed)

    def test_cannot_authorize_an_arbitrary_fingerprint_by_restore(self):
        with patch("manage_join.checked_call", side_effect=self.uci):
            with self.assertRaises(ValueError):
                change_device("restore", "c" * 64, self.store, self.reg)
