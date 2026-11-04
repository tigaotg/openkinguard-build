"""Server-side integration tests; no phones or Internet access are required."""
import asyncio
import hashlib
import json
import pathlib
import sys
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from websockets.legacy.client import connect
from websockets.legacy.server import serve
from websockets.exceptions import ConnectionClosed

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] /
                       "plugin-files/usr/lib/openkinguard-relay"))
from relay import AUTH_CONTEXT, Relay, enc, dec


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.relay = Relay({"a" * 64})
        self.server = await serve(self.relay.handler, "127.0.0.1", 0,
                                  max_size=24_000, compression=None)
        self.port = self.server.sockets[0].getsockname()[1]
        self.sockets = []

    async def asyncTearDown(self):
        for socket in self.sockets:
            await socket.close()
        self.server.close()
        await self.server.wait_closed()

    async def device(self, private_key=None):
        key = private_key or ec.generate_private_key(ec.SECP256R1())
        der = key.public_key().public_bytes(serialization.Encoding.DER,
                                            serialization.PublicFormat.SubjectPublicKeyInfo)
        self.relay.allowed = frozenset(set(self.relay.allowed) |
                                       {hashlib.sha256(der).hexdigest()})
        sock = await connect(f"ws://127.0.0.1:{self.port}/relay")
        self.sockets.append(sock)
        challenge = json.loads(await sock.recv())
        assert challenge["t"] == "challenge"
        signed = key.sign(AUTH_CONTEXT + dec(challenge["n"], 32), ec.ECDSA(hashes.SHA256()))
        await sock.send(json.dumps({"t": "hello", "key": enc(der), "sig": enc(signed)}))
        response = json.loads(await sock.recv())
        fingerprint = hashlib.sha256(der).hexdigest()
        self.assertEqual(response, {"t": "ready", "fp": fingerprint})
        return sock, fingerprint, key

    async def test_round_trip_routing_and_offline(self):
        a, a_id, _ = await self.device()
        b, b_id, _ = await self.device()
        frame = enc(b"S" * 160)
        await a.send(json.dumps({"t": "req", "id": "test1", "to": b_id, "data": frame}))
        incoming = json.loads(await b.recv())
        self.assertEqual(incoming, {"t": "request", "id": "test1", "from": a_id, "data": frame})
        await b.send(json.dumps({"t": "reply", "id": "test1", "data": frame}))
        self.assertEqual(json.loads(await a.recv()), {"t": "reply", "id": "test1", "data": frame})
        await b.close()
        await a.send(json.dumps({"t": "req", "id": "test2", "to": b_id, "data": frame}))
        self.assertEqual(json.loads(await a.recv()), {"t": "error", "id": "test2", "code": "offline"})

    async def test_cannot_spoof_another_devices_response(self):
        a, _, _ = await self.device()
        b, b_id, _ = await self.device()
        attacker, _, _ = await self.device()
        frame = enc(b"Z" * 180)
        await a.send(json.dumps({"t": "req", "id": "q", "to": b_id, "data": frame}))
        self.assertEqual(json.loads(await b.recv())["t"], "request")
        await attacker.send(json.dumps({"t": "reply", "id": "q", "data": frame}))
        with self.assertRaises(ConnectionClosed):
            await attacker.recv()
        await b.send(json.dumps({"t": "reply", "id": "q", "data": frame}))
        self.assertEqual(json.loads(await a.recv())["t"], "reply")

    async def test_reject_duplicate_login(self):
        _, fingerprint, key = await self.device()
        sock = await connect(f"ws://127.0.0.1:{self.port}/relay")
        self.sockets.append(sock)
        nonce = dec(json.loads(await sock.recv())["n"], 32)
        der = key.public_key().public_bytes(serialization.Encoding.DER,
                                            serialization.PublicFormat.SubjectPublicKeyInfo)
        await sock.send(json.dumps({"t": "hello", "key": enc(der),
            "sig": enc(key.sign(AUTH_CONTEXT + nonce, ec.ECDSA(hashes.SHA256())))}))
        with self.assertRaises(ConnectionClosed):
            await sock.recv()
        self.assertIn(fingerprint, self.relay.clients)

    async def test_reject_unknown_family_device(self):
        key = ec.generate_private_key(ec.SECP256R1())
        der = key.public_key().public_bytes(serialization.Encoding.DER,
                                            serialization.PublicFormat.SubjectPublicKeyInfo)
        sock = await connect(f"ws://127.0.0.1:{self.port}/relay")
        self.sockets.append(sock)
        nonce = dec(json.loads(await sock.recv())["n"], 32)
        signed = key.sign(AUTH_CONTEXT + nonce, ec.ECDSA(hashes.SHA256()))
        await sock.send(json.dumps({"t": "hello", "key": enc(der), "sig": enc(signed)}))
        with self.assertRaises(ConnectionClosed) as denied:
            await sock.recv()
        self.assertEqual(denied.exception.rcvd.code, 4403)
        self.assertEqual(self.relay.clients, {})

    async def test_family_connection_limit(self):
        self.relay.max_clients = 1
        _, _, _ = await self.device()
        key = ec.generate_private_key(ec.SECP256R1())
        der = key.public_key().public_bytes(serialization.Encoding.DER,
                                            serialization.PublicFormat.SubjectPublicKeyInfo)
        self.relay.allowed = frozenset(set(self.relay.allowed) |
                                       {hashlib.sha256(der).hexdigest()})
        sock = await connect(f"ws://127.0.0.1:{self.port}/relay")
        self.sockets.append(sock)
        nonce = dec(json.loads(await sock.recv())["n"], 32)
        await sock.send(json.dumps({"t": "hello", "key": enc(der),
            "sig": enc(key.sign(AUTH_CONTEXT + nonce, ec.ECDSA(hashes.SHA256())))}))
        with self.assertRaises(ConnectionClosed):
            await sock.recv()
        self.assertEqual(len(self.relay.clients), 1)

    async def test_reject_unsigned_identity(self):
        sock = await connect(f"ws://127.0.0.1:{self.port}/relay")
        self.sockets.append(sock)
        await sock.recv()
        pub = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        await sock.send(json.dumps({"t": "hello", "key": enc(pub), "sig": enc(b"fake") }))
        with self.assertRaises(ConnectionClosed):
            await sock.recv()

    async def test_reject_oversized_frame(self):
        a, _, _ = await self.device()
        _, b_id, _ = await self.device()
        await a.send(json.dumps({"t": "req", "id": "huge", "to": b_id,
                                 "data": enc(b"x" * 8193)}))
        with self.assertRaises(ConnectionClosed):
            await a.recv()

    async def test_bounded_offline_queue_waits_for_authenticated_recipient(self):
        a, a_id, _ = await self.device()
        future_b = ec.generate_private_key(ec.SECP256R1())
        pub = future_b.public_key().public_bytes(serialization.Encoding.DER,
                                                 serialization.PublicFormat.SubjectPublicKeyInfo)
        b_id = hashlib.sha256(pub).hexdigest()
        self.relay.allowed = frozenset(set(self.relay.allowed) | {b_id})
        packet = enc(b"s" * 160)
        await a.send(json.dumps({"t": "req", "id": "offline1", "to": b_id,
                                 "data": packet, "queue": True}))
        self.assertEqual(json.loads(await a.recv()), {"t": "queued", "id": "offline1"})
        self.assertEqual(len(self.relay.offline), 1)
        b, _, _ = await self.device(future_b)
        delivered = json.loads(await b.recv())
        self.assertEqual(delivered["from"], a_id)
        self.assertEqual(delivered["data"], packet)
        self.assertTrue(delivered["id"].startswith("queued-"))
        await b.send(json.dumps({"t": "reply", "id": delivered["id"], "data": packet}))
        await asyncio.sleep(0.01)
        self.assertEqual(self.relay.offline, [])
        self.assertEqual(self.relay.waiting, {})

    async def test_reject_queue_for_unauthorized_recipient(self):
        a, _, _ = await self.device()
        unknown = "b" * 64
        packet = enc(b"p" * 160)
        await a.send(json.dumps({"t": "req", "id": "not-family", "to": unknown,
                                 "data": packet, "queue": True}))
        self.assertEqual(json.loads(await a.recv()),
                         {"t": "error", "id": "not-family", "code": "denied"})
        self.assertEqual(self.relay.offline, [])

    async def test_empty_allowlist_is_forbidden(self):
        with self.assertRaises(ValueError):
            Relay(set())

    async def test_offline_queue_expires_and_rejects_excess(self):
        sender, a_id, _ = await self.device()
        b_id = "a" * 64
        packet = enc(b"p" * 160)
        for i in range(24):
            await sender.send(json.dumps({"t": "req", "id": f"q{i}",
                "to": b_id, "data": packet, "queue": True}))
            self.assertEqual(json.loads(await sender.recv())["t"], "queued")
        await sender.send(json.dumps({"t": "req", "id": "q-full", "to": b_id,
                                     "data": packet, "queue": True}))
        self.assertEqual(json.loads(await sender.recv())["code"], "queue_full")
        self.assertEqual(len(self.relay.offline), 24)
        self.assertTrue(all(item.sender == a_id for item in self.relay.offline))
        self.relay.offline[0].expires_at = 0
        self.relay.prune_offline()
        self.assertEqual(len(self.relay.offline), 23)


if __name__ == "__main__":
    unittest.main()
