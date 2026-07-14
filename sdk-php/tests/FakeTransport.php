<?php

declare(strict_types=1);

namespace Linkbridge\Tests;

use Linkbridge\Transport\TransportInterface;

/**
 * In-memory transport for unit tests. Records every call and pops queued
 * responses in FIFO order.
 */
final class FakeTransport implements TransportInterface
{
    /** @var list<array{method:string,url:string,headers:array<string,string>,body:string}> */
    public array $calls = [];

    /** @var list<array{0:int,1:array<string,string>,2:string}> */
    public array $responses = [];

    /**
     * @param array<string,string> $headers
     */
    public function enqueue(int $status, string $body, array $headers = []): void
    {
        $this->responses[] = [$status, $headers, $body];
    }

    public function send(string $method, string $url, array $headers, ?string $body): array
    {
        $this->calls[] = [
            'method'  => $method,
            'url'     => $url,
            'headers' => $headers,
            'body'    => $body ?? '',
        ];
        if ($this->responses === []) {
            throw new \RuntimeException('FakeTransport: no queued response for ' . $method . ' ' . $url);
        }
        return array_shift($this->responses);
    }
}
