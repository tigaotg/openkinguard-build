"""Offline checks for the generated, pinned family relay certificate."""
from __future__ import annotations

import importlib.util
import ssl
import tempfile
import unittest
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import serialization


SCRIPT = Path(__file__).resolve().parents[1] / "plugin-files/usr/lib/openkinguard-relay/node_identity.py"
spec = importlib.util.spec_from_file_location("okg_node_identity", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


class NodeIdentityTest(unittest.TestCase):
    def test_generation_is_idempotent_and_private_key_matches_cert(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "node"
            certificate, generated = module.create_identity(destination)
            self.assertTrue(generated)
            pin = module.pin_from_certificate(certificate)
            self.assertEqual(len(pin), 64)
            private_path = destination / "privkey.pem"
            self.assertEqual(private_path.stat().st_mode & 0o777, 0o600)
            key_before = private_path.read_bytes()
            second_cert, generated_again = module.create_identity(destination)
            self.assertFalse(generated_again)
            self.assertEqual(private_path.read_bytes(), key_before)
            self.assertEqual(module.pin_from_certificate(second_cert), pin)
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(str(destination / "fullchain.pem"), str(private_path))
            key = serialization.load_pem_private_key(key_before, password=None)
            self.assertEqual(key.public_key().public_numbers(), certificate.public_key().public_numbers())
            self.assertFalse(certificate.extensions.get_extension_for_class(x509.BasicConstraints).value.ca)

    def test_refuses_to_silently_replace_missing_cert(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "node"
            module.create_identity(destination)
            (destination / "fullchain.pem").unlink()
            with self.assertRaises(RuntimeError):
                module.create_identity(destination)


if __name__ == "__main__":
    unittest.main()
