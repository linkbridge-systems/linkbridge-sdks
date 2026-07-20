"""HMAC-SHA256 verification for Linkbridge webhook deliveries.

Spec contract (see openapi.yaml §security and ADR-0006):

    X-Linkbridge-Signature: t=<unix>,v1=<hex>
    v1 = hex(HMAC-SHA256(secret, f"{t}.{body}"))

The receiver MUST reject deliveries whose timestamp falls outside a
5-minute window to bound replay-attack horizons. We mirror the same
constant-time comparison logic the API server uses.
"""

from __future__ import annotations

import hmac
import time
from hashlib import sha256
from typing import Optional

from .errors import SignatureError

SIGNATURE_HEADER = "X-Linkbridge-Signature"
MAX_WEBHOOK_SKEW_SECONDS = 5 * 60


def verify_webhook(
    *,
    secret: bytes,
    body: bytes,
    header: str,
    now: Optional[float] = None,
) -> None:
    """Verify a webhook signature. Raises ``SignatureError`` on any
    failure mode (missing/malformed header, replay-window violation,
    signature mismatch). Returns ``None`` on success.

    Parameters
    ----------
    secret:
        The raw webhook secret bytes returned by ``POST /v1/webhooks``.
        Do NOT pass the secret as ``str`` — Python would otherwise
        encode it implicitly with the platform's default encoding.
    body:
        The exact request body bytes as read off the wire. JSON
        re-serialisation will alter byte ordering and break verification.
    header:
        The raw value of the ``X-Linkbridge-Signature`` header.
    now:
        Override the current time (epoch seconds). Production code
        should leave this as ``None`` so we use ``time.time()``.
    """
    if not header:
        raise SignatureError("missing X-Linkbridge-Signature")
    if not isinstance(secret, (bytes, bytearray)):
        raise TypeError("secret must be bytes; pass secret.encode() if it's a str")
    if not isinstance(body, (bytes, bytearray)):
        raise TypeError("body must be bytes; do not pre-decode the request body")

    t, sig = _parse_header(header)
    current = time.time() if now is None else now
    if abs(current - t) > MAX_WEBHOOK_SKEW_SECONDS:
        raise SignatureError("signature timestamp outside replay window")

    expected = hmac.new(secret, f"{t}.".encode("ascii"), sha256)
    expected.update(body)
    expected_hex = expected.hexdigest()
    # hmac.compare_digest is constant-time; explicit lower() avoids
    # accidentally rejecting a server that uppercased the hex.
    if not hmac.compare_digest(expected_hex, sig.lower()):
        raise SignatureError("signature mismatch")


def _parse_header(header: str) -> tuple[int, str]:
    """Parse ``t=<unix>,v1=<hex>`` into ``(timestamp_int, hex_str)``.

    Raises ``SignatureError`` for any structural problem.
    """
    t: Optional[int] = None
    sig: Optional[str] = None
    for part in header.split(","):
        kv = part.strip().split("=", 1)
        if len(kv) != 2:
            raise SignatureError("malformed signature header")
        key, value = kv[0], kv[1]
        if key == "t":
            try:
                t = int(value)
            except ValueError as exc:
                raise SignatureError("malformed signature timestamp") from exc
            if t <= 0:
                raise SignatureError("malformed signature timestamp")
        elif key == "v1":
            sig = value
        # Unknown keys are tolerated for forward-compat (e.g. v2=).
    if t is None or not sig:
        raise SignatureError("malformed signature header")
    return t, sig
