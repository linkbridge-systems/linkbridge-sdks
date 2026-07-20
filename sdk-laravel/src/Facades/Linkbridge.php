<?php

declare(strict_types=1);

namespace Linkbridge\Laravel\Facades;

use Illuminate\Support\Facades\Facade;
use Linkbridge\Laravel\LinkbridgeManager;
use Linkbridge\Resources\Invoices;
use Linkbridge\Resources\Lookups;
use Linkbridge\Resources\Webhooks;

/**
 * @method static \Linkbridge\LinkbridgeClient client()
 * @method static Invoices invoices()
 * @method static Webhooks webhooks()
 * @method static Lookups lookups()
 * @method static string idempotencyKey()
 * @method static void verifyWebhook(string $secret, string $body, string $signatureHeader, ?int $now = null)
 *
 * @see LinkbridgeManager
 */
final class Linkbridge extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return LinkbridgeManager::class;
    }
}
