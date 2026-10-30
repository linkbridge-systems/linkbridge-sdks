<?php

declare(strict_types=1);

namespace Linkbridge\Laravel\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Linkbridge\SignatureError;
use Linkbridge\Webhook;
use Symfony\Component\HttpKernel\Exception\HttpException;

/**
 * Reject webhook requests whose raw payload does not carry a valid signature.
 */
final class VerifyWebhookSignature
{
    public function handle(Request $request, Closure $next): mixed
    {
        $secret = config('linkbridge.webhook_secret');
        if (! is_string($secret) || $secret === '') {
            throw new \LogicException(
                'LINKBRIDGE_WEBHOOK_SECRET must be configured before using VerifyWebhookSignature',
            );
        }

        try {
            Webhook::verify(
                $secret,
                $request->getContent(),
                (string) $request->headers->get(Webhook::SIGNATURE_HEADER, ''),
            );
        } catch (SignatureError $error) {
            throw new HttpException(401, 'Invalid LinkBridge webhook signature', $error);
        }

        return $next($request);
    }
}
