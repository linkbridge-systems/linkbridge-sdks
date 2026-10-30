package ng.linkbridge.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the event-type constants to the API's accepted set — the same pins as
 * the Node suite (events.test.ts), guarding against a re-introduction of the
 * historical dashboard drift (PR #41).
 */
class EventsTest {

  @Test
  void fullSetIsExactlyTheApiAcceptedSet() {
    assertEquals(
        List.of(
            "invoice.submitted",
            "invoice.signed",
            "invoice.transmitted",
            "invoice.failed",
            "webhook.test"),
        WebhookEvents.WEBHOOK_EVENT_TYPES);
  }

  @Test
  void driftedNamesAbsent() {
    assertFalse(WebhookEvents.WEBHOOK_EVENT_TYPES.contains("invoice.accepted"));
    assertFalse(WebhookEvents.WEBHOOK_EVENT_TYPES.contains("invoice.cancelled"));
  }

  @Test
  void subscribableExcludesWebhookTestAndIsSubset() {
    assertEquals(
        List.of("invoice.submitted", "invoice.signed", "invoice.transmitted", "invoice.failed"),
        WebhookEvents.SUBSCRIBABLE_WEBHOOK_EVENTS);
    assertFalse(WebhookEvents.SUBSCRIBABLE_WEBHOOK_EVENTS.contains("webhook.test"));
    assertTrue(WebhookEvents.WEBHOOK_EVENT_TYPES.containsAll(WebhookEvents.SUBSCRIBABLE_WEBHOOK_EVENTS));
  }
}
