import { describe, it, expect } from "vitest";
import {
  WEBHOOK_EVENT_TYPES,
  SUBSCRIBABLE_WEBHOOK_EVENTS,
} from "./index.js";

// These lists are the single source of truth the dashboard/CLI/docs consume.
// The `satisfies` clause in webhook.ts already makes tsc reject an INVALID
// value (e.g. a re-introduced "invoice.accepted"); these tests pin the exact
// set so an accidental REMOVAL, or a change to the webhook.test exclusion, is
// caught too.
describe("webhook event types", () => {
  it("WEBHOOK_EVENT_TYPES is exactly the API's accepted set", () => {
    expect([...WEBHOOK_EVENT_TYPES].sort()).toEqual(
      [
        "invoice.failed",
        "invoice.signed",
        "invoice.submitted",
        "invoice.transmitted",
        "webhook.test",
      ].sort(),
    );
  });

  it("does NOT contain the drifted names the API rejects", () => {
    expect(WEBHOOK_EVENT_TYPES).not.toContain("invoice.accepted");
    expect(WEBHOOK_EVENT_TYPES).not.toContain("invoice.cancelled");
  });

  it("SUBSCRIBABLE_WEBHOOK_EVENTS is the lifecycle set with webhook.test excluded", () => {
    expect([...SUBSCRIBABLE_WEBHOOK_EVENTS].sort()).toEqual(
      ["invoice.failed", "invoice.signed", "invoice.submitted", "invoice.transmitted"].sort(),
    );
    expect(SUBSCRIBABLE_WEBHOOK_EVENTS).not.toContain("webhook.test");
  });

  it("SUBSCRIBABLE is a strict subset of the full set", () => {
    for (const e of SUBSCRIBABLE_WEBHOOK_EVENTS) {
      expect(WEBHOOK_EVENT_TYPES).toContain(e);
    }
  });
});
