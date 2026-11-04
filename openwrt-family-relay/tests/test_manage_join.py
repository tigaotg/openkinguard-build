"""Ensure the firewall helper never changes general WAN/SSH/LuCI rules."""
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] /
                       "plugin-files/usr/lib/openkinguard-relay"))
from manage_join import ipv6_firewall


class FirewallPolicyTests(unittest.TestCase):
    def test_open_creates_only_named_ipv6_tcp_rule(self):
        calls = []
        def fake(*args):
            calls.append(args)
            if args[1:3] == ("-q", "get"):
                if args[3] == "openkinguard-relay.main.port":
                    return "9443"
                raise __import__("subprocess").CalledProcessError(1, args)
            return ""
        with patch("manage_join.checked_call", side_effect=fake):
            result = ipv6_firewall("open-ipv6")
        self.assertTrue(result["ipv6Only"])
        self.assertIn(("uci", "set", "firewall.okg_family_relay_ipv6.family=ipv6"), calls)
        self.assertIn(("uci", "set", "firewall.okg_family_relay_ipv6.proto=tcp"), calls)
        self.assertIn(("uci", "set", "firewall.okg_family_relay_ipv6.dest_port=9443"), calls)
        self.assertFalse(any("ssh" in " ".join(c).lower() or
                             "luci" in " ".join(c).lower() for c in calls))

    def test_conflicting_rule_is_preserved(self):
        def fake(*args):
            if args[-1] == "openkinguard-relay.main.port":
                return "9443"
            if args[-1] == "firewall.okg_family_relay_ipv6.name":
                return "User's different rule"
            if args[-1] == "firewall.okg_family_relay_ipv6":
                return "rule"
            raise AssertionError("unexpected mutation")
        with patch("manage_join.checked_call", side_effect=fake):
            with self.assertRaises(ValueError):
                ipv6_firewall("close-ipv6")


if __name__ == "__main__":
    unittest.main()
