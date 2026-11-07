"""Ensure the router UI reports the actual installed family-relay version."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class LuCIVersionTests(unittest.TestCase):
    def test_package_and_runtime_marker_match(self):
        marker = (ROOT / "plugin-files/usr/lib/openkinguard-relay/VERSION").read_text().strip()
        makefile = (ROOT / "Makefile").read_text()
        version = re.search(r"^PKG_VERSION:=(.+)$", makefile, re.M).group(1)
        self.assertEqual(version, marker)

    def test_installer_copies_runtime_marker(self):
        script = (ROOT / "install-on-openwrt.sh").read_text()
        self.assertIn('VERSION" \\', script)
        self.assertIn('/usr/lib/openkinguard-relay/VERSION 0644', script)

    def test_fixed_command_entrypoint_allows_version_read(self):
        wrapper = (ROOT / "plugin-files/usr/lib/openkinguard-relay/manage-join").read_text()
        self.assertIn("invite|list|version|firewall-status", wrapper)

    def test_luci_page_displays_version_separately_from_service_state(self):
        page = (ROOT / "plugin-files/www/luci-static/resources/view/services/openkinguard-relay.js").read_text()
        self.assertIn("joinAction('version')", page)
        self.assertIn("已安装插件版本：", page)
        self.assertIn("不代表中继服务当前已启用或公网可达", page)


if __name__ == "__main__":
    unittest.main()
