# linkbridge-sdk (Java SDK)

Official Java client for the [Linkbridge](https://linkbridge.ng)
e-invoicing API. Mirrors the surface of the [Go](../sdk-go),
[Node](../sdk-node), [Python](../sdk-python) and [PHP](../sdk-php) SDKs
against the same OpenAPI contract (`tools/openapi/openapi.yaml`).

* **Single runtime dependency** — `jackson-databind` (Java has no
  stdlib JSON; Jackson is already on the classpath of the enterprise
  stacks our integrators run, so in practice it adds nothing new).
* **Sync API** with explicit OAuth2 client-credentials handling and
  automatic token refresh (single-flight under concurrency).
* **Webhook signature verification** with constant-time comparison and
  the same 5-minute clock-skew window enforced server-side.

## Install

```xml
<dependency>
  <groupId>ng.linkbridge</groupId>
  <artifactId>linkbridge-sdk</artifactId>
  <version>0.4.0</version>
</dependency>
```

> **Note:** Maven Central publishing is pending. Until the coordinates
> resolve on Central, consume the artifact from the
> [linkbridge-sdks](https://github.com/linkbridge-systems/linkbridge-sdks)
> mirror.

Requires Java >= 17 (any distribution).

## Quickstart

```java
import ng.linkbridge.sdk.LinkbridgeClient;
import ng.linkbridge.sdk.model.InvoiceSubmission;
import ng.linkbridge.sdk.model.InvoicePage;
import ng.linkbridge.sdk.model.InvoiceRecord;
```

```jsonc
{
  "irn": "INV001-SVC01-20260601",
  "invoice_kind": "B2B"
  // …rest of the canonical NRS payload — see packages/schema/invoice.schema.json
}
```

```java
LinkbridgeClient client = LinkbridgeClient.builder()
    .baseUrl("https://api.linkbridge.ng")
    .clientId(System.getenv("LB_CLIENT_ID"))
    .clientSecret(System.getenv("LB_CLIENT_SECRET"))
    .build();

InvoiceSubmission accepted = client.invoices().submit(invoice); // preserves async and sync result fields
System.out.println("accepted: " + accepted.irn() + " -> " + accepted.trackingUrl());

// Read back, paginate, retry, mutate payment status:
InvoiceRecord record   = client.invoices().get(accepted.irn());
InvoicePage  page      = client.invoices().list(null, 20, "failed");
client.invoices().transmit(accepted.irn());
InvoiceRecord paid     = client.invoices().updateStatus(accepted.irn(), "PAID", "RCPT-001");
```

An `Idempotency-Key` is auto-generated per submit; pass
`InvoicesApi.SubmitOptions` to supply your own
(`LinkbridgeClient.idempotencyKey()` mints one) or to pick a processing
mode (`InvoicesApi.SubmitMode.{ASYNC,SYNC,DRY_RUN}`). For endpoints the
typed resource groups don't wrap, use the generic
`client.request(method, path, query, body, extraHeaders)` escape hatch.

## Webhook verification

```java
import ng.linkbridge.sdk.Webhooks;
import ng.linkbridge.sdk.WebhookVerificationException;

try {
  Webhooks.verify(secret, rawBodyBytes, request.getHeader(Webhooks.SIGNATURE_HEADER));
} catch (WebhookVerificationException e) {
  // e.reason(): MISSING | MALFORMED | EXPIRED | MISMATCH
}
```

`secret` and the body are `byte[]`. Verify against the **raw request
body bytes exactly as read off the wire** — never re-serialized JSON:
any re-encoding (key reordering, whitespace, Unicode escaping) changes
the bytes and the signature will not match. The signature arrives in
the `X-Linkbridge-Signature` header (`Webhooks.SIGNATURE_HEADER`).

## Configuration

All configuration happens on `LinkbridgeClient.builder()`:

| Builder method | Required | Notes |
|---|---|---|
| `baseUrl(String)` | yes | e.g. `https://api.linkbridge.ng` — no default on purpose. |
| `clientId(String)` + `clientSecret(String)` | one of | OAuth2 client-credentials pair; token cached and refreshed automatically. |
| `staticToken(String)` | one of | Pre-issued bearer token; bypasses `/v1/oauth/token` entirely. |
| `scopes(List<String>)` | no | Defaults to `invoices:write invoices:read`. |
| `userAgent(String)` | no | Suffix appended to the SDK identifier `linkbridge-java/<version>`. |
| `transport(Transport)` | no | Inject a custom transport (hermetic tests, proxies). |

## Errors

All non-2xx responses throw `LinkbridgeApiException`, which exposes the
HTTP `status()`, the canonical `code()`, the human `apiMessage()`, the
loosely-typed `details()`, the raw body via `raw()`, and the
`traceId()` so that operators can correlate against server logs.

## Webhook event constants

`WebhookEvents.WEBHOOK_EVENT_TYPES` lists every event type the API
accepts in a webhook `events` array, and
`WebhookEvents.SUBSCRIBABLE_WEBHOOK_EVENTS` the subset worth offering
in a subscription picker (`webhook.test` is excluded — it is delivered
via `POST /v1/webhooks/{id}/test`, bypassing the subscription filter).

## Requirements

* Java >= 17 (any distribution)
* `com.fasterxml.jackson.core:jackson-databind` (the only runtime dependency)

## End-to-end tests

An opt-in E2E suite runs against a real Linkbridge stack (e.g.
`make dev-up`) and is skipped unless `LB_E2E=1`:

```bash
LB_E2E=1 LB_BASE_URL=http://localhost:18080 \
  LB_CLIENT_ID=... LB_CLIENT_SECRET=... \
  LB_SAMPLE_INVOICE=../../apps/api/testdata/sample-invoice.json \
  mvn test -Dtest=E2eLiveStackTest
```

## License

Apache-2.0 — see [LICENSE](./LICENSE).
