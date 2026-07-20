<?php

declare(strict_types=1);

namespace Linkbridge\Tests;

use Linkbridge\ApiError;
use Linkbridge\LinkbridgeClient;
use PHPUnit\Framework\TestCase;

final class ClientTest extends TestCase
{
    private function newClient(FakeTransport $transport): LinkbridgeClient
    {
        return new LinkbridgeClient([
            'base_url'      => 'https://api.example.test',
            'client_id'     => 'cid',
            'client_secret' => 'csecret',
            'transport'     => $transport,
        ]);
    }

    public function testRejectsConstructionWithoutCredentials(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        new LinkbridgeClient(['base_url' => 'https://api.example.test']);
    }

    public function testStaticTokenSkipsOauthRoundTrip(): void
    {
        $t = new FakeTransport();
        $t->enqueue(200, json_encode(['data' => [], 'next_cursor' => null]));

        $client = new LinkbridgeClient([
            'base_url'     => 'https://api.example.test',
            'static_token' => 'static-bearer',
            'transport'    => $t,
        ]);
        $client->invoices->list();

        $this->assertCount(1, $t->calls);
        $this->assertSame('https://api.example.test/v1/invoices', $t->calls[0]['url']);
        $this->assertSame('Bearer static-bearer', $t->calls[0]['headers']['Authorization']);
    }

    public function testTokenIsCachedAcrossCalls(): void
    {
        $t = new FakeTransport();
        // First call: oauth token, then GET /v1/invoices, then GET again.
        $t->enqueue(200, json_encode(['access_token' => 'tok-1', 'expires_in' => 3600, 'token_type' => 'Bearer']));
        $t->enqueue(200, json_encode(['data' => [], 'next_cursor' => null]));
        $t->enqueue(200, json_encode(['data' => [], 'next_cursor' => null]));

        $client = $this->newClient($t);
        $client->invoices->list();
        $client->invoices->list();

        $this->assertCount(3, $t->calls, 'token call should be cached for the second list');
        $this->assertSame('POST', $t->calls[0]['method']);
        $this->assertStringEndsWith('/v1/oauth/token', $t->calls[0]['url']);
        $this->assertSame('Bearer tok-1', $t->calls[1]['headers']['Authorization']);
        $this->assertSame('Bearer tok-1', $t->calls[2]['headers']['Authorization']);
    }

    public function testSubmitInjectsIdempotencyKeyAndJsonHeaders(): void
    {
        $t = new FakeTransport();
        $t->enqueue(200, json_encode(['access_token' => 'tok', 'expires_in' => 3600]));
        $t->enqueue(202, json_encode(['irn' => 'IRN-1', 'status' => 'accepted']));

        $client = $this->newClient($t);
        $resp = $client->invoices->submit(['supplier' => ['tin' => '12345678-0001']]);

        $this->assertSame('IRN-1', $resp['irn']);
        $submit = $t->calls[1];
        $this->assertSame('POST', $submit['method']);
        $this->assertSame('https://api.example.test/v1/invoices', $submit['url']);
        $this->assertSame('application/json', $submit['headers']['Content-Type']);
        $this->assertArrayHasKey('Idempotency-Key', $submit['headers']);
        $this->assertMatchesRegularExpression('/^lb-[0-9a-f]{32}$/', $submit['headers']['Idempotency-Key']);
        $this->assertSame(['supplier' => ['tin' => '12345678-0001']], json_decode($submit['body'], true));
    }

    public function testUpdateStatusSendsSpecFieldNames(): void
    {
        $t = new FakeTransport();
        $t->enqueue(200, json_encode(['access_token' => 'tok', 'expires_in' => 3600]));
        $t->enqueue(200, json_encode(['irn' => 'IRN-1', 'payment_status' => 'PAID']));
        $t->enqueue(200, json_encode(['irn' => 'IRN-1', 'payment_status' => 'UNPAID']));

        $client = $this->newClient($t);
        $client->invoices->updateStatus('IRN-1', 'PAID', 'RCPT-1');

        $update = $t->calls[1];
        $this->assertSame('POST', $update['method']);
        $this->assertSame('https://api.example.test/v1/invoices/IRN-1/status', $update['url']);
        $this->assertSame(
            ['payment_status' => 'PAID', 'reference' => 'RCPT-1'],
            json_decode($update['body'], true),
        );

        $client->invoices->updateStatus('IRN-1', 'UNPAID');
        $this->assertSame(
            ['payment_status' => 'UNPAID'],
            json_decode($t->calls[2]['body'], true),
            'reference key must be omitted entirely when not provided',
        );
    }

    public function testQueryStringIsAssembledFromFilters(): void
    {
        $t = new FakeTransport();
        $t->enqueue(200, json_encode(['access_token' => 'tok', 'expires_in' => 3600]));
        $t->enqueue(200, json_encode(['data' => []]));

        $client = $this->newClient($t);
        $client->invoices->list(['status' => 'transmitted', 'limit' => 25]);

        $this->assertSame(
            'https://api.example.test/v1/invoices?status=transmitted&limit=25',
            $t->calls[1]['url'],
        );
    }

    public function testApiErrorEnvelopeIsDecoded(): void
    {
        $t = new FakeTransport();
        $t->enqueue(200, json_encode(['access_token' => 'tok', 'expires_in' => 3600]));
        $t->enqueue(422, json_encode([
            'error' => [
                'code'     => 'validation_error',
                'message'  => 'totals.payable mismatch',
                'trace_id' => 'trace-xyz',
                'details'  => [['field' => 'totals.payable', 'rule' => 'crossCheckTotals']],
            ],
        ]));

        $client = $this->newClient($t);
        try {
            $client->invoices->submit(['x' => 1]);
            $this->fail('expected ApiError');
        } catch (ApiError $e) {
            $this->assertSame(422, $e->status);
            $this->assertSame('validation_error', $e->errorCode);
            $this->assertSame('validation_error', $e->code());
            $this->assertSame('trace-xyz', $e->traceId);
            $this->assertStringContainsString('totals.payable mismatch', $e->getMessage());
            $this->assertIsArray($e->details);
        }
    }

    public function testDeleteHandlesNoContent(): void
    {
        $t = new FakeTransport();
        $t->enqueue(200, json_encode(['access_token' => 'tok', 'expires_in' => 3600]));
        $t->enqueue(204, '');

        $client = $this->newClient($t);
        $client->webhooks->delete('wh_123');
        $this->assertSame('DELETE', $t->calls[1]['method']);
        $this->assertStringEndsWith('/v1/webhooks/wh_123', $t->calls[1]['url']);
    }

    public function testIdempotencyKeyHelperShape(): void
    {
        $key = LinkbridgeClient::idempotencyKey();
        $this->assertMatchesRegularExpression('/^lb-[0-9a-f]{32}$/', $key);
        $this->assertNotSame($key, LinkbridgeClient::idempotencyKey());
    }
}
