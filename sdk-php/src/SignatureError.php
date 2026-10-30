<?php

declare(strict_types=1);

namespace Linkbridge;

use RuntimeException;

/** Raised by Webhook::verify when a delivery cannot be trusted. */
final class SignatureError extends RuntimeException
{
}
