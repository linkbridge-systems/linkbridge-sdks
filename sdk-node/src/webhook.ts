/**
 * Webhook signature verification for Linkbridge HMAC-signed deliveries.
 *
 * Per spec §8.5, every webhook request carries:
 *
 *     X-Linkbridge-Signature: t=<unix>,v1=<hex>
 *
 * where `v1 = HMAC-SHA256(secret, t + "." + body)`. Receivers MUST
 * reject requests outside a 5-minute clock window to bound replay
 * attacks.
 */
import { createHmac, timingSafeEqual } from "node:crypto";
import type { components } from "./oapi/schema.gen.js";

export const SIGNATURE_HEADER = "x-linkbridge-signature";
export const MAX_WEBHOOK_SKEW_SECONDS = 300; // 5 minutes

/**
 * A webhook event type, sourced from the OpenAPI spec (`WebhookCreate.events`)
 * so it is always exactly the set the API accepts. Regenerate the underlying
 * schema with `npm run codegen`.
 */
export type WebhookEventType = components["schemas"]["WebhookCreate"]["events"][number];

/**
 * Every event type the API accepts in a webhook `events` array, in spec order.
 * The `satisfies` clause makes the compiler reject any value that is not a real
 * `WebhookEventType`, so this list cannot silently drift from the OpenAPI enum
 * the SDK is generated from (the drift that let the dashboard offer bogus
 * `invoice.accepted` / `invoice.cancelled` events the API 400'd on).
 */
export const WEBHOOK_EVENT_TYPES = [
  "invoice.submitted",
  "invoice.signed",
  "invoice.transmitted",
  "invoice.failed",
  "webhook.test",
] as const satisfies readonly WebhookEventType[];

/**
 * The events a subscription is meaningfully created for — the invoice lifecycle
 * events. `webhook.test` is excluded: it is delivered directly to a webhook by
 * `POST /v1/webhooks/{id}/test` (bypassing the subscription filter), so offering
 * it in a "which events?" picker is misleading. Derived from
 * `WEBHOOK_EVENT_TYPES`, so it stays in lockstep with the spec.
 */
export const SUBSCRIBABLE_WEBHOOK_EVENTS: readonly WebhookEventType[] =
  WEBHOOK_EVENT_TYPES.filter((e) => e !== "webhook.test");

export class WebhookVerificationError extends Error {
  readonly reason:
    | "missing"
    | "malformed"
    | "expired"
    | "mismatch";
  constructor(reason: WebhookVerificationError["reason"], message: string) {
    super(`linkbridge webhook: ${message}`);
    this.name = "WebhookVerificationError";
    this.reason = reason;
  }
}

export interface VerifyOptions {
  /** Inject the current time (epoch seconds) for tests. Default: now. */
  now?: number;
  /** Override the replay window (seconds). Default: 300. */
  toleranceSeconds?: number;
}

/**
 * Verify a Linkbridge webhook delivery. `secret` is the webhook's
 * shared secret (returned only at creation), `header` is the raw
 * `X-Linkbridge-Signature` value, and `body` MUST be the exact bytes
 * the receiver read off the wire — re-serialising parsed JSON will
 * change whitespace and break verification.
 *
 * Throws WebhookVerificationError on failure; returns void on success.
 */
export function verifyWebhook(
  secret: string | Buffer,
  body: string | Buffer,
  header: string | null | undefined,
  opts: VerifyOptions = {},
): void {
  if (!header) {
    throw new WebhookVerificationError("missing", "missing X-Linkbridge-Signature");
  }
  const tolerance = opts.toleranceSeconds ?? MAX_WEBHOOK_SKEW_SECONDS;
  const now = opts.now ?? Math.floor(Date.now() / 1000);

  const { t, v1 } = parseSignatureHeader(header);

  if (Math.abs(now - t) > tolerance) {
    throw new WebhookVerificationError(
      "expired",
      `signature timestamp outside ${tolerance}s replay window`,
    );
  }

  const mac = createHmac("sha256", secret);
  mac.update(String(t));
  mac.update(".");
  mac.update(typeof body === "string" ? Buffer.from(body, "utf8") : body);
  const expected = mac.digest();

  let got: Buffer;
  try {
    got = Buffer.from(v1, "hex");
  } catch {
    throw new WebhookVerificationError("malformed", "v1 is not valid hex");
  }
  if (got.length !== expected.length || !timingSafeEqual(got, expected)) {
    throw new WebhookVerificationError("mismatch", "signature mismatch");
  }
}

interface ParsedSignature {
  t: number;
  v1: string;
}

function parseSignatureHeader(header: string): ParsedSignature {
  let t = 0;
  let v1 = "";
  for (const part of header.split(",")) {
    const eq = part.indexOf("=");
    if (eq < 0) throw new WebhookVerificationError("malformed", "missing '=' in part");
    const key = part.slice(0, eq).trim();
    const val = part.slice(eq + 1).trim();
    if (key === "t") {
      const n = Number(val);
      if (!Number.isInteger(n) || n <= 0) {
        throw new WebhookVerificationError("malformed", "invalid t");
      }
      t = n;
    } else if (key === "v1") {
      v1 = val;
    }
  }
  if (t === 0 || v1 === "") {
    throw new WebhookVerificationError("malformed", "missing t or v1");
  }
  return { t, v1 };
}
