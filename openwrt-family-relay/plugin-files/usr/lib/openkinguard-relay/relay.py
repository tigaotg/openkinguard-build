#!/usr/bin/env python3
"""OpenKinGuard family-only S2 WebSocket relay for OpenWrt. Python >=3.10.

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
import os
import pwd
import re
import secrets
import ssl
import time
from collections import deque
from dataclasses import dataclass

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
# OpenWrt 25.12 ships python3-websockets 11.x, whose supported server API
# predates websockets.asyncio. The legacy API also works on newer 14-16.x.
from websockets.legacy.server import WebSocketServerProtocol as ServerConnection, serve
from websockets.exceptions import ConnectionClosed
from enrollment import EnrollmentStore, clean_device_label


class RelayNotAuthorized(ValueError):
    """Used only after a valid signed identity was checked against owner approval."""

LOG = logging.getLogger("openkinguard.relay")
AUTH_CONTEXT = b"OpenKinGuard|relay-auth-v1|"
JOIN_CONTEXT = b"OpenKinGuard|relay-join-v1|"
MAX_MESSAGE_BYTES = 24_000
MAX_FRAME_BYTES = 8192
REQUEST_TIMEOUT_SECONDS = 18
MAX_PENDING_PER_DEVICE = 16
OFFLINE_TTL_SECONDS = 300
MAX_QUEUED_TOTAL = 256
MAX_QUEUED_PER_RECIPIENT = 24
MAX_QUEUED_PER_SENDER = 32
FINGERPRINT = re.compile(r"[0-9a-f]{64}\Z")
MAX_REQUESTS_PER_MINUTE = 120


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
    kind: str = "req"


@dataclass
class Queued:
    request_id: str
    sender: str
    recipient: str
    data: str
    expires_at: float
    inflight: bool = False


class Relay:
    def __init__(self, allowed: set[str], max_clients: int = 16,
                 rate_kbps: int = 25_000,
                 enrollment: EnrollmentStore | None = None) -> None:
        if (not allowed and enrollment is None) or not all(
            FINGERPRINT.fullmatch(fp) for fp in allowed
        ):
            raise ValueError("family relay needs an allowlist or owner-approved enrollment")
        if max_clients not in range(1, 65) or rate_kbps not in range(64, 25_001):
            raise ValueError("invalid connection count or outbound rate")
        self.allowed = frozenset(allowed)
        self.enrollment = enrollment
        self.join_slots = asyncio.Semaphore(3)
        self.max_clients = max_clients
        self.rate_bytes_per_sec = rate_kbps * 1000 / 8
        self.burst_bytes = max(MAX_MESSAGE_BYTES, min(self.rate_bytes_per_sec, 262_144))
        self.tokens = self.burst_bytes
        self.last_token_time = time.monotonic()
        self.send_lock = asyncio.Lock()
        self.clients: dict[str, ServerConnection] = {}
        self.waiting: dict[str, Pending] = {}
        self.offline: list[Queued] = []
        self.request_times: dict[str, deque[float]] = {}

    async def budget_send(self, size: int) -> None:
        """Approximate application egress cap, not an OS-level WAN/SQM shaper."""
        async with self.send_lock:
            now = time.monotonic()
            self.tokens = min(self.burst_bytes,
                              self.tokens + (now - self.last_token_time) * self.rate_bytes_per_sec)
            self.last_token_time = now
            if size > self.tokens:
                await asyncio.sleep((size - self.tokens) / self.rate_bytes_per_sec)
                self.last_token_time = time.monotonic()
                self.tokens = 0
            else:
                self.tokens -= size

    def check_rate(self, identity: str) -> None:
        now = time.monotonic()
        times = self.request_times.setdefault(identity, deque())
        while times and times[0] <= now - 60:
            times.popleft()
        if len(times) >= MAX_REQUESTS_PER_MINUTE:
            raise ValueError("per-device request rate exceeded")
        times.append(now)

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
            wire = json.dumps(message, separators=(",", ":"))
            await self.budget_send(len(wire.encode("utf-8")))
            await socket.send(wire)
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
        if fingerprint not in self.allowed and not (
            self.enrollment and self.enrollment.is_approved(fingerprint)
        ):
            raise RelayNotAuthorized("identity not on family allowlist")
        if fingerprint in self.clients:
            raise ValueError("identity already connected")
        if len(self.clients) >= self.max_clients:
            raise ValueError("family connection limit reached")
        self.clients[fingerprint] = socket
        await socket.send(json.dumps({"t": "ready", "fp": fingerprint,
                                      "caps": ["family-peers-v1", "family-pair-v1"]}))
        LOG.info("Connected identity %s", fingerprint[:12])
        return fingerprint

    async def expire(self, request_id: str, sender: str) -> None:
        await asyncio.sleep(REQUEST_TIMEOUT_SECONDS)
        pending = self.waiting.pop(request_id, None)
        if pending is not None and pending.sender == sender:
            if pending.queued:
                self.mark_queued_idle(request_id)
            else:
                await self.send(sender, {"t": "pair_error" if pending.kind == "pair" else "error",
                                         "id": request_id, "code": "timeout"})

    async def receive(self, identity: str, payload: dict) -> None:
        self.check_rate(identity)
        kind = payload.get("t")
        request_id = payload.get("id")
        if not isinstance(request_id, str) or not (1 <= len(request_id) <= 70):
            raise ValueError("invalid request ID")
        if kind == "peers":
            # Only authenticated, owner-approved participants can query ONLINE
            # peers. No medical data, nickname, offline list or relation is exposed.
            await self.send(identity, {"t": "peer_list", "id": request_id,
                                       "peers": sorted(fp for fp in self.clients if fp != identity)})
        elif kind == "pair_cancel":
            recipient = payload.get("to")
            session_id = payload.get("session")
            if (not isinstance(recipient, str) or not FINGERPRINT.fullmatch(recipient) or
                    not isinstance(session_id, str) or not re.fullmatch(
                        r"[0-9a-f-]{36}", session_id)):
                raise ValueError("invalid pairing cancellation")
            if recipient != identity:
                await self.send(recipient, {"t": "pair_cancelled", "from": identity,
                                            "session": session_id})
        elif kind in ("req", "pair"):
            recipient = payload.get("to")
            if not isinstance(recipient, str) or len(recipient) != 64 or any(
                c not in "0123456789abcdef" for c in recipient
            ):
                raise ValueError("invalid recipient fingerprint")
            if recipient not in self.allowed and not (
                self.enrollment and self.enrollment.is_approved(recipient)
            ):
                await self.send(identity, {"t": "error", "id": request_id, "code": "denied"})
                return
            if kind == "req":
                frame = dec(payload.get("data"), MAX_FRAME_BYTES)
                if len(frame) < 100:
                    raise ValueError("invalid signed application frame")
            else:
                # The phones validate the *signed* four-step pairing protocol;
                # the family relay only routes bounded, never-offline invitations.
                frame = payload.get("frame")
                if not isinstance(frame, str) or not 100 <= len(frame) <= 4000 or not frame.isascii():
                    raise ValueError("invalid pairing frame")
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
            self.waiting[request_id] = Pending(identity, recipient, time.monotonic(),
                                               kind=kind)
            delivered = await self.send(recipient, {
                "t": "pair_request" if kind == "pair" else "request",
                "id": request_id, "from": identity,
                **({"frame": frame} if kind == "pair" else {"data": payload["data"]})
            })
            if not delivered:
                self.waiting.pop(request_id, None)
                await self.send(identity, {"t": "error", "id": request_id, "code": "offline"})
            else:
                asyncio.create_task(self.expire(request_id, identity))
        elif kind in ("reply", "reject", "pair_reply"):
            pending = self.waiting.get(request_id)
            if pending is None or pending.recipient != identity:
                raise ValueError("unrecognized response or wrong replying device")
            if (pending.kind == "pair") != (kind == "pair_reply") and kind != "reject":
                raise ValueError("wrong response protocol")
            if kind == "pair_reply":
                response = {"t": "pair_ack" if payload.get("ok") is True else "pair_error",
                            "id": request_id}
                if payload.get("ok") is not True:
                    response["code"] = "denied"
            elif kind == "reply":
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

    async def handle_join(self, socket: ServerConnection) -> None:
        """Untrusted clients may REQUEST enrollment, never self-approve it."""
        if self.enrollment is None:
            await socket.close(1008, "Enrollment disabled")
            return
        nonce = secrets.token_bytes(32)
        await socket.send(json.dumps({"t": "join_challenge", "n": enc(nonce)}))
        raw = await asyncio.wait_for(socket.recv(), timeout=10)
        data = json.loads(raw)
        if not isinstance(data, dict) or data.get("t") != "join":
            raise ValueError("expected signed enrollment request")
        token = data.get("token")
        if not isinstance(token, str) or not 20 <= len(token) <= 100:
            raise ValueError("invalid invitation")
        der = dec(data.get("key"), 250)
        sig = dec(data.get("sig"), 100)
        pub = serialization.load_der_public_key(der)
        if not isinstance(pub, ec.EllipticCurvePublicKey) or not isinstance(
            pub.curve, ec.SECP256R1
        ):
            raise ValueError("only P-256 identities supported")
        pub.verify(sig, JOIN_CONTEXT + nonce + hashlib.sha256(
            token.encode("utf-8")).digest(), ec.ECDSA(hashes.SHA256()))
        fingerprint = hashlib.sha256(der).hexdigest()
        request_id = self.enrollment.submit(token, fingerprint,
            clean_device_label(data.get("nickname")),
            clean_device_label(data.get("model")))
        await socket.send(json.dumps({"t": "pending", "id": request_id,
                                     "fp": fingerprint}))
        # The owner may take a few minutes to approve on LuCI. Limit polling.
        last_query = 0.0
        while True:
            raw = await asyncio.wait_for(socket.recv(), timeout=300)
            query = json.loads(raw)
            if not isinstance(query, dict) or query.get("t") != "status" or (
                query.get("id") != request_id
            ):
                raise ValueError("invalid enrollment status query")
            if time.monotonic() - last_query < 1:
                raise ValueError("enrollment status polled too frequently")
            last_query = time.monotonic()
            status = self.enrollment.status(request_id, fingerprint)
            await socket.send(json.dumps({"t": status, "id": request_id}))
            if status != "pending":
                return

    async def handler(self, socket: ServerConnection) -> None:
        identity = None
        try:
            if socket.path == "/join":
                if self.join_slots.locked():
                    await socket.close(code=1013, reason="Too many pending joins")
                    return
                async with self.join_slots:
                    await self.handle_join(socket)
                return
            if socket.path != "/relay":
                await socket.close(code=1008, reason="Use /relay")
                return
            identity = await self.authenticate(socket)
            await self.flush_offline(identity)
            async for raw in socket:
                payload = json.loads(raw)
                if not isinstance(payload, dict):
                    raise ValueError("object required")
                await self.receive(identity, payload)
        except RelayNotAuthorized:
            # Specific close code lets the phone distinguish revoked permission
            # from ordinary loss of mobile connectivity, without disclosing names.
            await socket.close(code=4403, reason="not_authorized")
        except (ValueError, TypeError, InvalidSignature, json.JSONDecodeError,
                asyncio.TimeoutError) as exc:
            LOG.warning("Rejected connection/request %s: %s", (identity or "unknown")[:12], exc)
            await socket.close(code=1008, reason="Invalid protocol")
        except ConnectionClosed:
            pass
        finally:
            if identity is not None and self.clients.get(identity) is socket:
                self.clients.pop(identity, None)
                self.request_times.pop(identity, None)
                for req_id, pending in list(self.waiting.items()):
                    if pending.recipient == identity:
                        self.waiting.pop(req_id, None)
                        if pending.queued:
                            self.mark_queued_idle(req_id)
                        else:
                                await self.send(pending.sender, {
                                    "t": "pair_error" if pending.kind == "pair" else "error",
                                    "id": req_id, "code": "offline"})
                    elif pending.sender == identity and not pending.queued:
                        self.waiting.pop(req_id, None)
                LOG.info("Disconnected identity %s", identity[:12])


async def run(host: str, port: int, tls_cert: str | None, tls_key: str | None,
              allowed: set[str], max_clients: int, rate_kbps: int,
              drop_user: str | None,
              enrollment_dir: str | None = None) -> None:
    context = None
    if tls_cert and tls_key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(tls_cert, tls_key)
    elif host not in {"127.0.0.1", "::1", "localhost"}:
        raise ValueError("Plain WS must bind to loopback; use --cert/--key or a local TLS reverse proxy")
    enrollment = EnrollmentStore(enrollment_dir) if enrollment_dir else None
    relay = Relay(allowed, max_clients=max_clients, rate_kbps=rate_kbps,
                  enrollment=enrollment)
    # Read TLS private key while privileged, then drop rights before accepting
    # any network connections. Failure to change UID is fatal.
    if drop_user and os.geteuid() == 0:
        account = pwd.getpwnam(drop_user)
        os.setgroups([])
        os.setgid(account.pw_gid)
        os.setuid(account.pw_uid)
    async with serve(relay.handler, None if host == "both" else host, port, ssl=context,
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
    parser.add_argument("--allow", action="append", default=[],
                        help="Allowed OpenKinGuard device fingerprint (repeatable)")
    parser.add_argument("--max-clients", type=int, default=16)
    parser.add_argument("--rate-kbps", type=int, default=25_000)
    parser.add_argument("--drop-user", default="nobody")
    parser.add_argument("--enrollment-dir", default=None,
                        help="Optional private tmpfs directory for owner-approved QR joining")
    args = parser.parse_args()
    if bool(args.cert) != bool(args.key):
        parser.error("--cert and --key must be specified together")
    if (not args.allow and not args.enrollment_dir) or not all(
        FINGERPRINT.fullmatch(item) for item in args.allow
    ):
        parser.error("at least one valid 64-character lowercase device fingerprint is required")
    if not 1 <= args.max_clients <= 64 or not 64 <= args.rate_kbps <= 25_000:
        parser.error("limits must be max-clients 1..64 and rate-kbps 64..25000")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(run(args.listen, args.port, args.cert, args.key, set(args.allow),
                    args.max_clients, args.rate_kbps, args.drop_user,
                    args.enrollment_dir))


if __name__ == "__main__":
    main()
