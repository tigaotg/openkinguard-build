#!/usr/bin/env python3
"""Phase-five evidence gate. Passing checks means 'ready for human review', NOT safety certification."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

CHECKS = (
    "sos_receiver_signed_receipt",
    "sos_family_explicit_confirmation",
    "checkin_receiver_signed_receipt",
    "offline_queue_recovery",
    "wifi_to_cellular_and_back",
    "relay_service_restart",
    "background_screen_off_delivery",
    "no_duplicate_sos_notification",
    "app_restart_preserves_identity_and_history",
    "seven_day_redacted_history_export",
)


def assess(summary: dict, devices: list[dict], checklist: dict) -> dict:
    issues: list[str] = []
    if summary.get("format") != "OpenKinGuard-phase5-72h-passive-observer-v1":
        issues.append("unsupported_monitor_format")
    if summary.get("duration_hours_requested") != 72:
        issues.append("monitor_duration_not_72h")
    if len(devices) != 2:
        issues.append("two_different_devices_required")
    elif len({d.get("manufacturer") for d in devices}) != 2 or (
            "unknown" in {d.get("manufacturer") for d in devices}):
        issues.append("two_known_manufacturers_required")
    sdks = {d.get("sdk") for d in devices}
    if 29 not in sdks or not any(isinstance(x, int) and x >= 37 for x in sdks):
        issues.append("android_10_and_17_required")
    per_device = summary.get("devices", {})
    if not isinstance(per_device, dict) or set(per_device) != {"device-1", "device-2"}:
        issues.append("both_device_summaries_required")
    interval = summary.get("interval_seconds", 60)
    if not isinstance(interval, int) or interval < 30 or interval > 600:
        issues.append("invalid_sample_interval")
        interval = 60
    for label in ("device-1", "device-2"):
        data = per_device.get(label, {})
        if not isinstance(data, dict):
            issues.append(f"{label}_summary_invalid")
            continue
        span = (data.get("last_at") or 0) - (data.get("first_at") or 0)
        if span < 72 * 3600 - interval * 1.5:
            issues.append(f"{label}_less_than_72h_observed")
        if data.get("sampling_coverage_pct", 0) < 95:
            issues.append(f"{label}_insufficient_sampling_coverage")
        if data.get("gaps_over_3_intervals", 0) > 0:
            issues.append(f"{label}_sampling_gaps_need_review")
        if data.get("app_not_running_observations", 0) > 0:
            issues.append(f"{label}_app_process_interruptions_need_review")
        if data.get("adb", {}).get("connected", 0) < 0.95 * data.get("samples", 0):
            issues.append(f"{label}_adb_connection_loss")
    for check in CHECKS:
        if checklist.get(check) is not True:
            issues.append("missing_manual_evidence_" + check)
    if checklist.get("critical_unresolved_defects") is not False:
        issues.append("critical_defects_unresolved_or_unknown")
    return {
        "format": "OpenKinGuard-phase5-review-gate-v1",
        "status": "READY_FOR_HUMAN_REVIEW" if not issues else "NOT_READY",
        "issues": issues,
        "disclaimer": "Neither status proves SOS reliability or approves a stable release. "
                      "Review source tests, signed receipts, devices and the complete scenario log."
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--summary", type=Path, required=True)
    p.add_argument("--devices", type=Path, required=True)
    p.add_argument("--checklist", type=Path, required=True)
    args = p.parse_args()
    result = assess(json.loads(args.summary.read_text(encoding="utf-8")),
                    json.loads(args.devices.read_text(encoding="utf-8")),
                    json.loads(args.checklist.read_text(encoding="utf-8")))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "READY_FOR_HUMAN_REVIEW" else 1


if __name__ == "__main__":
    raise SystemExit(main())
