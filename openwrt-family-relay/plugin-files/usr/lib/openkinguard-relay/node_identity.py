#!/usr/bin/env python3
"""Generate an explicitly pinned TLS identity for a private family relay.

The SPKI SHA-256 pin is public; the private key must never be shared.  This
certificate is NOT trusted by the public Web PKI.  Android clients must use
the exact node pin supplied by the owner before accepting it.
"""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def pin_from_certificate(certificate: x509.Certificate) -> str:
    public_key = certificate.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(public_key).hexdigest()


def detected_public_ipv6() -> str | None:
    """Read-only network status; never edit OpenWrt network settings."""
    for interface in ("wan_6", "wan6"):
        try:
            raw = subprocess.run(
                ["ubus", "call", f"network.interface.{interface}", "status"],
                check=True, capture_output=True, text=True, timeout=3
            ).stdout
            data = json.loads(raw)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired,
                ValueError):
            continue
        if not data.get("up"):
            continue
        for entry in data.get("ipv6-address", []):
            try:
                address = ipaddress.IPv6Address(entry.get("address", ""))
                if address.is_global:
                    return str(address)
            except ValueError:
                continue
    return None


def create_identity(directory: Path) -> tuple[x509.Certificate, bool]:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    key_path = directory / "privkey.pem"
    cert_path = directory / "fullchain.pem"
    if key_path.exists() != cert_path.exists():
        raise RuntimeError("证书和私钥只有一个存在；为避免丢失节点身份，拒绝自动覆盖")
    if key_path.exists():
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        actual = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        expected = cert.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        if actual != expected:
            raise RuntimeError("证书与私钥不匹配；拒绝替换已保存的节点身份")
        return cert, False

    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "OpenKinGuard private family relay")
    ])
    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5))
        .not_valid_after(now + timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .sign(key, hashes.SHA256())
    )
    key_data = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    cert_data = cert.public_bytes(serialization.Encoding.PEM)
    # Exclusive creation: a failed or concurrent invocation must not replace a key.
    with key_path.open("xb") as output:
        os.chmod(key_path, 0o600)
        output.write(key_data)
    with cert_path.open("xb") as output:
        os.chmod(cert_path, 0o644)
        output.write(cert_data)
    return cert, True


def main() -> None:
    parser = argparse.ArgumentParser(description="OpenKinGuard 私人节点免域名 TLS 身份")
    parser.add_argument("--directory", default="/etc/ssl/openkinguard")
    parser.add_argument("--show-url", action="store_true")
    args = parser.parse_args()
    certificate, generated = create_identity(Path(args.directory))
    print("节点身份：" + ("已生成" if generated else "已存在，保持不变"))
    print("节点 SPKI-SHA256 指纹：" + pin_from_certificate(certificate))
    print("TLS 证书：" + str(Path(args.directory) / "fullchain.pem"))
    print("TLS 私钥：" + str(Path(args.directory) / "privkey.pem"))
    if args.show_url:
        address = detected_public_ipv6()
        if address:
            port = "9443"
            try:
                configured = subprocess.run(
                    ["uci", "-q", "get", "openkinguard-relay.main.port"],
                    check=True, capture_output=True, text=True, timeout=3,
                ).stdout.strip()
                if configured.isascii() and configured.isdigit() and 1024 <= int(configured) <= 65535:
                    port = configured
            except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass
            print("家庭节点地址（复制到两台手机）：")
            print(f"wss://[{address}]:{port}/relay#spki={pin_from_certificate(certificate)}")
        else:
            print("未自动检测到可用公网 IPv6；请先核对 wan_6 / wan6 网络状态。")
        print("动态公网 IPv6 可能变化；变化后需要重新填写两台手机的节点地址。")
    print("请只分享节点地址和指纹，永远不要分享私钥。")


if __name__ == "__main__":
    main()
