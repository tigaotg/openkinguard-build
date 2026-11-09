#!/usr/bin/env python3
"""OpenKinGuard S5: privacy-limited, passive ADB monitor for 72-hour field acceptance.

This program never changes device settings, starts applications, reads app private
files, or requests privileges. It only reads coarse device/battery/app-process state.
SOS delivery/peer reachability must be verified separately with the app's redacted
seven-day connection history export and the manual acceptance checklist.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

DEFAULT_PACKAGE = "io.github.tigaotg.openkinguard.debug.pilotv4"
API_MIN = 29
METADATA_KEYS = {"label", "sdk", "manufacturer"}
SAMPLE_KEYS = {"kind", "at", "device", "adb", "app_process", "battery_pct",
               "charging", "screen", "idle", "airplane"}


def category(value: str) -> str:
    """Discard every raw device error, IP, token and sensitive system dump."""
    text = value.lower()
    if "timeout" in text or "timed out" in text:
        return "timeout"
    if "unauthorized" in text:
        return "unauthorized"
    if "offline" in text or "no devices" in text:
        return "offline"
    return "unavailable"


def adb(adb_bin: str, serial: str, *args: str, timeout: int = 12) -> tuple[bool, str]:
    try:
        result = subprocess.run([adb_bin, "-s", serial, *args], capture_output=True,
                                text=True, timeout=timeout, check=False)
        return result.returncode == 0, result.stdout if result.returncode == 0 else category(result.stderr)
    except subprocess.TimeoutExpired:
        return False, "timeout"
    except (OSError, ValueError):
        return False, "unavailable"


def parse_battery(raw: str) -> tuple[int | None, bool | None]:
    match = re.search(r"(?m)^\s*level:\s*(\d+)\s*$", raw)
    level = int(match.group(1)) if match else None
    if level is not None and not 0 <= level <= 100:
        level = None
    status = re.search(r"(?m)^\s*status:\s*(\d+)\s*$", raw)
    charging = int(status.group(1)) in (2, 5) if status else None
    return level, charging


def parse_idle(raw: str) -> str:
    match = re.search(r"(?m)^\s*mState=(\w+)", raw)
    name = match.group(1).lower() if match else "unknown"
    return name if name in {"active", "inactive", "idle", "idle_maintenance",
                            "sensing", "locating", "light_idle", "light_idle_maintenance",
                            "idle_pending", "quick_doze_delay", "wait_for_unlock"} else "other"


def parse_screen(raw: str) -> str:
    # Raw dumpsys output (which may contain package names) never leaves memory.
    m = re.search(r"\bmWakefulness=(Awake|Asleep|Dozing|Dreaming)\b", raw)
    if m:
        return m.group(1).lower()
    m = re.search(r"\bmInteractive=(true|false)\b", raw)
    return "awake" if m and m.group(1) == "true" else "asleep" if m else "unknown"


def parse_airplane(raw: str) -> str:
    return "on" if raw.strip() == "1" else "off" if raw.strip() == "0" else "unknown"


def safe_manufacturer(raw: str) -> str:
    return re.sub(r"[^a-z0-9_-]", "", raw.strip().lower())[:32] or "unknown"


def device_meta(adb_bin: str, serial: str, label: str) -> dict:
    ok_sdk, raw_sdk = adb(adb_bin, serial, "shell", "getprop", "ro.build.version.sdk")
    ok_manu, raw_manu = adb(adb_bin, serial, "shell", "getprop", "ro.product.manufacturer")
    sdk = int(raw_sdk.strip()) if ok_sdk and raw_sdk.strip().isdigit() else None
    return {"label": label, "sdk": sdk, "manufacturer": safe_manufacturer(raw_manu) if ok_manu else "unknown"}


def sample_device(adb_bin: str, serial: str, label: str, package: str,
                  now: float | None = None) -> dict:
    stamp = time.time() if now is None else now
    row = {"kind": "sample", "at": round(stamp, 3), "device": label,
           "adb": "unavailable", "app_process": "unknown", "battery_pct": None,
           "charging": None, "screen": "unknown", "idle": "unknown", "airplane": "unknown"}
    ok, state = adb(adb_bin, serial, "get-state")
    if not ok or state.strip() != "device":
        row["adb"] = category(state) if not ok else "offline"
        return row
    row["adb"] = "connected"
    process_ok, process = adb(adb_bin, serial, "shell", "pidof", "-s", package)
    row["app_process"] = "running" if process_ok and process.strip().isdigit() else (
        "not_running" if not process_ok and process.strip() == "" else "unknown")
    battery_ok, battery = adb(adb_bin, serial, "shell", "dumpsys", "battery")
    if battery_ok:
        row["battery_pct"], row["charging"] = parse_battery(battery)
    screen_ok, screen = adb(adb_bin, serial, "shell", "dumpsys", "power")
    if screen_ok:
        row["screen"] = parse_screen(screen)
    idle_ok, idle = adb(adb_bin, serial, "shell", "dumpsys", "deviceidle")
    if idle_ok:
        row["idle"] = parse_idle(idle)
    airplane_ok, airplane = adb(adb_bin, serial, "shell", "settings", "get", "global", "airplane_mode_on")
    if airplane_ok:
        row["airplane"] = parse_airplane(airplane)
    return row


def append_jsonl(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n")
        handle.flush()


def load_jsonl(path: Path) -> list[dict]:
    values = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            try:
                v = json.loads(line)
            except json.JSONDecodeError:
                continue  # A power loss may truncate the final line.
            if isinstance(v, dict) and v.get("kind") == "sample" and (
                    set(v) <= SAMPLE_KEYS) and v.get("device") in {"device-1", "device-2"}:
                values.append(v)
    return values


def report(samples: list[dict], interval: int, duration_hours: int) -> dict:
    if interval < 30:
        raise ValueError("sample interval must be at least 30 seconds")
    groups = defaultdict(list)
    for row in samples:
        groups[row["device"]].append(row)
    summaries = {}
    for label, readings in sorted(groups.items()):
        readings.sort(key=lambda x: x["at"])
        timestamps = [r["at"] for r in readings]
        gaps = [max(0.0, later - earlier) for earlier, later in zip(timestamps, timestamps[1:])]
        # Sampling coverage is not real-time availability; gaps mean unobserved periods.
        expected = (duration_hours * 3600) / interval
        coverage = min(100.0, round(100 * len(readings) / expected, 2)) if expected else 0.0
        counts = Counter(r["app_process"] for r in readings)
        adb_status = Counter(r["adb"] for r in readings)
        battery = [(r["at"], r["battery_pct"]) for r in readings
                   if isinstance(r.get("battery_pct"), int) and r.get("charging") is False]
        summaries[label] = {
            "samples": len(readings), "sampling_coverage_pct": coverage,
            "first_at": timestamps[0], "last_at": timestamps[-1],
            "largest_sampling_gap_seconds": round(max(gaps, default=0), 1),
            "gaps_over_3_intervals": sum(g > interval * 3 for g in gaps),
            "adb": dict(adb_status), "app_process": dict(counts),
            "app_not_running_observations": counts["not_running"],
            "start_battery_pct_uncharged": battery[0][1] if battery else None,
            "end_battery_pct_uncharged": battery[-1][1] if battery else None,
        }
    return {"format": "OpenKinGuard-phase5-72h-passive-observer-v1",
            "duration_hours_requested": duration_hours,
            "interval_seconds": interval,
            "devices": summaries,
            "acceptance_status": "EVIDENCE_ONLY_NOT_PASSED",
            "limitations": ["App E2EE and SOS receipts require separate authenticated app evidence",
                            "ADB connection gaps do not establish that the phone was offline",
                            "Never infer safety from a continuous Android PID alone"]}


def collect(args: argparse.Namespace) -> int:
    if len(args.serial) != 2 or args.serial[0] == args.serial[1]:
        raise SystemExit("--serial must be supplied twice with two different devices")
    if args.interval < 30 or args.interval > 600 or args.duration_hours < 1 or args.duration_hours > 168:
        raise SystemExit("interval must be 30..600 seconds; duration hours 1..168")
    if not re.fullmatch(r"[A-Za-z0-9._]+", args.package):
        raise SystemExit("invalid package name")
    root = Path(args.output_dir).resolve()
    root.mkdir(parents=True, exist_ok=True)
    if root.is_symlink():
        raise SystemExit("output directory must not be a symbolic link")
    records = root / "samples.jsonl"
    if records.exists():
        raise SystemExit("output already contains samples.jsonl; specify a new directory")
    meta = [device_meta(args.adb, serial, f"device-{i}") for i, serial in enumerate(args.serial, 1)]
    (root / "devices.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    if any(item["sdk"] is not None and item["sdk"] < API_MIN for item in meta):
        raise SystemExit("device below Android 10 (SDK 29)")
    print("Monitoring device-1 and device-2; raw serials, IPs and dumpsys are NOT written.", flush=True)
    start = time.monotonic()
    target = start + args.duration_hours * 3600
    iterations = 0
    try:
        while time.monotonic() < target:
            stamp = time.time()
            summary = []
            for index, serial in enumerate(args.serial, 1):
                row = sample_device(args.adb, serial, f"device-{index}", args.package, stamp)
                append_jsonl(records, row)
                summary.append(f"device-{index}: adb={row['adb']}, app={row['app_process']}, battery={row['battery_pct']}")
            iterations += 1
            if iterations % 10 == 1:
                print(datetime.now(timezone.utc).isoformat(), " | ".join(summary), flush=True)
            time.sleep(max(0, start + iterations * args.interval - time.monotonic()))
    except KeyboardInterrupt:
        print("Interrupted. Partial telemetry remains usable and is NOT acceptance proof.")
    result = report(load_jsonl(records), args.interval, args.duration_hours)
    (root / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    cmd = sub.add_parser("collect", help="Record two physical devices (passive; no APK installs)")
    cmd.add_argument("--serial", action="append", required=True, help="ADB serial (used in memory only)")
    cmd.add_argument("--package", required=True, help="Installed OpenKinGuard applicationId")
    cmd.add_argument("--adb", default="adb")
    cmd.add_argument("--duration-hours", type=int, default=72)
    cmd.add_argument("--interval", type=int, default=60, help="Sampling seconds (30..600)")
    cmd.add_argument("--output-dir", required=True)
    view = sub.add_parser("summarize", help="Summarize a recorded sampling file")
    view.add_argument("--input", required=True)
    view.add_argument("--duration-hours", type=int, default=72)
    view.add_argument("--interval", type=int, default=60)
    args = parser.parse_args(argv)
    if args.command == "collect":
        return collect(args)
    output = report(load_jsonl(Path(args.input)), args.interval, args.duration_hours)
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
