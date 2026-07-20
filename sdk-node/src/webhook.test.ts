import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import {
  MAX_WEBHOOK_SKEW_SECONDS,
  SIGNATURE_HEADER,
  WebhookVerificationError,
  verifyWebhook,
} from "../src/webhook.js";

const SECRET = "shh";

function sign(body: string, t: number, secret = SECRET): string {
  const mac = createHmac("sha256", secret);
  mac.update(String(t));
  mac.update(".");
  mac.update(body);
  return `t=${t},v1=${mac.digest("hex")}`;
}

describe("verifyWebhook", () => {
  const now = 1_700_000_000;
  const body = `{"event":"invoice.accepted"}`;

  it("accepts a freshly signed payload", () => {
    expect(() => verifyWebhook(SECRET, body, sign(body, now), { now })).not.toThrow();
  });

  it("accepts at the boundary of the replay window", () => {
    const t = now - MAX_WEBHOOK_SKEW_SECONDS;
    expect(() => verifyWebhook(SECRET, body, sign(body, t), { now })).not.toThrow();
  });

  it("rejects a missing header", () => {
    expect(() => verifyWebhook(SECRET, body, undefined, { now }))
      .toThrowError(WebhookVerificationError);
  });

  it("rejects malformed headers", () => {
    for (const h of ["abc", "t=,v1=ff", "t=abc,v1=ff", "t=1700000000", "v1=ff"]) {
      try {
        verifyWebhook(SECRET, body, h, { now });
        expect.fail(`should reject: ${h}`);
      } catch (e) {
        expect((e as WebhookVerificationError).reason).toBe("malformed");
      }
    }
  });

  it("rejects expired signatures (past)", () => {
    const stale = sign(body, now - 600);
    try {
      verifyWebhook(SECRET, body, stale, { now });
      expect.fail("should reject");
    } catch (e) {
      expect((e as WebhookVerificationError).reason).toBe("expired");
    }
  });

  it("rejects future-dated signatures", () => {
    const future = sign(body, now + 600);
    expect(() => verifyWebhook(SECRET, body, future, { now }))
      .toThrowError(/expired|outside/);
  });

  it("rejects signatures from a different secret", () => {
    const wrong = sign(body, now, "other-secret");
    try {
      verifyWebhook(SECRET, body, wrong, { now });
      expect.fail("should reject");
    } catch (e) {
      expect((e as WebhookVerificationError).reason).toBe("mismatch");
    }
  });

  it("rejects body tampering", () => {
    const good = sign(body, now);
    try {
      verifyWebhook(SECRET, "tampered", good, { now });
      expect.fail("should reject");
    } catch (e) {
      expect((e as WebhookVerificationError).reason).toBe("mismatch");
    }
  });

  it("rejects malformed hex in v1", () => {
    const hdr = `t=${now},v1=zzz-not-hex`;
    try {
      verifyWebhook(SECRET, body, hdr, { now });
      expect.fail("should reject");
    } catch (e) {
      expect((e as WebhookVerificationError).reason).toBe("mismatch");
    }
  });

  it("accepts Buffer secret + Buffer body", () => {
    const sig = sign(body, now);
    expect(() =>
      verifyWebhook(Buffer.from(SECRET), Buffer.from(body, "utf8"), sig, { now }),
    ).not.toThrow();
  });

  it("exports the canonical header name (lowercase)", () => {
    expect(SIGNATURE_HEADER).toBe("x-linkbridge-signature");
  });
});
