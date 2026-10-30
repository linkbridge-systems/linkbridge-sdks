<?php

declare(strict_types=1);

namespace Linkbridge;

/**
 * HMAC-SHA256 verification for Linkbridge webhook deliveries.
 *
 * Spec contract (mirrors API server implementation):
 *
 *     X-Linkbridge-Signature: t=<unix>,v1=<hex>
 *     v1 = hex(HMAC-SHA256(secret, "{t}.{body}"))
 *
 * The receiver MUST reject deliveries whose timestamp falls outside a
 * 5-minute window to bound replay-attack horizons.
 */
final class Webhook
{
    public const SIGNATURE_HEADER          = 'X-Linkbridge-Signature';
    public const MAX_SKEW_SECONDS          = 5 * 60;

    /**
     * @throws SignatureError on any failure mode
     */
    public static function verify(
        string $secret,
        string $body,
        string $header,
        ?int $now = null,
    ): void {
        if ($secret === '') {
            throw new SignatureError('secret must not be empty');
        }
        if ($header === '') {
            throw new SignatureError('missing X-Linkbridge-Signature');
        }

        [$t, $sig] = self::parseHeader($header);

        $current = $now ?? time();
        if (abs($current - $t) > self::MAX_SKEW_SECONDS) {
            throw new SignatureError('signature timestamp outside replay window');
        }

        $expected = hash_hmac('sha256', $t . '.' . $body, $secret);
        // Lower-case the candidate so we don't reject an upper-case
        // server (the API server emits lower hex; some proxies may
        // normalise). hash_equals is constant-time.
        if (! hash_equals($expected, strtolower($sig))) {
            throw new SignatureError('signature mismatch');
        }
    }

    /**
     * @return array{0:int,1:string} [timestamp, hex digest]
     */
    private static function parseHeader(string $header): array
    {
        $t   = null;
        $sig = null;
        foreach (explode(',', $header) as $part) {
            $kv = explode('=', trim($part), 2);
            if (count($kv) !== 2) {
                throw new SignatureError('malformed signature header');
            }
            [$key, $value] = $kv;
            if ($key === 't') {
                if (! preg_match('/^[1-9][0-9]*$/', $value)) {
                    throw new SignatureError('malformed signature timestamp');
                }
                $t = (int) $value;
            } elseif ($key === 'v1') {
                $sig = $value;
            }
            // Unknown keys are tolerated for forward-compat (e.g. v2=).
        }
        if ($t === null || $sig === null || $sig === '') {
            throw new SignatureError('malformed signature header');
        }
        return [$t, $sig];
    }
}
