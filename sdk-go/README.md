# linkbridge-go

Official Go SDK for the [Linkbridge](https://linkbridge.ng) e-invoicing
API. Targets the OpenAPI contract at `tools/openapi/openapi.yaml`.

## Install

```bash
go get github.com/linkbridge-systems/linkbridge-sdks/sdk-go
```

## Quick start

```go
package main

import (
    "context"
    "encoding/json"
    "log"
    "os"

    linkbridge "github.com/linkbridge-systems/linkbridge-sdks/sdk-go"
    "github.com/linkbridge-systems/linkbridge-sdks/sdk-go/oapi"
)

func main() {
    ctx := context.Background()
    client, err := linkbridge.New(ctx, linkbridge.Config{
        BaseURL:      "https://api.linkbridge.ng",
        ClientID:     os.Getenv("LB_CLIENT_ID"),
        ClientSecret: os.Getenv("LB_CLIENT_SECRET"),
    })
    if err != nil { log.Fatal(err) }

    raw, err := os.ReadFile("invoice.json")
    if err != nil { log.Fatal(err) }
    var invoice oapi.Invoice
    if err := json.Unmarshal(raw, &invoice); err != nil { log.Fatal(err) }

    accepted, err := client.Invoices.Submit(ctx, invoice, linkbridge.SubmitOptions{})
    if err != nil { log.Fatal(err) }

    log.Printf("queued: %s -> %s", accepted.Irn, accepted.TrackingUrl)
}
```

## Surface

| Area      | Methods                                                  |
| --------- | -------------------------------------------------------- |
| Invoices  | `Submit`, `Get`, `List`                                  |
| Webhooks  | `Create`, `List`                                         |
| Lookups   | `TaxCodes` (with ETag revalidation)                      |
| Webhook verifier | `VerifyWebhook`, `VerifyHTTPRequest`              |
| Helpers   | `IdempotencyKey()`, `WithIdempotencyKey(key)`            |

For anything not covered by the high-level resources, use `client.Raw()`
to access the full generated client in `./oapi`.

## Authentication

`New` accepts either:

- `ClientID` + `ClientSecret` — performs OAuth2 `client_credentials`,
  caches the token, and refreshes it 60s before expiry.
- `StaticToken` — use a pre-issued OAuth access token and skip the SDK's
  client-credentials exchange.

## Webhook verification

```go
http.HandleFunc("/webhook", func(w http.ResponseWriter, r *http.Request) {
    body, err := linkbridge.VerifyHTTPRequest(secret, r, time.Now())
    if err != nil {
        http.Error(w, err.Error(), http.StatusUnauthorized)
        return
    }
    // body is now trusted; decode and process.
})
```

## Regenerating the low-level client

The `./oapi` package is generated from `tools/openapi/openapi.yaml`.
To regen after a spec change:

```bash
go install github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen@v2.4.1
./tools/scripts/codegen-sdk-go.sh
```

CI fails if regen produces a diff — this guarantees the SDK never
drifts from the spec. See `docs-internal/adr/0017-sdk-codegen-strategy.md`.

## Versioning

The SDK module follows semver. Breaking changes to the high-level
surface require a major-version bump; the low-level `oapi` package may
change shape on any minor when the OpenAPI spec evolves — pin the
module if you depend on it directly.
