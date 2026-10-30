<?php

declare(strict_types=1);

namespace Linkbridge\Laravel;

use Linkbridge\LinkbridgeClient;
use Linkbridge\Resources\Invoices;
use Linkbridge\Resources\Lookups;
use Linkbridge\Resources\Webhooks;
use Linkbridge\Webhook;

/**
 * Laravel-friendly facade target around the framework-agnostic PHP SDK.
 */
final class LinkbridgeManager
{
    public function __construct(private readonly LinkbridgeClient $client)
    {
    }

    public function client(): LinkbridgeClient
    {
        return $this->client;
    }

    public function invoices(): Invoices
    {
        return $this->client->invoices;
    }

    public function webhooks(): Webhooks
    {
        return $this->client->webhooks;
    }

    public function lookups(): Lookups
    {
        return $this->client->lookups;
    }

    public function idempotencyKey(): string
    {
        return LinkbridgeClient::idempotencyKey();
    }

    /**
     * Verify a delivery against its raw request body.
     *
     * @throws \Linkbridge\SignatureError
     */
    public function verifyWebhook(
        string $secret,
        string $body,
        string $signatureHeader,
        ?int $now = null,
    ): void {
        Webhook::verify($secret, $body, $signatureHeader, $now);
    }
}
