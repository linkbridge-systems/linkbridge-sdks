# LinkBridge Laravel SDK

The official Laravel integration for the LinkBridge e-invoicing API. It wraps
the framework-agnostic `linkbridge/sdk-php` client with service-container
bindings, package discovery, a facade, publishable configuration, and webhook
signature middleware.

## Requirements

- PHP 8.1+
- Laravel 10, 11, 12, or 13
- The PHP extensions required by `linkbridge/sdk-php`: curl, json, openssl,
  and hash

## Install

```bash
composer require linkbridge/laravel
php artisan vendor:publish --tag=linkbridge-config
```

Configure the application:

```dotenv
LINKBRIDGE_BASE_URL=https://api.linkbridge.ng
LINKBRIDGE_CLIENT_ID=client_xxx
LINKBRIDGE_CLIENT_SECRET=secret_xxx
LINKBRIDGE_SCOPES=invoices:write,invoices:read
LINKBRIDGE_WEBHOOK_SECRET=whsec_xxx
```

Laravel discovers `LinkbridgeServiceProvider` and the `Linkbridge` facade
automatically.

## Submit an invoice

Use constructor injection in application services:

```php
use Linkbridge\LinkbridgeClient;

final class SubmitInvoice
{
    public function __construct(private LinkbridgeClient $linkbridge)
    {
    }

    public function __invoke(array $invoice): array
    {
        return $this->linkbridge->invoices->submit($invoice);
    }
}
```

Or use the facade:

```php
use Linkbridge\Laravel\Facades\Linkbridge;

$accepted = Linkbridge::invoices()->submit(
    $invoice,
    Linkbridge::idempotencyKey(),
);
```

The client is a container singleton, so its lazy OAuth token cache is reused
throughout a traditional Laravel request or long-running queue worker.

## Verify webhook requests

Apply the middleware to the route that receives LinkBridge deliveries:

```php
use Illuminate\Support\Facades\Route;
use Linkbridge\Laravel\Http\Middleware\VerifyWebhookSignature;

Route::post('/webhooks/linkbridge', HandleLinkbridgeWebhook::class)
    ->middleware(VerifyWebhookSignature::class);
```

The middleware verifies `X-Linkbridge-Signature` against the raw request body
and rejects missing, malformed, expired, or mismatched signatures with HTTP
401. `LINKBRIDGE_WEBHOOK_SECRET` must contain the secret returned once when
the webhook subscription was created.

For secret rotation or multi-tenant routing, verify explicitly after resolving
the correct secret:

```php
Linkbridge::verifyWebhook(
    $tenant->linkbridge_webhook_secret,
    $request->getContent(),
    (string) $request->header('X-Linkbridge-Signature'),
);
```

## Testing

Bind a fake core transport before resolving the client:

```php
use Linkbridge\Transport\TransportInterface;

$this->app->instance(TransportInterface::class, new FakeTransport());
```

Run the package suite with:

```bash
composer install
composer test
```
