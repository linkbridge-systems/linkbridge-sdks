<?php

declare(strict_types=1);

namespace Linkbridge;

use Linkbridge\Resources\Invoices;
use Linkbridge\Resources\Lookups;
use Linkbridge\Resources\Webhooks;
use Linkbridge\Transport\CurlTransport;
use Linkbridge\Transport\TransportInterface;

/**
 * Linkbridge HTTP client with OAuth2 client-credentials token caching.
 *
 * Mirrors the API surface of the Python / Node / Go SDKs so an integrator
 * can swap stacks without re-learning conventions.
 */
final class LinkbridgeClient
{
    public const SDK_VERSION = '0.4.0';
    public const DEFAULT_SCOPES = ['invoices:write', 'invoices:read'];
    private const TOKEN_REFRESH_LEEWAY_SECONDS = 60;

    public readonly string $baseUrl;
    public readonly string $userAgent;
    public readonly TransportInterface $transport;

    public readonly Invoices $invoices;
    public readonly Webhooks $webhooks;
    public readonly Lookups  $lookups;

    /** @var list<string> */
    private array $scopes;
    private ?string $clientId;
    private ?string $clientSecret;
    private ?string $staticToken;

    private ?string $cachedToken = null;
    private int     $cachedTokenExpiresAt = 0;

    /**
     * @param array{
     *   base_url: string,
     *   client_id?: ?string,
     *   client_secret?: ?string,
     *   static_token?: ?string,
     *   scopes?: list<string>,
     *   user_agent?: ?string,
     *   transport?: ?TransportInterface,
     * } $opts
     */
    public function __construct(array $opts = [])
    {
        // base_url is required — the API is single-host, but there is no safe
        // universal default (localhost was a dev footgun in prod), and the
        // Go/Node/Python SDKs all require it. Standardise: throw when absent.
        $baseUrl = $opts['base_url'] ?? '';
        if ($baseUrl === '') {
            throw new \InvalidArgumentException('base_url is required (e.g. https://api.linkbridge.ng)');
        }
        $this->baseUrl      = rtrim($baseUrl, '/');
        $this->clientId     = $opts['client_id']     ?? null;
        $this->clientSecret = $opts['client_secret'] ?? null;
        $this->staticToken  = $opts['static_token']  ?? null;
        $this->scopes       = $opts['scopes']        ?? self::DEFAULT_SCOPES;
        $this->userAgent    = $opts['user_agent']    ?? ('linkbridge-php/' . self::SDK_VERSION);
        $this->transport    = $opts['transport']     ?? new CurlTransport();

        if ($this->staticToken === null && ($this->clientId === null || $this->clientSecret === null)) {
            throw new \InvalidArgumentException(
                'either static_token or client_id+client_secret must be provided',
            );
        }

        $this->invoices = new Invoices($this);
        $this->webhooks = new Webhooks($this);
        $this->lookups  = new Lookups($this);
    }

    /**
     * Generate a fresh idempotency key shaped like the rest of the SDKs.
     */
    public static function idempotencyKey(): string
    {
        return 'lb-' . bin2hex(random_bytes(16));
    }

    /**
     * Perform an authenticated request against the API.
     *
     * @param 'GET'|'POST'|'PUT'|'PATCH'|'DELETE' $method
     * @param array{
     *   query?: array<string,scalar|null>,
     *   json?: mixed,
     *   headers?: array<string,string>,
     *   idempotency_key?: ?string,
     *   auth?: bool,
     * } $opts
     * @return mixed Decoded JSON body, or null for 204.
     */
    public function request(string $method, string $path, array $opts = []): mixed
    {
        $url = $this->baseUrl . '/' . ltrim($path, '/');
        if (! empty($opts['query'])) {
            $pairs = [];
            foreach ($opts['query'] as $k => $v) {
                if ($v === null) {
                    continue;
                }
                $pairs[$k] = is_bool($v) ? ($v ? 'true' : 'false') : (string) $v;
            }
            if ($pairs !== []) {
                $url .= '?' . http_build_query($pairs);
            }
        }

        $headers = [
            'Accept'     => 'application/json',
            'User-Agent' => $this->userAgent,
        ];
        foreach ($opts['headers'] ?? [] as $k => $v) {
            $headers[$k] = $v;
        }

        $body = '';
        if (array_key_exists('json', $opts)) {
            $body = json_encode($opts['json'], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
            if ($body === false) {
                throw new \InvalidArgumentException('failed to encode json body: ' . json_last_error_msg());
            }
            $headers['Content-Type'] = 'application/json';
        }

        if (in_array($method, ['POST', 'PUT', 'PATCH'], true)
            && ! isset($headers['Idempotency-Key'])
        ) {
            $headers['Idempotency-Key'] = $opts['idempotency_key'] ?? self::idempotencyKey();
        }

        $auth = $opts['auth'] ?? true;
        if ($auth) {
            $headers['Authorization'] = 'Bearer ' . $this->token();
        }

        [$status, , $respBody] = $this->transport->send($method, $url, $headers, $body);

        if ($status >= 400) {
            throw ApiError::fromResponse($status, $respBody);
        }
        if ($status === 204 || $respBody === '') {
            return null;
        }
        $decoded = json_decode($respBody, true);
        if (json_last_error() !== JSON_ERROR_NONE) {
            throw new \RuntimeException('failed to decode response body: ' . json_last_error_msg());
        }
        return $decoded;
    }

    /**
     * Returns a valid bearer token, refreshing lazily.
     */
    private function token(): string
    {
        if ($this->staticToken !== null) {
            return $this->staticToken;
        }
        $now = time();
        if ($this->cachedToken !== null && ($this->cachedTokenExpiresAt - self::TOKEN_REFRESH_LEEWAY_SECONDS) > $now) {
            return $this->cachedToken;
        }

        $payload = [
            'grant_type'    => 'client_credentials',
            'client_id'     => $this->clientId,
            'client_secret' => $this->clientSecret,
            'scope'         => implode(' ', $this->scopes),
        ];

        $resp = $this->request('POST', '/v1/oauth/token', [
            'json'            => $payload,
            'auth'            => false,
            'idempotency_key' => null,
            'headers'         => ['Idempotency-Key' => self::idempotencyKey()],
        ]);
        if (! is_array($resp) || ! isset($resp['access_token'], $resp['expires_in'])) {
            throw new \RuntimeException('oauth response missing access_token / expires_in');
        }

        $this->cachedToken          = (string) $resp['access_token'];
        $this->cachedTokenExpiresAt = $now + (int) $resp['expires_in'];
        return $this->cachedToken;
    }
}
