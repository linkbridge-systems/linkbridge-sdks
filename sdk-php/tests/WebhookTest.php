<?php

declare(strict_types=1);

namespace Linkbridge\Tests;

use Linkbridge\SignatureError;
use Linkbridge\Webhook;
use PHPUnit\Framework\TestCase;

final class WebhookTest extends TestCase
{
    private const SECRET = 'whsec_test_secret';

    private function header(int $t, string $body, string $secret = self::SECRET): string
    {
        $sig = hash_hmac('sha256', $t . '.' . $body, $secret);
        return 't=' . $t . ',v1=' . $sig;
    }

    public function testHappyPath(): void
    {
        $body = '{"event":"invoice.transmitted","data":{}}';
        $now  = 1_700_000_000;
        Webhook::verify(self::SECRET, $body, $this->header($now, $body), $now);
        $this->expectNotToPerformAssertions();
    }

    public function testToleratesUnknownVersionTokens(): void
    {
        $body = '{"event":"x"}';
        $now  = 1_700_000_000;
        $sig  = hash_hmac('sha256', $now . '.' . $body, self::SECRET);
        $hdr  = 't=' . $now . ',v1=' . $sig . ',v2=ignored';
        Webhook::verify(self::SECRET, $body, $hdr, $now);
        $this->expectNotToPerformAssertions();
    }

    public function testMissingHeaderRejected(): void
    {
        $this->expectException(SignatureError::class);
        Webhook::verify(self::SECRET, 'body', '', 1_700_000_000);
    }

    public function testMalformedTimestampRejected(): void
    {
        $this->expectException(SignatureError::class);
        Webhook::verify(self::SECRET, 'body', 't=abc,v1=deadbeef', 1_700_000_000);
    }

    public function testReplayWindowRejected(): void
    {
        $body = 'body';
        $old  = 1_700_000_000;
        $now  = $old + 600; // 10 min skew, beyond 5 min window
        $this->expectException(SignatureError::class);
        Webhook::verify(self::SECRET, $body, $this->header($old, $body), $now);
    }

    public function testWrongSecretRejected(): void
    {
        $body = 'body';
        $now  = 1_700_000_000;
        $this->expectException(SignatureError::class);
        Webhook::verify('other-secret', $body, $this->header($now, $body), $now);
    }

    public function testEmptySecretRejected(): void
    {
        $this->expectException(SignatureError::class);
        Webhook::verify('', 'body', 't=1,v1=abc', 1);
    }
}
