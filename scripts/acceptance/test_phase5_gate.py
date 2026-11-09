import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).with_name("phase5_gate.py")
spec = importlib.util.spec_from_file_location("phase5_gate", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def good():
    sample = {
        "first_at": 10_000, "last_at": 10_000 + 72 * 3600 - 60,
        "sampling_coverage_pct": 100, "samples": 4320, "adb": {"connected": 4320},
        "gaps_over_3_intervals": 0, "app_not_running_observations": 0,
    }
    summary = {
        "format": "OpenKinGuard-phase5-72h-passive-observer-v1",
        "duration_hours_requested": 72, "interval_seconds": 60,
        "devices": {"device-1": sample, "device-2": sample},
    }
    devices = [
        {"label": "device-1", "sdk": 29, "manufacturer": "honor"},
        {"label": "device-2", "sdk": 37, "manufacturer": "xiaomi"},
    ]
    checklist = {k: True for k in module.CHECKS}
    checklist["critical_unresolved_defects"] = False
    return summary, devices, checklist


class GateTests(unittest.TestCase):
    def test_complete_evidence_is_only_ready_for_human_review(self):
        result = module.assess(*good())
        self.assertEqual(result["status"], "READY_FOR_HUMAN_REVIEW")
        self.assertIn("Neither status proves", result["disclaimer"])

    def test_rejects_missing_72h_samples(self):
        summary, devices, checklist = good()
        summary["devices"]["device-1"] = {**summary["devices"]["device-1"], "sampling_coverage_pct": 18}
        result = module.assess(summary, devices, checklist)
        self.assertEqual(result["status"], "NOT_READY")
        self.assertIn("device-1_insufficient_sampling_coverage", result["issues"])

    def test_rejects_single_brand_or_unknown_android_api(self):
        summary, devices, checklist = good()
        devices[1]["manufacturer"] = "honor"
        devices[1]["sdk"] = 31
        result = module.assess(summary, devices, checklist)
        self.assertIn("two_known_manufacturers_required", result["issues"])
        self.assertIn("android_10_and_17_required", result["issues"])

    def test_rejects_unconfirmed_sos_and_open_critical_defects(self):
        summary, devices, checklist = good()
        checklist["sos_receiver_signed_receipt"] = False
        checklist["critical_unresolved_defects"] = True
        result = module.assess(summary, devices, checklist)
        self.assertIn("missing_manual_evidence_sos_receiver_signed_receipt", result["issues"])
        self.assertIn("critical_defects_unresolved_or_unknown", result["issues"])

    def test_process_interruptions_are_review_blockers(self):
        summary, devices, checklist = good()
        summary["devices"]["device-2"] = {
            **summary["devices"]["device-2"], "app_not_running_observations": 1,
            "gaps_over_3_intervals": 2,
        }
        result = module.assess(summary, devices, checklist)
        self.assertIn("device-2_app_process_interruptions_need_review", result["issues"])
        self.assertIn("device-2_sampling_gaps_need_review", result["issues"])


if __name__ == "__main__":
    unittest.main()
