"""Linkbridge Python SDK — public surface.

Stable imports for downstream callers; everything else is implementation
detail and may change without a major version bump.
"""

from .client import LinkbridgeClient, SDK_VERSION
from .errors import APIError, SignatureError
from .webhook import (
    MAX_WEBHOOK_SKEW_SECONDS,
    SIGNATURE_HEADER,
    verify_webhook,
)

__all__ = [
    "LinkbridgeClient",
    "APIError",
    "SignatureError",
    "verify_webhook",
    "SIGNATURE_HEADER",
    "MAX_WEBHOOK_SKEW_SECONDS",
    "SDK_VERSION",
]

__version__ = SDK_VERSION
