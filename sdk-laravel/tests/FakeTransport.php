<?php

declare(strict_types=1);

namespace Linkbridge\Laravel\Tests;

use Linkbridge\Transport\TransportInterface;

final class FakeTransport implements TransportInterface
{
    /** @var list<array{method:string,url:string,headers:array<string,string>,body:?string}> */
    public array $calls = [];

    /** @var list<array{0:int,1:array<string,string>,2:string}> */
    private array $responses = [];

    public function enqueue(int $status, string $body, array $headers = []): void
    {
        $this->responses[] = [$status, $headers, $body];
    }

    public function send(string $method, string $url, array $headers, ?string $body): array
    {
        $this->calls[] = compact('method', 'url', 'headers', 'body');

        if ($this->responses === []) {
            throw new \RuntimeException('no fake response queued');
        }

        return array_shift($this->responses);
    }
}
