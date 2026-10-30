"""Unit tests for the Linkbridge Python SDK client.

We rely on the Transport injection point to drive every code path
hermetically — no real HTTP, no Postgres. The contract is small enough
(four arguments in, three values out) that tests stay readable.
"""

from __future__ import annotations

import json
import threading
import time
from typing import Any, List, Mapping, Optional, Tuple

import pytest

from linkbridge import APIError, LinkbridgeClient


# --- helpers ---------------------------------------------------------------


class FakeTransport:
    """Records every call and returns canned responses."""

    def __init__(self, responses: List[Tuple[int, Mapping[str, str], bytes]]) -> None:
        self._responses = list(responses)
        self.calls: List[Tuple[str, str, Mapping[str, str], Optional[bytes]]] = []
        self.lock = threading.Lock()

    def __call__(
        self,
        method: str,
        url: str,
        headers: Mapping[str, str],
        body: Optional[bytes],
    ) -> Tuple[int, Mapping[str, str], bytes]:
        with self.lock:
            self.calls.append((method, url, dict(headers), body))
            if not self._responses:
                raise AssertionError(f"FakeTransport: unexpected call {method} {url}")
            return self._responses.pop(0)


def _ok(body: Any, *, status: int = 200) -> Tuple[int, Mapping[str, str], bytes]:
    return status, {"content-type": "application/json"}, json.dumps(body).encode()


def _err(status: int, code: str, message: str) -> Tuple[int, Mapping[str, str], bytes]:
    body = {"error": {"code": code, "message": message, "trace_id": "trace-xyz"}}
    return status, {"content-type": "application/json"}, json.dumps(body).encode()


def _new_client(transport: FakeTransport, **overrides: Any) -> LinkbridgeClient:
    base: dict[str, Any] = {
        "base_url": "https://api.test",
        "client_id": "cid",
        "client_secret": "csecret",
        "transport": transport,
    }
    base.update(overrides)
    return LinkbridgeClient(**base)


# --- construction ----------------------------------------------------------


def test_constructor_requires_credentials():
    with pytest.raises(ValueError):
        LinkbridgeClient(base_url="https://x")


def test_constructor_requires_base_url():
    with pytest.raises(ValueError):
        LinkbridgeClient(base_url="", static_token="t")


def test_static_token_skips_oauth_dance():
    transport = FakeTransport([_ok({"data": [], "next_cursor": None})])
    client = LinkbridgeClient(
        base_url="https://api.test", static_token="static-bearer", transport=transport
    )
    client.invoices.list()
    method, url, headers, _ = transport.calls[0]
    assert method == "GET"
    assert url == "https://api.test/v1/invoices"
    assert headers["authorization"] == "Bearer static-bearer"


# --- token caching ---------------------------------------------------------


def test_token_is_fetched_once_and_cached():
    transport = FakeTransport(
        [
            _ok({"access_token": "tok-1", "expires_in": 3600, "token_type": "Bearer"}),
            _ok({"data": [], "next_cursor": None}),
            _ok({"data": [], "next_cursor": None}),
        ]
    )
    client = _new_client(transport)
    client.invoices.list()
    client.invoices.list()
    # Only one /v1/oauth/token call despite two list() calls.
    token_calls = [c for c in transport.calls if c[1].endswith("/v1/oauth/token")]
    assert len(token_calls) == 1


def test_token_refreshes_when_near_expiry(monkeypatch: pytest.MonkeyPatch):
    transport = FakeTransport(
        [
            _ok({"access_token": "tok-1", "expires_in": 30, "token_type": "Bearer"}),
            _ok({"data": [], "next_cursor": None}),
            _ok({"access_token": "tok-2", "expires_in": 3600, "token_type": "Bearer"}),
            _ok({"data": [], "next_cursor": None}),
        ]
    )
    client = _new_client(transport)
    client.invoices.list()  # first call → fetches tok-1
    # Advance time well past tok-1's window. expires_in=30 means cache
    # window is now-60; we cross instantly.
    base = time.time()
    monkeypatch.setattr("linkbridge.client.time.time", lambda: base + 120)
    client.invoices.list()  # should refresh
    token_calls = [c for c in transport.calls if c[1].endswith("/v1/oauth/token")]
    assert len(token_calls) == 2


# --- error envelopes -------------------------------------------------------


def test_api_error_decodes_canonical_envelope():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _err(404, "invoice_not_found", "no invoice with that IRN"),
        ]
    )
    client = _new_client(transport)
    with pytest.raises(APIError) as excinfo:
        client.invoices.get("INV-MISSING")
    assert excinfo.value.status == 404
    assert excinfo.value.code == "invoice_not_found"
    assert excinfo.value.trace_id == "trace-xyz"


def test_api_error_handles_non_envelope_body():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            (502, {}, b"<html>Bad Gateway</html>"),
        ]
    )
    client = _new_client(transport)
    with pytest.raises(APIError) as excinfo:
        client.invoices.get("INV-X")
    assert excinfo.value.status == 502
    # Falls back to opaque code rather than crashing on the HTML body.
    assert excinfo.value.code == "http_error"


# --- resource calls --------------------------------------------------------


def test_invoices_submit_attaches_idempotency_key_and_body():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _ok(
                {"irn": "INV001", "status": "pending", "tracking_url": "/v1/invoices/INV001"},
                status=202,
            ),
        ]
    )
    client = _new_client(transport)
    out = client.invoices.submit({"irn": "INV001"}, idempotency_key="my-key", mode="async")
    assert out["irn"] == "INV001"

    method, url, headers, body = transport.calls[1]
    assert method == "POST"
    assert url == "https://api.test/v1/invoices?mode=async"
    assert headers["idempotency-key"] == "my-key"
    assert headers["content-type"] == "application/json"
    assert json.loads(body) == {"irn": "INV001"}


def test_invoices_submit_generates_idempotency_key_when_omitted():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _ok({"irn": "INV", "status": "pending", "tracking_url": "/v1/invoices/INV"}, status=202),
        ]
    )
    client = _new_client(transport)
    client.invoices.submit({"irn": "INV"})
    _, _, headers, _ = transport.calls[1]
    assert headers["idempotency-key"].startswith("lb-")
    assert len(headers["idempotency-key"]) == len("lb-") + 32


def test_invoices_transmit_targets_correct_path():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _ok({"irn": "INV-X", "status": "failed", "tracking_url": "/v1/invoices/INV-X"}, status=202),
        ]
    )
    client = _new_client(transport)
    client.invoices.transmit("INV-X")
    _, url, _, body = transport.calls[1]
    assert url == "https://api.test/v1/invoices/INV-X/transmit"
    assert body is None


def test_invoices_update_status_serialises_body():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _ok({"irn": "INV-Y", "status": "transmitted"}),
        ]
    )
    client = _new_client(transport)
    client.invoices.update_status("INV-Y", payment_status="PAID", reference="RCPT-1")
    _, url, _, body = transport.calls[1]
    assert url == "https://api.test/v1/invoices/INV-Y/status"
    assert json.loads(body) == {"payment_status": "PAID", "reference": "RCPT-1"}


def test_invoices_list_drops_empty_query_params():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _ok({"data": [], "next_cursor": None}),
        ]
    )
    client = _new_client(transport)
    client.invoices.list(limit=20)
    _, url, _, _ = transport.calls[1]
    # cursor=None and status=None must not appear in the query string.
    assert url == "https://api.test/v1/invoices?limit=20"


def test_webhooks_create_and_delete():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _ok({"id": "wh-1", "url": "https://hook", "events": ["invoice.accepted"]}),
            (204, {}, b""),
        ]
    )
    client = _new_client(transport)
    created = client.webhooks.create(url="https://hook", events=["invoice.accepted"])
    assert created["id"] == "wh-1"
    assert client.webhooks.delete("wh-1") is None
    assert transport.calls[-1][0] == "DELETE"


def test_user_agent_includes_sdk_version_and_suffix():
    transport = FakeTransport(
        [
            _ok({"access_token": "t", "expires_in": 3600}),
            _ok({"data": [], "next_cursor": None}),
        ]
    )
    client = _new_client(transport, user_agent="myapp/1.0")
    client.invoices.list()
    _, _, headers, _ = transport.calls[1]
    assert headers["user-agent"].startswith("linkbridge-python/")
    assert headers["user-agent"].endswith("myapp/1.0")
