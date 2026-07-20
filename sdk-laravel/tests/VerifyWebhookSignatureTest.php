<?php

declare(strict_types=1);

namespace Linkbridge\Laravel\Tests;

use Illuminate\Http\Request;
use Linkbridge\Laravel\Http\Middleware\VerifyWebhookSignature;
use Linkbridge\Webhook;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\HttpException;

final class VerifyWebhookSignatureTest extends TestCase
{
    public function testValidSignaturePassesRawBodyToNextMiddleware(): void
    {
        $secret = 'whsec_test_secret';
        $body = '{"event":"invoice.transmitted","data":{}}';
        $timestamp = time();
        $header = sprintf(
            't=%d,v1=%s',
            $timestamp,
            hash_hmac('sha256', $timestamp . '.' . $body, $secret),
        );
        $this->app['config']->set('linkbridge.webhook_secret', $secret);
        $request = Request::create(
            '/webhooks/linkbridge',
            'POST',
            server: ['HTTP_' . strtoupper(str_replace('-', '_', Webhook::SIGNATURE_HEADER)) => $header],
            content: $body,
        );

        $response = (new VerifyWebhookSignature())->handle(
            $request,
            static fn (): Response => new Response('accepted', 202),
        );

        $this->assertSame(202, $response->getStatusCode());
        $this->assertSame('accepted', $response->getContent());
    }

    public function testInvalidSignatureIsRejectedWithoutCallingNext(): void
    {
        $this->app['config']->set('linkbridge.webhook_secret', 'whsec_test_secret');
        $request = Request::create(
            '/webhooks/linkbridge',
            'POST',
            server: ['HTTP_X_LINKBRIDGE_SIGNATURE' => 't=1700000000,v1=bad'],
            content: '{}',
        );
        $called = false;

        try {
            (new VerifyWebhookSignature())->handle(
                $request,
                static function () use (&$called): Response {
                    $called = true;

                    return new Response();
                },
            );
            $this->fail('expected invalid signature to be rejected');
        } catch (HttpException $error) {
            $this->assertSame(401, $error->getStatusCode());
            $this->assertSame('Invalid LinkBridge webhook signature', $error->getMessage());
        }

        $this->assertFalse($called);
    }

    public function testMissingWebhookSecretIsAConfigurationError(): void
    {
        $this->app['config']->set('linkbridge.webhook_secret', null);

        $this->expectException(\LogicException::class);
        (new VerifyWebhookSignature())->handle(
            Request::create('/webhooks/linkbridge', 'POST', content: '{}'),
            static fn (): Response => new Response(),
        );
    }
}
