<?php

declare(strict_types=1);

namespace Linkbridge\Transport;

/**
 * Pluggable HTTP transport. Returning a tuple-shaped array keeps the
 * interface trivially mockable from PHPUnit without a guzzle-style
 * fluent client. Implementations MUST capture 4xx/5xx response bodies
 * (i.e. NOT throw on non-2xx) so the SDK can decode the canonical
 * error envelope.
 *
 * @phpstan-type TransportResponse array{0:int,1:array<string,string>,2:string}
 */
interface TransportInterface
{
    /**
     * @param array<string,string> $headers
     * @return array{0:int,1:array<string,string>,2:string} status, headers, body
     */
    public function send(string $method, string $url, array $headers, ?string $body): array;
}
