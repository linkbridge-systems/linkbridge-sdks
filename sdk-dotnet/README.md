# LinkBridge .NET SDK

The official async .NET client for the LinkBridge e-invoicing API.

- .NET 8 and .NET 10
- zero runtime NuGet dependencies
- OAuth2 client-credentials with a single-flight in-memory token cache
- typed invoice and webhook resources
- injectable `HttpClient`
- canonical API errors
- constant-time webhook HMAC verification

## Install

```bash
dotnet add package Linkbridge.Sdk --version 0.4.0
```

## Create a client

```csharp
using Linkbridge;

using var client = new LinkbridgeClient(new LinkbridgeClientOptions
{
    BaseUrl = "https://api.linkbridge.ng",
    ClientId = Environment.GetEnvironmentVariable("LB_CLIENT_ID"),
    ClientSecret = Environment.GetEnvironmentVariable("LB_CLIENT_SECRET"),
});
```

The constructor performs no network I/O. The first authenticated request
fetches a token; later calls reuse it until the 60-second early-refresh window.

In ASP.NET Core, construct the SDK from an `HttpClient` managed by
`IHttpClientFactory`:

```csharp
builder.Services.AddHttpClient("linkbridge");
builder.Services.AddSingleton(services =>
{
    var factory = services.GetRequiredService<IHttpClientFactory>();
    return new LinkbridgeClient(
        new LinkbridgeClientOptions
        {
            BaseUrl = builder.Configuration["LINKBRIDGE_BASE_URL"],
            ClientId = builder.Configuration["LINKBRIDGE_CLIENT_ID"],
            ClientSecret = builder.Configuration["LINKBRIDGE_CLIENT_SECRET"],
        },
        factory.CreateClient("linkbridge"));
});
```

The SDK never disposes an injected `HttpClient`.

## Submit and retrieve invoices

```csharp
using Linkbridge.Models;

var accepted = await client.Invoices.SubmitAsync(
    invoicePayload,
    new InvoiceSubmitOptions
    {
        IdempotencyKey = LinkbridgeClient.IdempotencyKey(),
        Mode = InvoiceSubmitMode.Async,
    },
    cancellationToken);

var invoice = await client.Invoices.GetAsync(accepted.Irn, cancellationToken);
var page = await client.Invoices.ListAsync(
    new InvoiceListOptions { Status = "transmitted", Limit = 50 },
    cancellationToken);
```

The invoice request is intentionally accepted as any JSON-serializable object.
The canonical schema remains `packages/schema/invoice.schema.json`, and the API
performs authoritative validation.

## Webhook subscriptions

```csharp
var webhook = await client.Webhooks.CreateAsync(
    "https://merchant.example/webhooks/linkbridge",
    WebhookEvents.Subscribable,
    "ERP lifecycle events",
    cancellationToken);

// Store webhook.Secret now. It is never returned again.
```

Verify incoming requests against the exact raw bytes read from the request:

```csharp
WebhookVerifier.Verify(
    webhookSecretBytes,
    rawRequestBodyBytes,
    request.Headers[WebhookVerifier.SignatureHeader].ToString());
```

`WebhookVerificationException.Reason` distinguishes missing, malformed,
expired, and mismatched signatures. The default replay window is five minutes,
and a signature exactly 300 seconds old remains valid.

## Errors and custom endpoints

Non-success responses throw `LinkbridgeApiException`, exposing `Status`,
`Code`, `ApiMessage`, `TraceId`, `Details`, and `RawBody`.

Use the authenticated JSON escape hatch for an endpoint not yet represented by
a resource method:

```csharp
var response = await client.RequestAsync(
    HttpMethod.Get,
    "/v1/custom",
    query: [KeyValuePair.Create<string, string?>("cursor", cursor)],
    cancellationToken: cancellationToken);
```

## Build and test

```bash
dotnet restore Linkbridge.sln
dotnet test Linkbridge.sln --configuration Release
dotnet pack src/Linkbridge/Linkbridge.csproj --configuration Release
```
