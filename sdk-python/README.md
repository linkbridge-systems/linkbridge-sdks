# linkbridge (Python SDK)

Official Python client for the [Linkbridge](https://linkbridge.ng)
e-invoicing API. Mirrors the surface of the [Go](../sdk-go) and
[Node](../sdk-node) SDKs against the same OpenAPI contract
(`tools/openapi/openapi.yaml`).

* **Zero runtime dependencies** — uses only the Python standard library
  so the package drops cleanly into Lambda layers, vendored POS
  firmware, and air-gapped merchant ERPs.
* **Sync API** with explicit OAuth2 client-credentials handling and
  automatic token refresh.
* **Webhook signature verification** with constant-time comparison and
  the same 5-minute clock-skew window enforced server-side.

## Install

```bash
pip install linkbridge
```

Requires Python 3.9 or newer.

## Quickstart

```python
from linkbridge import LinkbridgeClient

client = LinkbridgeClient(
    base_url="https://api.linkbridge.ng",
    client_id="your-client-id",
    client_secret="your-client-secret",
    scopes=["invoices:write", "invoices:read"],
)

accepted = client.invoices.submit({
    "irn": "INV001-SVC01-20260601",
    "invoice_kind": "B2B",
    # …rest of the canonical NRS payload — see
    # packages/schema/invoice.schema.json
})
print(accepted["irn"], accepted["status"])

# Read back, paginate, retry, mutate payment status:
record   = client.invoices.get(accepted["irn"])
page     = client.invoices.list(limit=20, status="failed")
requeued = client.invoices.transmit(accepted["irn"])
paid     = client.invoices.update_status(accepted["irn"],
              payment_status="PAID", reference="RCPT-001")
```

## Webhook verification

```python
from linkbridge import verify_webhook, SignatureError

@app.post("/hooks/linkbridge")
def hook(request):
    try:
        verify_webhook(
            secret=os.environ["WEBHOOK_SECRET"].encode(),
            body=request.body,                     # raw bytes off the wire
            header=request.headers["X-Linkbridge-Signature"],
        )
    except SignatureError:
        return 401
    # …handle the event
```

## Errors

All non-2xx responses raise `linkbridge.APIError`, which exposes the
HTTP status, the canonical `error.code`, the human `error.message`, and
the `trace_id` so that operators can correlate against server logs.

## Versioning

The package follows the same `0.MINOR.PATCH` cadence as the API surface
during the beta. Breaking changes will be confined to MINOR bumps until
the API freezes at `1.0.0`.
