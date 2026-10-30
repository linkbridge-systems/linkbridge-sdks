# @linkbridge/sdk

Official Node.js / TypeScript SDK for the [Linkbridge](https://linkbridge.ng)
e-invoicing API. Targets the OpenAPI contract at
`tools/openapi/openapi.yaml`.

Requires Node ≥ 20.

## Install

```bash
npm install @linkbridge/sdk
```

## Quick start

```ts
import { LinkbridgeClient } from "@linkbridge/sdk";

const client = new LinkbridgeClient({
  baseURL: "https://api.linkbridge.ng",
  clientId: process.env.LB_CLIENT_ID!,
  clientSecret: process.env.LB_CLIENT_SECRET!,
});

const accepted = await client.invoices.submit({
  // canonical NRS invoice — see packages/schema/invoice.schema.json
  irn: "INV2024-SVC1-20240101",
  // ...
});
console.log("queued:", accepted.irn, "→", accepted.tracking_url);
```

## Surface

| Area      | Methods                                          |
| --------- | ------------------------------------------------ |
| Invoices  | `client.invoices.submit / get / list`            |
| Webhooks  | `client.webhooks.create / list`                  |
| Helpers   | `LinkbridgeClient.idempotencyKey()`              |
| Webhook verifier | `verifyWebhook(secret, body, header)`     |
| Errors    | `LinkbridgeAPIError` (status, code, traceId, …)  |

The exhaustive generated type surface is available under
`@linkbridge/sdk/oapi` for advanced use cases. The
hand-curated types re-exported from the package root are the
supported, semver-stable surface.

## Authentication

Pass either:

- `clientId` + `clientSecret` — the SDK performs OAuth2
  `client_credentials`, caches the token, and refreshes it 60s before
  expiry.
- `staticToken` — use a pre-issued OAuth access token and skip the SDK's
  client-credentials exchange.

## Webhook verification

```ts
import { verifyWebhook, SIGNATURE_HEADER, WebhookVerificationError } from "@linkbridge/sdk";
import express from "express";

const app = express();
app.use(express.raw({ type: "application/json" })); // keep the raw body!

app.post("/webhook", (req, res) => {
  try {
    verifyWebhook(process.env.WEBHOOK_SECRET!, req.body, req.header(SIGNATURE_HEADER));
  } catch (err) {
    if (err instanceof WebhookVerificationError) return res.status(401).send(err.message);
    throw err;
  }
  // body is now trusted — JSON.parse and dispatch.
  res.sendStatus(204);
});
```

The verifier rejects requests outside a 5-minute clock skew window per
spec §8.5.

## Regenerating from the OpenAPI spec

```bash
./tools/scripts/codegen-sdk-node.sh
```

CI fails if regen produces a diff under `src/oapi/` — this guarantees
the SDK never drifts from the spec. See
`docs-internal/adr/0017-sdk-codegen-strategy.md`.

## Versioning

Semver. Breaking changes to the package-root surface require a major
bump; the generated `oapi/schema.gen.ts` may evolve on any minor when
the OpenAPI spec changes — pin the package if you depend on it
directly.
