<?php

declare(strict_types=1);

namespace Linkbridge\Laravel\Tests;

use Illuminate\Support\ServiceProvider;
use Linkbridge\Laravel\Facades\Linkbridge as LinkbridgeFacade;
use Linkbridge\Laravel\LinkbridgeManager;
use Linkbridge\Laravel\LinkbridgeServiceProvider;
use Linkbridge\LinkbridgeClient;
use Linkbridge\Transport\TransportInterface;

final class ServiceProviderTest extends TestCase
{
    public function testClientAndManagerAreSingletons(): void
    {
        $client = $this->app->make(LinkbridgeClient::class);
        $manager = $this->app->make(LinkbridgeManager::class);

        $this->assertSame($client, $this->app->make(LinkbridgeClient::class));
        $this->assertSame($manager, $this->app->make('linkbridge'));
        $this->assertSame($client, $manager->client());
        $this->assertSame($client->invoices, $manager->invoices());
        $this->assertSame($client->webhooks, $manager->webhooks());
        $this->assertSame($client->lookups, $manager->lookups());
    }

    public function testFacadeResolvesManagerAndGeneratesKeys(): void
    {
        $this->assertSame(
            $this->app->make(LinkbridgeClient::class),
            LinkbridgeFacade::client(),
        );
        $this->assertMatchesRegularExpression('/^lb-[0-9a-f]{32}$/', LinkbridgeFacade::idempotencyKey());
    }

    public function testConfigurationIsPublishable(): void
    {
        $paths = ServiceProvider::pathsToPublish(
            LinkbridgeServiceProvider::class,
            'linkbridge-config',
        );

        $this->assertCount(1, $paths);
        $this->assertStringEndsWith('/config/linkbridge.php', array_key_first($paths));
        $this->assertStringEndsWith('/config/linkbridge.php', array_values($paths)[0]);
    }

    public function testConfiguredTransportAndUserAgentReachCoreSdk(): void
    {
        $transport = new FakeTransport();
        $transport->enqueue(200, '{"data":[],"next_cursor":null}');
        $this->app->instance(TransportInterface::class, $transport);
        $this->app['config']->set('linkbridge.user_agent', 'merchant-app/1.2');

        $client = $this->app->make(LinkbridgeClient::class);
        $client->invoices->list();

        $this->assertSame($transport, $client->transport);
        $this->assertSame(
            'linkbridge-laravel/0.4.0 linkbridge-php/0.4.0 merchant-app/1.2',
            $client->userAgent,
        );
        $this->assertSame('Bearer test-token', $transport->calls[0]['headers']['Authorization']);
    }

    public function testEmptyCredentialsAreNormalizedAndRejected(): void
    {
        $this->app['config']->set('linkbridge.static_token', ' ');
        $this->app['config']->set('linkbridge.client_id', '');
        $this->app['config']->set('linkbridge.client_secret', null);

        $this->expectException(\InvalidArgumentException::class);
        $this->app->make(LinkbridgeClient::class);
    }
}
