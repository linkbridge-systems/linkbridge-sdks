<?php

declare(strict_types=1);

namespace Linkbridge\Resources;

use Linkbridge\LinkbridgeClient;

final class Invoices
{
    public function __construct(private readonly LinkbridgeClient $client)
    {
    }

    /**
     * @param array<string,mixed> $invoice
     * @return array<string,mixed>
     */
    public function submit(array $invoice, ?string $idempotencyKey = null): array
    {
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('POST', '/v1/invoices', [
            'json'            => $invoice,
            'idempotency_key' => $idempotencyKey,
        ]);
        return $resp;
    }

    /**
     * @return array<string,mixed>
     */
    public function get(string $irn): array
    {
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('GET', '/v1/invoices/' . rawurlencode($irn));
        return $resp;
    }

    /**
     * @param array{
     *   status?: string,
     *   cursor?: string,
     *   limit?: int,
     * } $filters
     * @return array<string,mixed>
     */
    public function list(array $filters = []): array
    {
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('GET', '/v1/invoices', [
            'query' => $filters,
        ]);
        return $resp;
    }

    /**
     * @return array<string,mixed>
     */
    public function transmit(string $irn, ?string $idempotencyKey = null): array
    {
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('POST', '/v1/invoices/' . rawurlencode($irn) . '/transmit', [
            'json'            => new \stdClass(),
            'idempotency_key' => $idempotencyKey,
        ]);
        return $resp;
    }

    /**
     * @return array<string,mixed>
     */
    public function updateStatus(string $irn, string $status, ?string $reference = null, ?string $idempotencyKey = null): array
    {
        $body = ['payment_status' => $status];
        if ($reference !== null) {
            $body['reference'] = $reference;
        }
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('POST', '/v1/invoices/' . rawurlencode($irn) . '/status', [
            'json'            => $body,
            'idempotency_key' => $idempotencyKey,
        ]);
        return $resp;
    }
}
