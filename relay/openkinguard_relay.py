#!/usr/bin/env python3
"""OpenKinGuard S2 authenticated WebSocket forwarder. Python >=3.10.

The relay knows connected device public-key fingerprints, not family relations.
It cannot forge signed OpenKinGuard application messages. When expressly
requested, it temporarily buffers a bounded number of offline notifications
in RAM, never on disk. Restarting the process discards this queue.
Place behind a TLS reverse proxy (wss://) or pass a TLS certificate directly.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import logging
import secrets
import ssl
import time
from dataclasses import dataclass

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

LOG = logging.getLogger("openkinguard.relay")
AUTH_CONTEXT = b"OpenKinGuard|relay-auth-v1|"
MAX_MESSAGE_BYTES = 24_000
MAX_FRAME_BYTES = 8192
REQUEST_TIMEOUT_SECONDS = 18
MAX_PENDING_PER_DEVICE = 16
OFFLINE_TTL_SECONDS = 300
MAX_QUEUED_TOTAL = 256
MAX_QUEUED_PER_RECIPIENT = 24
MAX_QUEUED_PER_SENDER = 32


def enc(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def dec(value: str, max_size: int) -> bytes:
    if not isinstance(value, str) or len(value) > max_size * 2:
        raise ValueError("invalid base64 data")
    result = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    if len(result) > max_size:
        raise ValueError("packet exceeds limit")
    return result


@dataclass
class Pending:
    sender: str
    recipient: str
    created: float
    queued: bool = False


@dataclass
class Queued:
    request_id: str
    sender: str
    recipient: str
    data: str
    expires_at: float
    inflight: bool = False


class Relay:
    def __init__(self) -> None:
        self.clients: dict[str, ServerConnection] = {}
        self.waiting: dict[str, Pending] = {}
        self.offline: list[Queued] = []

    def prune_offline(self) -> None:
        now = time.monotonic()
        self.offline = [item for item in self.offline if item.expires_at > now]

    def mark_queued_idle(self, request_id: str) -> None:
        for item in self.offline:
            if item.request_id == request_id:
                item.inflight = False

    async def enqueue(self, sender: str, recipient: str, data: str) -> bool:
        self.prune_offline()
        if len(self.offline) >= MAX_QUEUED_TOTAL or sum(
            item.recipient == recipient for item in self.offline
        ) >= MAX_QUEUED_PER_RECIPIENT or sum(
            item.sender == sender for item in self.offline
        ) >= MAX_QUEUED_PER_SENDER:
            return False
        self.offline.append(Queued("queued-" + secrets.token_hex(16), sender,
                                   recipient, data, time.monotonic() + OFFLINE_TTL_SECONDS))
        return True

    async def flush_offline(self, recipient: str) -> None:
        self.prune_offline()
        for item in self.offline:
            if item.recipient != recipient or item.inflight:
                continue
            item.inflight = True
            self.waiting[item.request_id] = Pending(item.sender, recipient,
                                                    time.monotonic(), queued=True)
            if await self.send(recipient, {"t": "request", "id": item.request_id,
                                           "from": item.sender, "data": item.data}):
                asyncio.create_task(self.expire(item.request_id, item.sender))
            else:
                self.waiting.pop(item.request_id, None)
                item.inflight = False
                break

    async def send(self, peer: str, message: dict) -> bool:
        socket = self.clients.get(peer)
        if socket is None:
            return False
        try:
            await socket.send(json.dumps(message, separators=(",", ":")))
            return True
        except ConnectionClosed:
            return False

    async def authenticate(self, socket: ServerConnection) -> str:
        nonce = secrets.token_bytes(32)
        await socket.send(json.dumps({"t": "challenge", "n": enc(nonce)}))
        raw = await asyncio.wait_for(socket.recv(), timeout=8)
        hello = json.loads(raw)
        if not isinstance(hello, dict) or hello.get("t") != "hello":
            raise ValueError("expected signed hello")
        pub_der = dec(hello.get("key"), 250)
        signature = dec(hello.get("sig"), 100)
        public_key = serialization.load_der_public_key(pub_der)
        if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(
            public_key.curve, ec.SECP256R1
        ):
            raise ValueError("only P-256 identities supported")
        try:
            public_key.verify(signature, AUTH_CONTEXT + nonce, ec.ECDSA(hashes.SHA256()))
        except InvalidSignature as exc:
            raise ValueError("invalid identity signature") from exc
        fingerprint = hashlib.sha256(pub_der).hexdigest()
        if fingerprint in self.clients:
            raise ValueError("identity already connected")
        self.clients[fingerprint] = socket
        await socket.send(json.dumps({"t": "ready", "fp": fingerprint}))
        LOG.info("Connected identity %s", fingerprint[:12])
        return fingerprint

    async def expire(self, request_id: str, sender: str) -> None:
        await asyncio.sleep(REQUEST_TIMEOUT_SECONDS)
        pending = self.waiting.pop(request_id, None)
        if pending is not None and pending.sender == sender:
            if pending.queued:
                self.mark_queued_idle(request_id)
            else:
                await self.send(sender, {"t": "error", "id": request_id, "code": "timeout"})

    async def receive(self, identity: str, payload: dict) -> None:
        kind = payload.get("t")
        request_id = payload.get("id")
        if not isinstance(request_id, str) or not (1 <= len(request_id) <= 70):
            raise ValueError("invalid request ID")
        if kind == "req":
            recipient = payload.get("to")
            if not isinstance(recipient, str) or len(recipient) != 64 or any(
                c not in "0123456789abcdef" for c in recipient
            ):
                raise ValueError("invalid recipient fingerprint")
            frame = dec(payload.get("data"), MAX_FRAME_BYTES)
            if len(frame) < 100:
                raise ValueError("invalid signed application frame")
            if sum(p.sender == identity for p in self.waiting.values()) >= MAX_PENDING_PER_DEVICE:
                await self.send(identity, {"t": "error", "id": request_id, "code": "busy"})
                return
            if request_id in self.waiting:
                await self.send(identity, {"t": "error", "id": request_id, "code": "duplicate"})
                return
            if recipient == identity:
                await self.send(identity, {"t": "error", "id": request_id, "code": "offline"})
                return
            if recipient not in self.clients:
                if payload.get("queue") is True:
                    accepted = await self.enqueue(identity, recipient, payload["data"])
                    await self.send(identity, {"t": "queued" if accepted else "error",
                                               "id": request_id,
                                               **({} if accepted else {"code": "queue_full"})})
                else:
                    await self.send(identity, {"t": "error", "id": request_id, "code": "offline"})
                return
            self.waiting[request_id] = Pending(identity, recipient, time.monotonic())
            delivered = await self.send(recipient, {
                "t": "request", "id": request_id, "from": identity, "data": payload["data"]
            })
            if not delivered:
                self.waiting.pop(request_id, None)
                await self.send(identity, {"t": "error", "id": request_id, "code": "offline"})
            else:
                asyncio.create_task(self.expire(request_id, identity))
        elif kind in ("reply", "reject"):
            pending = self.waiting.get(request_id)
            if pending is None or pending.recipient != identity:
                raise ValueError("unrecognized response or wrong replying device")
            if kind == "reply":
                frame = dec(payload.get("data"), MAX_FRAME_BYTES)
                if len(frame) < 100:
                    raise ValueError("invalid signed response frame")
                response = {"t": "reply", "id": request_id, "data": payload["data"]}
            else:
                code = payload.get("code", "remote_error")
                if code not in {"denied", "remote_error", "busy"}:
                    code = "remote_error"
                response = {"t": "error", "id": request_id, "code": code}
            self.waiting.pop(request_id, None)
            if pending.queued:
                self.offline = [item for item in self.offline
                                if item.request_id != request_id]
            else:
                await self.send(pending.sender, response)
        else:
            raise ValueError("unsupported message type")

    async def handler(self, socket: ServerConnection) -> None:
        identity = None
        try:
            if socket.request.path != "/relay":
                await socket.close(code=1008, reason="Use /relay")
                return
            identity = await self.authenticate(socket)
            await self.flush_offline(identity)
            async for raw in socket:
                payload = json.loads(raw)
                if not isinstance(payload, dict):
                    raise ValueError("object required")
                await self.receive(identity, payload)
        except (ValueError, TypeError, json.JSONDecodeError, asyncio.TimeoutError) as exc:
            LOG.warning("Rejected connection/request %s: %s", (identity or "unknown")[:12], exc)
            await socket.close(code=1008, reason="Invalid protocol")
        except ConnectionClosed:
            pass
        finally:
            if identity is not None and self.clients.get(identity) is socket:
                self.clients.pop(identity, None)
                for req_id, pending in list(self.waiting.items()):
                    if pending.recipient == identity:
                        self.waiting.pop(req_id, None)
                        if pending.queued:
                            self.mark_queued_idle(req_id)
                        else:
                            await self.send(pending.sender, {"t": "error", "id": req_id, "code": "offline"})
                    elif pending.sender == identity and not pending.queued:
                        self.waiting.pop(req_id, None)
                LOG.info("Disconnected identity %s", identity[:12])


async def run(host: str, port: int, tls_cert: str | None, tls_key: str | None) -> None:
    context = None
    if tls_cert and tls_key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(tls_cert, tls_key)
    elif host not in {"127.0.0.1", "::1", "localhost"}:
        raise ValueError("Plain WS must bind to loopback; use --cert/--key or a local TLS reverse proxy")
    relay = Relay()
    async with serve(relay.handler, host, port, ssl=context,
                     max_size=MAX_MESSAGE_BYTES, max_queue=16, ping_interval=25,
                     ping_timeout=20, compression=None):
        LOG.info("Relay listening at %s:%d%s/relay", host, port, " TLS " if context else "")
        await asyncio.Future()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1", help="127.0.0.1 for proxy, 0.0.0.0 with TLS only")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cert", help="TLS fullchain PEM; omit when proxy terminates TLS")
    parser.add_argument("--key", help="TLS private key PEM")
    args = parser.parse_args()
    if bool(args.cert) != bool(args.key):
        parser.error("--cert and --key must be specified together")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(run(args.listen, args.port, args.cert, args.key))


if __name__ == "__main__":
    main()
