<?php

declare(strict_types=1);

namespace Linkbridge\Resources;

use Linkbridge\LinkbridgeClient;

final class Webhooks
{
    public function __construct(private readonly LinkbridgeClient $client)
    {
    }

    /**
     * @param list<string> $events
     * @return array<string,mixed>
     */
    public function create(string $url, array $events, ?string $secret = null, ?string $idempotencyKey = null): array
    {
        $body = ['url' => $url, 'events' => $events];
        if ($secret !== null) {
            $body['secret'] = $secret;
        }
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('POST', '/v1/webhooks', [
            'json'            => $body,
            'idempotency_key' => $idempotencyKey,
        ]);
        return $resp;
    }

    /**
     * @return array<string,mixed>
     */
    public function list(): array
    {
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('GET', '/v1/webhooks');
        return $resp;
    }

    public function delete(string $id): void
    {
        $this->client->request('DELETE', '/v1/webhooks/' . rawurlencode($id));
    }
}
