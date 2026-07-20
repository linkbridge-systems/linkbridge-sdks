# Linkbridge PHP SDK

Idiomatic PHP client for the Linkbridge SI + APP API.

```php
use Linkbridge\LinkbridgeClient;

$client = new LinkbridgeClient([
    'base_url'      => 'https://api.linkbridge.ng',
    'client_id'     => getenv('LB_CLIENT_ID'),
    'client_secret' => getenv('LB_CLIENT_SECRET'),
    'scopes'        => ['invoices:read', 'invoices:write'],
]);

$invoice = json_decode(file_get_contents(__DIR__.'/invoice.json'), true);

$accepted = $client->invoices->submit($invoice, LinkbridgeClient::idempotencyKey());
echo $accepted['irn'].PHP_EOL;
```

## Design notes

* **Zero runtime Composer dependencies.** Built on the bundled
  `curl`, `json`, `openssl`, and `hash` extensions only. Same
  posture as the Python SDK (ADR-0022) so the package installs
  on any FIRS-issued image without `composer require`.
* **Resource grouping** mirrors the Go / Node / Python SDKs:
  `client->invoices->{submit,get,list,transmit,updateStatus}`,
  `client->webhooks->{create,list,delete}`, `client->lookups->*`.
* **OAuth2 client-credentials** with a lazy in-memory token cache
  refreshed 60 seconds before expiry. Pass a `static_token` to
  bypass the OAuth dance for ops scripts.
* **Pluggable transport**: any class implementing
  `Linkbridge\Transport\TransportInterface` can replace `CurlTransport`,
  which is what the unit tests do (no live HTTP needed).
* **Webhook verifier** in `Linkbridge\Webhook::verify()` mirrors the
  reference implementation in the API server byte-for-byte.

## Requirements

* PHP **8.1+**
* Extensions: `curl`, `json`, `openssl`, `hash` (all in php-core)

## Install

```bash
composer require linkbridge/sdk-php
```

…or just copy `src/` into your project — there are no transitive deps.

## Running the tests

```bash
docker run --rm -v "$PWD:/app" -w /app php:8.3-cli \
  php vendor/bin/phpunit --testdox
```

The included `composer.json` has no runtime requires; `phpunit/phpunit`
is a `require-dev` only and is fetched the first time you run
`composer install`.

## Webhook verification

```php
use Linkbridge\Webhook;
use Linkbridge\SignatureError;

try {
    Webhook::verify(
        secret: $secretBytes,                // raw bytes from POST /v1/webhooks
        body:   file_get_contents('php://input'),
        header: $_SERVER['HTTP_X_LINKBRIDGE_SIGNATURE'] ?? '',
    );
} catch (SignatureError $e) {
    http_response_code(401);
    return;
}
```

`Webhook::verify()` enforces the same 5-minute replay window the
server-side verifier uses and compares the digest in constant time
via `hash_equals`.
