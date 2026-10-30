package ng.linkbridge.sdk;

import java.util.List;

/**
 * Webhook event-type constants, kept in lockstep with the OpenAPI spec's
 * {@code WebhookCreate.events} enum — the single source of truth every SDK
 * derives from so UI pickers can never drift from what the API accepts
 * (historically the dashboard offered {@code invoice.accepted} /
 * {@code invoice.cancelled}, which the API rejects with 400).
 */
public final class WebhookEvents {

  /** Every event type the API accepts in a webhook {@code events} array, in spec order. */
  public static final List<String> WEBHOOK_EVENT_TYPES =
      List.of(
          "invoice.submitted",
          "invoice.signed",
          "invoice.transmitted",
          "invoice.failed",
          "webhook.test");

  /**
   * The events a subscription is meaningfully created for — the invoice
   * lifecycle events. {@code webhook.test} is excluded: it is delivered
   * directly to a webhook by {@code POST /v1/webhooks/{id}/test}, bypassing
   * the subscription filter, so offering it in a picker is misleading.
   */
  public static final List<String> SUBSCRIBABLE_WEBHOOK_EVENTS =
      WEBHOOK_EVENT_TYPES.stream().filter(e -> !e.equals("webhook.test")).toList();

  private WebhookEvents() {}
}
