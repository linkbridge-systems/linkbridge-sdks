"""Tests for the webhook signature verifier.

The vector below is reproduced byte-for-byte against the Go SDK's
``VerifyWebhook`` (see ``packages/sdk-go/webhook_test.go``) so the two
implementations stay in lockstep.
"""

from __future__ import annotations

import hmac
import time
from hashlib import sha256

import pytest

from linkbridge import (
    MAX_WEBHOOK_SKEW_SECONDS,
    SignatureError,
    verify_webhook,
)


SECRET = b"shhh-it-is-a-secret"
BODY = b'{"event":"invoice.accepted","data":{"irn":"INV-1"}}'
NOW = 1_700_000_000  # fixed reference timestamp


def _sign(secret: bytes, body: bytes, t: int) -> str:
    mac = hmac.new(secret, f"{t}.".encode("ascii"), sha256)
    mac.update(body)
    return f"t={t},v1={mac.hexdigest()}"


def test_accepts_well_formed_signature():
    header = _sign(SECRET, BODY, NOW)
    verify_webhook(secret=SECRET, body=BODY, header=header, now=NOW)


def test_rejects_missing_header():
    with pytest.raises(SignatureError, match="missing"):
        verify_webhook(secret=SECRET, body=BODY, header="", now=NOW)


def test_rejects_malformed_header():
    with pytest.raises(SignatureError, match="malformed"):
        verify_webhook(secret=SECRET, body=BODY, header="garbage", now=NOW)


def test_rejects_negative_timestamp():
    with pytest.raises(SignatureError, match="malformed"):
        verify_webhook(secret=SECRET, body=BODY, header="t=-1,v1=abc", now=NOW)


def test_rejects_replay_outside_window():
    header = _sign(SECRET, BODY, NOW)
    skewed = NOW + MAX_WEBHOOK_SKEW_SECONDS + 1
    with pytest.raises(SignatureError, match="replay"):
        verify_webhook(secret=SECRET, body=BODY, header=header, now=skewed)


def test_rejects_signature_mismatch():
    header = _sign(b"different-secret", BODY, NOW)
    with pytest.raises(SignatureError, match="mismatch"):
        verify_webhook(secret=SECRET, body=BODY, header=header, now=NOW)


def test_tolerates_unknown_keys_for_forward_compat():
    # The verifier must ignore unknown k=v pairs (e.g. v2= once we add
    # an algorithm bump) so that future signers stay backward-compatible
    # with deployed receivers.
    header = _sign(SECRET, BODY, NOW) + ",v2=ignored"
    verify_webhook(secret=SECRET, body=BODY, header=header, now=NOW)


def test_uppercased_hex_is_accepted():
    header = _sign(SECRET, BODY, NOW)
    parts = dict(p.split("=", 1) for p in header.split(","))
    upper_header = f"t={parts['t']},v1={parts['v1'].upper()}"
    verify_webhook(secret=SECRET, body=BODY, header=upper_header, now=NOW)


def test_secret_must_be_bytes():
    header = _sign(SECRET, BODY, NOW)
    with pytest.raises(TypeError):
        verify_webhook(secret="str-secret", body=BODY, header=header, now=NOW)  # type: ignore[arg-type]


def test_body_must_be_bytes():
    header = _sign(SECRET, BODY, NOW)
    with pytest.raises(TypeError):
        verify_webhook(secret=SECRET, body="str-body", header=header, now=NOW)  # type: ignore[arg-type]


def test_default_now_uses_wall_clock(monkeypatch):
    """When ``now`` is omitted, the verifier must call ``time.time()``."""
    monkeypatch.setattr(time, "time", lambda: float(NOW))
    header = _sign(SECRET, BODY, NOW)
    verify_webhook(secret=SECRET, body=BODY, header=header)
