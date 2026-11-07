#!/usr/bin/env python3
"""Root-only, tightly scoped LuCI/SSH enrollment commands for the router owner."""
from __future__ import annotations

import argparse
import base64
import json
import os
import pwd
import subprocess
from pathlib import Path

from cryptography import x509
from device_registry import DeviceRegistry
from enrollment import EnrollmentStore, INVITE_SECONDS
from node_identity import detected_public_ipv6, pin_from_certificate

STATE_DIR = Path("/var/run/openkinguard-relay")
CERT = Path("/etc/ssl/openkinguard/fullchain.pem")
FIREWALL_SECTION = "firewall.okg_family_relay_ipv6"
FIREWALL_LABEL = "OpenKinGuard family relay IPv6"
REGISTRY_FILE = Path("/etc/openkinguard-relay/devices.json")
VERSION_FILE = Path(__file__).with_name("VERSION")


def installed_version() -> str:
    try:
        value = VERSION_FILE.read_text(encoding="ascii").strip()
    except OSError:
        return "unknown"
    return value if value and len(value) <= 32 else "unknown"


def restart_relay_fail_closed() -> None:
    try:
        checked_call("/etc/init.d/openkinguard-relay", "restart")
    except Exception:
        # A stale in-memory allowlist must never keep a revoked device online.
        try:
            checked_call("/etc/init.d/openkinguard-relay", "stop")
        except Exception:
            pass
        raise


def ensure_state_dir() -> None:
    account = pwd.getpwnam("nobody")
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chown(STATE_DIR, account.pw_uid, account.pw_gid)
    os.chmod(STATE_DIR, 0o700)
    lock = STATE_DIR / "state.lock"
    lock.touch(mode=0o600, exist_ok=True)
    os.chown(lock, account.pw_uid, account.pw_gid)
    os.chmod(lock, 0o600)


def checked_call(*args: str) -> str:
    return subprocess.run(args, capture_output=True, check=True,
                          text=True, timeout=8).stdout.strip()


def persist(fingerprint: str) -> None:
    try:
        existing = checked_call("uci", "-q", "get", "openkinguard-relay.main.allowed_device")
    except subprocess.CalledProcessError:
        existing = ""
    if fingerprint in existing.split():
        return
    checked_call("uci", "-q", "add_list",
                 "openkinguard-relay.main.allowed_device=" + fingerprint)
    checked_call("uci", "commit", "openkinguard-relay")


def allowed_fingerprints() -> set[str]:
    try:
        raw = checked_call("uci", "-q", "get", "openkinguard-relay.main.allowed_device")
    except subprocess.CalledProcessError:
        raw = ""
    return {item for item in raw.split() if len(item) == 64 and
            all(char in "0123456789abcdef" for char in item)}


def change_device(action: str, fingerprint: str, store: EnrollmentStore,
                  registry: DeviceRegistry) -> dict:
    if not len(fingerprint) == 64 or any(char not in "0123456789abcdef"
                                      for char in fingerprint):
        raise ValueError("设备指纹必须是64位小写十六进制")
    allowed = allowed_fingerprints()
    if action == "revoke":
        if fingerprint not in allowed:
            raise ValueError("该设备已经不在授权名单中")
        checked_call("uci", "-q", "del_list",
                     "openkinguard-relay.main.allowed_device=" + fingerprint)
        checked_call("uci", "commit", "openkinguard-relay")
        # Also invalidate the temporary approval that would otherwise bypass UCI.
        # If this write fails, stop the relay: its old in-memory allowlist is unsafe.
        try:
            store.revoke_approval(fingerprint)
        except Exception:
            try:
                checked_call("/etc/init.d/openkinguard-relay", "stop")
            except Exception:
                pass
            raise
        # Registry metadata is secondary. Even if it cannot be written, reload the
        # UCI allowlist and invalidate existing sessions before reporting the error.
        try:
            registry.revoke(fingerprint)
        finally:
            restart_relay_fail_closed()
        return {"ok": True, "fingerprint": fingerprint, "status": "revoked"}
    if action == "delete-device":
        # Delete only previously revoked devices. Keep UCI authorization
        # unchanged; the action must never silently revoke a live device.
        if fingerprint in allowed_fingerprints():
            raise ValueError("设备目前仍已授权；请先取消绑定，再彻底删除")
        if not registry.is_revoked(fingerprint):
            raise ValueError("只能彻底删除已取消授权的设备记录")
        # Invalidate ALL old pending/approved requests for this public key.
        store.purge_device(fingerprint)
        if fingerprint in allowed_fingerprints():
            raise ValueError("设备在删除期间重新获授权，请先取消授权")
        registry.forget_revoked(fingerprint)
        return {"ok": True, "fingerprint": fingerprint, "status": "deleted"}
    if action == "restore":
        if fingerprint in allowed:
            raise ValueError("该设备已处于允许连接状态")
        if not registry.was_approved(fingerprint):
            raise ValueError("只能恢复此前明确取消的设备")
        persist(fingerprint)
        registry.approve(fingerprint)
        checked_call("/etc/init.d/openkinguard-relay", "restart")
        return {"ok": True, "fingerprint": fingerprint, "status": "approved"}
    raise ValueError("unsupported device action")


def ipv6_firewall(action: str) -> dict:
    """Change only one named IPv6/TCP WAN rule. No IPv4, SSH or LuCI changes."""
    port = checked_call("uci", "-q", "get", "openkinguard-relay.main.port")
    if not port.isdigit() or not 1024 <= int(port) <= 65535:
        raise ValueError("中继端口设置无效，无法调整防火墙")
    try:
        old_label = checked_call("uci", "-q", "get", FIREWALL_SECTION + ".name")
    except subprocess.CalledProcessError:
        old_label = ""
    try:
        section_exists = checked_call("uci", "-q", "get", FIREWALL_SECTION) == "rule"
    except subprocess.CalledProcessError:
        section_exists = False
    if section_exists and old_label != FIREWALL_LABEL:
        raise ValueError("防火墙存在同编号但不属于 OpenKinGuard 的规则，拒绝覆盖")
    if old_label and old_label != FIREWALL_LABEL:
        raise ValueError("已有同名的其他防火墙规则，拒绝覆盖")
    if action == "open-ipv6":
        if not old_label:
            checked_call("uci", "set", FIREWALL_SECTION + "=rule")
        options = {"name": FIREWALL_LABEL, "src": "wan", "proto": "tcp",
                   "dest_port": port, "family": "ipv6", "target": "ACCEPT"}
        for key, value in options.items():
            checked_call("uci", "set", FIREWALL_SECTION + "." + key + "=" + value)
    elif action == "close-ipv6" and old_label:
        checked_call("uci", "delete", FIREWALL_SECTION)
    if action != "firewall-status" and (action == "open-ipv6" or old_label):
        checked_call("uci", "commit", "firewall")
        checked_call("/etc/init.d/firewall", "reload")
    return {"ok": True, "enabled": action == "open-ipv6" or (
        action == "firewall-status" and bool(old_label)), "port": int(port),
        "ipv6Only": True}


def invite(store: EnrollmentStore) -> dict:
    ipv6 = detected_public_ipv6()
    if not ipv6:
        raise ValueError("路由器没有检测到可用的公网 IPv6；不能生成无法连接的邀请二维码")
    if not CERT.exists():
        raise ValueError("先执行 --setup-no-domain，生成家庭节点 TLS 证书")
    cert = x509.load_pem_x509_certificate(CERT.read_bytes())
    port = checked_call("uci", "-q", "get", "openkinguard-relay.main.port")
    if not port.isdigit() or not 1024 <= int(port) <= 65535:
        raise ValueError("路由器中继端口设置无效")
    url = f"wss://[{ipv6}]:{port}/relay#spki={pin_from_certificate(cert)}"
    payload = json.dumps({"v": 1, "kind": "OpenKinGuardRelayJoin", "url": url,
                          "token": store.new_invite()}, separators=(",", ":"))
    try:
        svg = checked_call("qrencode", "-t", "SVG", "-l", "M", "-m", "2", "-o", "-", payload)
    except (FileNotFoundError, subprocess.CalledProcessError):
        raise ValueError("请先在路由器执行 apk -U add qrencode，再生成邀请二维码")
    return {"ok": True, "payload": payload,
            "svgBase64": base64.b64encode(svg.encode()).decode(),
            "validSeconds": INVITE_SECONDS,
            "nodeFingerprint": pin_from_certificate(cert)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("invite", "list", "approve", "reject",
                                           "revoke", "restore", "delete-device", "set-note",
                                           "firewall-status", "open-ipv6", "close-ipv6", "version"))
    parser.add_argument("request_id", nargs="?")
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    try:
        if os.geteuid() != 0:
            raise ValueError("只有路由器管理员可管理加入申请")
        ensure_state_dir()
        store = EnrollmentStore(STATE_DIR)
        registry = DeviceRegistry(REGISTRY_FILE)
        if args.value is not None and args.action != "set-note":
            raise ValueError("该指令不接受额外参数")
        if args.action == "version":
            if args.request_id:
                raise ValueError("version 不接受额外参数")
            result = {"ok": True, "version": installed_version()}
        elif args.action in ("firewall-status", "open-ipv6", "close-ipv6"):
            if args.request_id:
                raise ValueError("防火墙指令不接受额外参数")
            result = ipv6_firewall(args.action)
        elif args.action == "invite":
            if args.request_id:
                raise ValueError("invite 不接受额外参数")
            result = invite(store)
        elif args.action == "list":
            if args.request_id:
                raise ValueError("list 不接受额外参数")
            result = {"ok": True, "pending": store.list_pending(),
                      **registry.list_devices(allowed_fingerprints())}
        elif args.action in ("revoke", "restore", "delete-device"):
            if not args.request_id:
                raise ValueError("请指定要取消、恢复或彻底删除的设备指纹")
            result = change_device(args.action, args.request_id, store, registry)
        elif args.action == "set-note":
            if not args.request_id or args.value is None:
                raise ValueError("请输入设备指纹和备注；清空备注可传空字符串")
            if args.request_id in allowed_fingerprints():
                registry.ensure_legacy(args.request_id)
            registry.set_note(args.request_id, args.value)
            result = {"ok": True, "fingerprint": args.request_id, "status": "saved"}
        else:
            if not args.request_id:
                raise ValueError("请提供待批准申请的编号")
            approved = args.action == "approve"
            request = next((item for item in store.list_pending()
                            if item["id"] == args.request_id), None)
            if request is None:
                raise ValueError("申请不存在或已过期")
            def approve_and_record(fingerprint: str) -> None:
                persist(fingerprint)
                registry.approve(fingerprint, request["nickname"], request["model"])
            fp = store.decide(args.request_id, approved,
                              persist_approval=approve_and_record if approved else None)
            result = {"ok": True, "fingerprint": fp,
                      "status": "approved" if approved else "rejected"}
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        result = {"ok": False, "error": str(error)[:150]}
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
