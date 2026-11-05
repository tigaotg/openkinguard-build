"""Guard the LuCI deletion UX: one confirmation, no manual phrase entry."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
VIEW = ROOT / "plugin-files/www/luci-static/resources/view/services/openkinguard-relay.js"

class DeletedDeviceUiTests(unittest.TestCase):
    def test_revoked_device_has_single_yes_no_confirmation(self):
        js = VIEW.read_text(encoding="utf-8")
        start = js.index("if (revoked) {", js.index("function showSavedDevice("))
        end = js.index("return panel;", start)
        block = js[start:end]
        self.assertIn("window.confirm(notice)", block)
        self.assertNotIn("window.prompt(notice", block)
        self.assertIn("joinAction('delete-device', fp)", block)
        self.assertIn(".catch(showJoinError)", block)

if __name__ == "__main__":
    unittest.main()
