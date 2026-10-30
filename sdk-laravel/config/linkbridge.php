<?php

declare(strict_types=1);

$scopes = array_values(array_filter(array_map(
    static fn (string $scope): string => trim($scope),
    explode(',', (string) env('LINKBRIDGE_SCOPES', 'invoices:write,invoices:read')),
)));

return [
    /*
    |--------------------------------------------------------------------------
    | LinkBridge API
    |--------------------------------------------------------------------------
    |
    | No base URL is assumed. Set the URL explicitly so a production
    | application can never fall back to a local or test endpoint.
    |
    */
    'base_url' => env('LINKBRIDGE_BASE_URL'),

    /*
    |--------------------------------------------------------------------------
    | Authentication
    |--------------------------------------------------------------------------
    |
    | Production applications normally use OAuth client credentials. A static
    | token is useful for short-lived operational jobs and bypasses OAuth.
    |
    */
    'client_id' => env('LINKBRIDGE_CLIENT_ID'),
    'client_secret' => env('LINKBRIDGE_CLIENT_SECRET'),
    'static_token' => env('LINKBRIDGE_STATIC_TOKEN'),
    'scopes' => $scopes,

    /*
    |--------------------------------------------------------------------------
    | Diagnostics and webhook verification
    |--------------------------------------------------------------------------
    */
    'user_agent' => env('LINKBRIDGE_USER_AGENT'),
    'webhook_secret' => env('LINKBRIDGE_WEBHOOK_SECRET'),
];
