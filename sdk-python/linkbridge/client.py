"""LinkbridgeClient — the SDK entry point.

Design notes
------------

* Stdlib-only HTTP via ``urllib.request``. We deliberately avoid the
  ``requests``/``httpx`` dependency so the package installs cleanly
  into AWS Lambda layers and air-gapped POS firmware.
* OAuth2 client-credentials flow with lazy, thread-safe token refresh.
  Tokens are refreshed 60 seconds before expiry; concurrent callers
  block on a single in-flight refresh via a threading.Lock.
* Mirrors the resource grouping of the Node and Go SDKs:
  ``client.invoices.{submit, get, list, transmit, update_status}``,
  ``client.webhooks.{create, list, delete}``, ``client.lookups.*``.
* The ``transport`` constructor argument lets tests inject a fake
  request handler (see ``tests/test_client.py``).
"""

from __future__ import annotations

import json
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, Dict, Iterable, Mapping, Optional, Tuple

from .errors import APIError

SDK_VERSION = "0.4.0"

# A Transport is a pluggable HTTP function. Returning the tuple
# (status, headers, body_bytes) keeps the interface trivially mockable.
Transport = Callable[[str, str, Mapping[str, str], Optional[bytes]], Tuple[int, Mapping[str, str], bytes]]


def _stdlib_transport(
    method: str,
    url: str,
    headers: Mapping[str, str],
    body: Optional[bytes],
) -> Tuple[int, Mapping[str, str], bytes]:
    """Default transport built on urllib. Captures error responses so
    callers (and ``APIError.from_response``) can decode the body even
    on 4xx/5xx — urllib normally treats those as exceptions."""
    req = urllib.request.Request(url, data=body, method=method, headers=dict(headers))
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310 — trusted scheme
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers or {}), exc.read()


class LinkbridgeClient:
    """Synchronous Linkbridge API client."""

    def __init__(
        self,
        *,
        base_url: str,
        client_id: Optional[str] = None,
        client_secret: Optional[str] = None,
        static_token: Optional[str] = None,
        scopes: Optional[Iterable[str]] = None,
        user_agent: Optional[str] = None,
        transport: Optional[Transport] = None,
    ) -> None:
        if not base_url:
            raise ValueError("linkbridge: base_url is required")
        if not static_token and not (client_id and client_secret):
            raise ValueError(
                "linkbridge: either static_token or client_id+client_secret is required"
            )

        self._base_url = base_url.rstrip("/")
        self._client_id = client_id
        self._client_secret = client_secret
        self._static_token = static_token
        self._scopes = list(scopes) if scopes else ["invoices:write", "invoices:read"]
        self._user_agent_suffix = user_agent or ""
        self._transport: Transport = transport or _stdlib_transport

        self._token_cache: Optional[Tuple[str, float]] = None  # (token, expires_at_epoch)
        self._token_lock = threading.Lock()

        self.invoices = InvoicesAPI(self)
        self.webhooks = WebhooksAPI(self)
        self.lookups = LookupsAPI(self)

    # ----- public helpers -------------------------------------------------

    @staticmethod
    def idempotency_key() -> str:
        """Return a fresh URL-safe Idempotency-Key (32 hex chars + prefix)."""
        return "lb-" + secrets.token_hex(16)

    def user_agent(self) -> str:
        base = f"linkbridge-python/{SDK_VERSION}"
        return f"{base} {self._user_agent_suffix}".rstrip()

    # ----- token management ----------------------------------------------

    def _token(self) -> str:
        if self._static_token:
            return self._static_token
        with self._token_lock:
            if self._token_cache and self._token_cache[1] - time.time() > 60:
                return self._token_cache[0]
            self._refresh_token_locked()
            assert self._token_cache is not None
            return self._token_cache[0]

    def _refresh_token_locked(self) -> None:
        body = json.dumps(
            {
                "client_id": self._client_id,
                "client_secret": self._client_secret,
                "grant_type": "client_credentials",
                "scope": " ".join(self._scopes),
            }
        ).encode("utf-8")
        status, _headers, raw = self._transport(
            "POST",
            f"{self._base_url}/v1/oauth/token",
            {
                "content-type": "application/json",
                "accept": "application/json",
                "user-agent": self.user_agent(),
            },
            body,
        )
        if status // 100 != 2:
            raise APIError.from_response(status, raw)
        try:
            decoded = json.loads(raw)
        except ValueError as exc:
            raise APIError(
                status=status,
                code="invalid_token_response",
                message="non-JSON token response",
                raw=raw,
            ) from exc
        token = decoded.get("access_token")
        ttl = int(decoded.get("expires_in") or 300)
        if not token:
            raise APIError(
                status=status,
                code="invalid_token_response",
                message="empty access_token",
                raw=raw,
            )
        self._token_cache = (token, time.time() + ttl)

    # ----- request plumbing ----------------------------------------------

    def request(
        self,
        method: str,
        path: str,
        *,
        query: Optional[Mapping[str, Any]] = None,
        body: Any = None,
        extra_headers: Optional[Mapping[str, str]] = None,
        expect_json: bool = True,
    ) -> Any:
        """Authenticated request. Returns the decoded JSON body on
        2xx (or ``None`` for 204 / empty bodies). Raises :class:`APIError`
        otherwise."""
        url = self._base_url + path
        if query:
            cleaned = {k: str(v) for k, v in query.items() if v is not None and v != ""}
            if cleaned:
                url = f"{url}?{urllib.parse.urlencode(cleaned)}"

        headers: Dict[str, str] = {
            "authorization": "Bearer " + self._token(),
            "accept": "application/json",
            "user-agent": self.user_agent(),
        }
        raw_body: Optional[bytes] = None
        if body is not None:
            headers["content-type"] = "application/json"
            raw_body = json.dumps(body).encode("utf-8")
        if extra_headers:
            for k, v in extra_headers.items():
                headers[k.lower()] = v

        status, _resp_headers, payload = self._transport(method, url, headers, raw_body)
        if status // 100 != 2:
            raise APIError.from_response(status, payload)
        if not expect_json or status == 204 or not payload:
            return None
        try:
            return json.loads(payload)
        except ValueError as exc:
            raise APIError(
                status=status,
                code="invalid_json_response",
                message="server returned non-JSON body",
                raw=payload,
            ) from exc


# ---------------------------------------------------------------------------
# Resource handles. Each one is a thin facade over LinkbridgeClient.request
# so callers get IDE-friendly grouping (`client.invoices.submit(...)`).
# ---------------------------------------------------------------------------


class InvoicesAPI:
    def __init__(self, client: LinkbridgeClient) -> None:
        self._c = client

    def submit(
        self,
        invoice: Mapping[str, Any],
        *,
        idempotency_key: Optional[str] = None,
        mode: Optional[str] = None,
    ) -> Dict[str, Any]:
        """POST /v1/invoices. Returns the async acceptance or sync/dry-run result."""
        headers = {
            "idempotency-key": idempotency_key or LinkbridgeClient.idempotency_key(),
        }
        return self._c.request(
            "POST",
            "/v1/invoices",
            query={"mode": mode} if mode else None,
            body=invoice,
            extra_headers=headers,
        )

    def get(self, irn: str) -> Dict[str, Any]:
        """GET /v1/invoices/{irn}."""
        return self._c.request("GET", f"/v1/invoices/{urllib.parse.quote(irn, safe='')}")

    def list(
        self,
        *,
        cursor: Optional[str] = None,
        limit: Optional[int] = None,
        status: Optional[str] = None,
    ) -> Dict[str, Any]:
        """GET /v1/invoices. Returns ``{"data": [...], "next_cursor": ...}``."""
        return self._c.request(
            "GET",
            "/v1/invoices",
            query={"cursor": cursor, "limit": limit, "status": status},
        )

    def transmit(self, irn: str) -> Dict[str, Any]:
        """POST /v1/invoices/{irn}/transmit — re-queue for transmission."""
        return self._c.request(
            "POST", f"/v1/invoices/{urllib.parse.quote(irn, safe='')}/transmit"
        )

    def update_status(
        self,
        irn: str,
        *,
        payment_status: str,
        reference: Optional[str] = None,
    ) -> Dict[str, Any]:
        """POST /v1/invoices/{irn}/status — record a payment-state change."""
        body: Dict[str, Any] = {"payment_status": payment_status}
        if reference is not None:
            body["reference"] = reference
        return self._c.request(
            "POST",
            f"/v1/invoices/{urllib.parse.quote(irn, safe='')}/status",
            body=body,
        )


class WebhooksAPI:
    def __init__(self, client: LinkbridgeClient) -> None:
        self._c = client

    def create(
        self,
        *,
        url: str,
        events: Iterable[str],
        description: Optional[str] = None,
    ) -> Dict[str, Any]:
        """POST /v1/webhooks. The plaintext ``secret`` is returned on
        the response and never again — store it securely."""
        body: Dict[str, Any] = {"url": url, "events": list(events)}
        if description is not None:
            body["description"] = description
        return self._c.request("POST", "/v1/webhooks", body=body)

    def list(self) -> Dict[str, Any]:
        """GET /v1/webhooks."""
        return self._c.request("GET", "/v1/webhooks")

    def delete(self, webhook_id: str) -> None:
        """DELETE /v1/webhooks/{id}."""
        self._c.request(
            "DELETE",
            f"/v1/webhooks/{urllib.parse.quote(webhook_id, safe='')}",
            expect_json=False,
        )


class LookupsAPI:
    def __init__(self, client: LinkbridgeClient) -> None:
        self._c = client

    def tax_codes(self) -> Dict[str, Any]:
        """GET /v1/lookups/tax-codes."""
        return self._c.request("GET", "/v1/lookups/tax-codes")

    def hsn_codes(self, *, limit: Optional[int] = None, cursor: Optional[str] = None) -> Dict[str, Any]:
        """GET /v1/lookups/hsn-codes (paginated)."""
        return self._c.request(
            "GET",
            "/v1/lookups/hsn-codes",
            query={"limit": limit, "cursor": cursor},
        )
