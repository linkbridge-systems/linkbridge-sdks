"""Error types for the Linkbridge Python SDK.

The API always returns a `{"error": {"code", "message", "trace_id"}}`
envelope on failure. We surface those fields verbatim on `APIError` so
callers can switch on the canonical code without parsing the message.
"""

from __future__ import annotations

import json
from typing import Any, Mapping, Optional


class APIError(Exception):
    """Raised for any non-2xx HTTP response from the Linkbridge API."""

    def __init__(
        self,
        *,
        status: int,
        code: str,
        message: str,
        trace_id: Optional[str] = None,
        details: Optional[Any] = None,
        raw: Optional[bytes] = None,
    ) -> None:
        super().__init__(f"linkbridge: {status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message
        self.trace_id = trace_id
        self.details = details
        self.raw = raw

    @classmethod
    def from_response(cls, status: int, body: bytes) -> "APIError":
        """Best-effort decoder. Falls back to opaque codes when the
        response is not the canonical error envelope (e.g. a 502 from a
        load balancer that has no idea what an APIError looks like)."""
        code = "http_error"
        message = body.decode("utf-8", errors="replace")[:500] or f"http {status}"
        trace_id: Optional[str] = None
        details: Any = None
        try:
            decoded = json.loads(body)
        except (ValueError, UnicodeDecodeError):
            decoded = None
        if isinstance(decoded, Mapping):
            err = decoded.get("error")
            if isinstance(err, Mapping):
                code = str(err.get("code") or code)
                message = str(err.get("message") or message)
                trace_id = err.get("trace_id")
                details = err.get("details")
        return cls(
            status=status,
            code=code,
            message=message,
            trace_id=trace_id,
            details=details,
            raw=body,
        )


class SignatureError(Exception):
    """Raised by ``verify_webhook`` when a delivery cannot be trusted."""
