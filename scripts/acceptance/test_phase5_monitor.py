import importlib.util
import json
import pathlib
import tempfile
import unittest

FILE = pathlib.Path(__file__).with_name("phase5_monitor.py")
spec = importlib.util.spec_from_file_location("phase5_monitor", FILE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class ObserverTests(unittest.TestCase):
    def test_battery_ranges_and_charging(self):
        self.assertEqual(mod.parse_battery("  level: 87\n  status: 2\n"), (87, True))
        self.assertEqual(mod.parse_battery("level: 142\nstatus: 3\n"), (None, False))
        self.assertEqual(mod.parse_battery("garbage"), (None, None))

    def test_screen_idle_airplane_categories_only(self):
        self.assertEqual(mod.parse_screen("mWakefulness=Dozing"), "dozing")
        self.assertEqual(mod.parse_idle("mState=IDLE\nIP: 192.0.2.1"), "idle")
        self.assertEqual(mod.parse_airplane("1\n"), "on")
        self.assertEqual(mod.category("adb: device unauthorized 192.168.1.3"), "unauthorized")
        self.assertEqual(mod.safe_manufacturer("Apple: wss://secret"), "applewsssecret")

    def test_sample_reports_only_allowlisted_data(self):
        commands = {
            ("get-state",): (True, "device\n"),
            ("shell", "pidof", "-s", "io.github.tigaotg.openkinguard.debug.pilotv4"): (True, "1234\n"),
            ("shell", "dumpsys", "battery"): (True, "level: 50\nstatus: 3\n192.0.2.1\n"),
            ("shell", "dumpsys", "power"): (True, "mWakefulness=Asleep\nsecret token"),
            ("shell", "dumpsys", "deviceidle"): (True, "mState=IDLE\nhttp://secret"),
            ("shell", "settings", "get", "global", "airplane_mode_on"): (True, "0\n"),
        }
        original = mod.adb
        mod.adb = lambda exe, serial, *args: commands[args]
        try:
            row = mod.sample_device("adb", "SECRET_SERIAL", "device-1",
                                    "io.github.tigaotg.openkinguard.debug.pilotv4", 100.0)
        finally:
            mod.adb = original
        self.assertEqual(set(row), mod.SAMPLE_KEYS)
        self.assertEqual(row["app_process"], "running")
        self.assertEqual(row["screen"], "asleep")
        self.assertEqual(row["battery_pct"], 50)
        encoded = json.dumps(row)
        for forbidden in ("SECRET_SERIAL", "192.0.2.1", "secret", "http://"):
            self.assertNotIn(forbidden, encoded)

    def test_summary_never_claims_success_and_reports_gaps(self):
        rows = [dict(kind="sample", at=t, device="device-1", adb="connected",
                     app_process="running" if t != 360 else "not_running",
                     battery_pct=90, charging=False, screen="asleep", idle="idle", airplane="off")
                for t in (0, 60, 360)]
        result = mod.report(rows, 60, 72)
        self.assertEqual(result["acceptance_status"], "EVIDENCE_ONLY_NOT_PASSED")
        self.assertEqual(result["devices"]["device-1"]["gaps_over_3_intervals"], 1)
        self.assertEqual(result["devices"]["device-1"]["app_not_running_observations"], 1)
        self.assertEqual(result["devices"]["device-1"]["sampling_coverage_pct"], 0.07)

    def test_append_and_partial_line_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "samples.jsonl"
            row = dict(kind="sample", at=100, device="device-1", adb="connected",
                       app_process="unknown", battery_pct=None, charging=None,
                       screen="unknown", idle="unknown", airplane="unknown")
            mod.append_jsonl(path, row)
            with path.open("a") as handle:
                handle.write('{"kind":"sample"')
            self.assertEqual(mod.load_jsonl(path), [row])

    def test_rejects_unknown_serial_or_field_in_saved_sample(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "samples.jsonl"
            mod.append_jsonl(path, {"kind": "sample", "at": 1, "device": "SECRET_SERIAL"})
            mod.append_jsonl(path, {"kind": "sample", "at": 2, "device": "device-1",
                                    "secret": "wss://private-relay"})
            self.assertEqual(mod.load_jsonl(path), [])


if __name__ == "__main__":
    unittest.main()
