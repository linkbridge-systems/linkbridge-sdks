<?php

declare(strict_types=1);

namespace Linkbridge\Resources;

use Linkbridge\LinkbridgeClient;

final class Lookups
{
    public function __construct(private readonly LinkbridgeClient $client)
    {
    }

    /**
     * @return array<string,mixed>
     */
    public function taxCodes(): array
    {
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('GET', '/v1/lookups/tax-codes');
        return $resp;
    }

    /**
     * @return array<string,mixed>
     */
    public function hsnCodes(): array
    {
        /** @var array<string,mixed> $resp */
        $resp = $this->client->request('GET', '/v1/lookups/hsn-codes');
        return $resp;
    }
}
