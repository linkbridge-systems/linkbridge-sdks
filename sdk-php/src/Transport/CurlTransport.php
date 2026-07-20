<?php

declare(strict_types=1);

namespace Linkbridge\Transport;

use RuntimeException;

/**
 * Default transport built on the bundled curl extension. No
 * Composer dependencies; works on the bare PHP base image.
 */
final class CurlTransport implements TransportInterface
{
    public function __construct(
        private readonly int $timeoutSeconds = 30,
        private readonly bool $verifyTls = true,
    ) {
    }

    public function send(string $method, string $url, array $headers, ?string $body): array
    {
        $ch = curl_init();
        if ($ch === false) {
            throw new RuntimeException('linkbridge: curl_init failed');
        }

        $curlHeaders = [];
        foreach ($headers as $name => $value) {
            $curlHeaders[] = $name . ': ' . $value;
        }

        $responseHeaders = [];
        curl_setopt_array($ch, [
            CURLOPT_URL            => $url,
            CURLOPT_CUSTOMREQUEST  => strtoupper($method),
            CURLOPT_HTTPHEADER     => $curlHeaders,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => $this->timeoutSeconds,
            CURLOPT_CONNECTTIMEOUT => min(10, $this->timeoutSeconds),
            CURLOPT_SSL_VERIFYPEER => $this->verifyTls,
            CURLOPT_SSL_VERIFYHOST => $this->verifyTls ? 2 : 0,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_HEADERFUNCTION => static function ($_ch, string $rawHeader) use (&$responseHeaders): int {
                $line = trim($rawHeader);
                if ($line === '' || stripos($line, 'HTTP/') === 0) {
                    return strlen($rawHeader);
                }
                $colon = strpos($line, ':');
                if ($colon !== false) {
                    $name  = strtolower(trim(substr($line, 0, $colon)));
                    $value = trim(substr($line, $colon + 1));
                    $responseHeaders[$name] = $value;
                }
                return strlen($rawHeader);
            },
        ]);

        if ($body !== null) {
            curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
        }

        $raw = curl_exec($ch);
        if ($raw === false) {
            $err = curl_error($ch);
            curl_close($ch);
            throw new RuntimeException('linkbridge: curl error: ' . $err);
        }

        $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);

        return [$status, $responseHeaders, (string) $raw];
    }
}
