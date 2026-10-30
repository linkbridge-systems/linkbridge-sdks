<?php

declare(strict_types=1);

namespace Linkbridge;

use RuntimeException;

/**
 * Raised for any non-2xx HTTP response from the Linkbridge API.
 *
 * Surfaces the canonical error envelope verbatim so callers can switch
 * on $code without parsing the human-readable $message:
 *
 *   { "error": { "code", "message", "trace_id", "details" } }
 */
final class ApiError extends RuntimeException
{
    /**
     * The canonical Linkbridge error code (e.g. `validation_error`).
     *
     * Exposed as a public field rather than via a getter to mirror the
     * Python / Node SDKs. We deliberately avoid the name `$code` because
     * `\Exception::$code` is a non-readonly int property on the base class.
     */
    public readonly string $errorCode;

    /**
     * @param array<string,mixed>|null $details
     */
    public function __construct(
        public readonly int $status,
        string $errorCode,
        string $message,
        public readonly ?string $traceId = null,
        public readonly mixed $details = null,
        public readonly string $raw = '',
    ) {
        $this->errorCode = $errorCode;
        parent::__construct(sprintf('linkbridge: %d %s: %s', $status, $errorCode, $message));
    }

    /**
     * Backwards-friendly alias for callers that prefer `$err->code()`.
     */
    public function code(): string
    {
        return $this->errorCode;
    }

    /**
     * Best-effort decoder. Falls back to opaque codes when the response
     * is not the canonical envelope (e.g. a 502 from a load balancer).
     */
    public static function fromResponse(int $status, string $body): self
    {
        $errorCode = 'http_error';
        $message   = $body !== '' ? substr($body, 0, 500) : ('http ' . $status);
        $traceId   = null;
        $details   = null;

        $decoded = json_decode($body, true);
        if (is_array($decoded) && isset($decoded['error']) && is_array($decoded['error'])) {
            $err = $decoded['error'];
            if (isset($err['code']) && is_string($err['code'])) {
                $errorCode = $err['code'];
            }
            if (isset($err['message']) && is_string($err['message'])) {
                $message = $err['message'];
            }
            if (isset($err['trace_id']) && is_string($err['trace_id'])) {
                $traceId = $err['trace_id'];
            }
            if (isset($err['details'])) {
                $details = $err['details'];
            }
        }

        return new self($status, $errorCode, $message, $traceId, $details, $body);
    }
}
