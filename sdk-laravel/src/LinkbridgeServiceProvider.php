<?php

declare(strict_types=1);

namespace Linkbridge\Laravel;

use Illuminate\Contracts\Config\Repository as ConfigRepository;
use Illuminate\Contracts\Container\Container;
use Illuminate\Support\ServiceProvider;
use Linkbridge\LinkbridgeClient;
use Linkbridge\Transport\TransportInterface;

final class LinkbridgeServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->mergeConfigFrom(__DIR__ . '/../config/linkbridge.php', 'linkbridge');

        $this->app->singleton(LinkbridgeClient::class, function (Container $app): LinkbridgeClient {
            /** @var ConfigRepository $config */
            $config = $app->make(ConfigRepository::class);

            $options = [
                'base_url' => $this->nullableString($config->get('linkbridge.base_url')) ?? '',
                'client_id' => $this->nullableString($config->get('linkbridge.client_id')),
                'client_secret' => $this->nullableString($config->get('linkbridge.client_secret')),
                'static_token' => $this->nullableString($config->get('linkbridge.static_token')),
                'scopes' => $this->scopes($config->get('linkbridge.scopes')),
                'user_agent' => $this->userAgent($config->get('linkbridge.user_agent')),
            ];

            if ($app->bound(TransportInterface::class)) {
                $options['transport'] = $app->make(TransportInterface::class);
            }

            return new LinkbridgeClient($options);
        });

        $this->app->singleton(
            LinkbridgeManager::class,
            static fn (Container $app): LinkbridgeManager => new LinkbridgeManager(
                $app->make(LinkbridgeClient::class),
            ),
        );

        $this->app->alias(LinkbridgeManager::class, 'linkbridge');
    }

    public function boot(): void
    {
        $this->publishes([
            __DIR__ . '/../config/linkbridge.php' => $this->app->configPath('linkbridge.php'),
        ], 'linkbridge-config');
    }

    private function nullableString(mixed $value): ?string
    {
        if (! is_string($value)) {
            return null;
        }

        $value = trim($value);

        return $value === '' ? null : $value;
    }

    /**
     * @return list<string>
     */
    private function scopes(mixed $value): array
    {
        if (is_string($value)) {
            $value = explode(',', $value);
        }

        if (! is_array($value)) {
            return LinkbridgeClient::DEFAULT_SCOPES;
        }

        $scopes = [];
        foreach ($value as $scope) {
            if (is_string($scope) && trim($scope) !== '') {
                $scopes[] = trim($scope);
            }
        }

        return $scopes === [] ? LinkbridgeClient::DEFAULT_SCOPES : $scopes;
    }

    private function userAgent(mixed $suffix): string
    {
        $userAgent = sprintf(
            'linkbridge-laravel/%s linkbridge-php/%s',
            LinkbridgeLaravel::VERSION,
            LinkbridgeClient::SDK_VERSION,
        );
        $suffix = $this->nullableString($suffix);

        return $suffix === null ? $userAgent : $userAgent . ' ' . $suffix;
    }
}
