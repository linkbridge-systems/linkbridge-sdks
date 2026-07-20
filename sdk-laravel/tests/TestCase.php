<?php

declare(strict_types=1);

namespace Linkbridge\Laravel\Tests;

use Linkbridge\Laravel\LinkbridgeServiceProvider;
use Orchestra\Testbench\TestCase as Orchestra;

abstract class TestCase extends Orchestra
{
    /**
     * @return list<class-string>
     */
    protected function getPackageProviders($app): array
    {
        return [LinkbridgeServiceProvider::class];
    }

    protected function defineEnvironment($app): void
    {
        $app['config']->set('linkbridge.base_url', 'https://api.example.test');
        $app['config']->set('linkbridge.static_token', 'test-token');
    }
}
