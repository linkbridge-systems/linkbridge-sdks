package ng.linkbridge.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ng.linkbridge.sdk.model.Webhook;

/**
 * Webhook subscription management.
 *
 * <p>Use {@link WebhookEvents#SUBSCRIBABLE_WEBHOOK_EVENTS} for the event
 * names to offer in a picker; an empty {@code events} list subscribes to ALL
 * events (server semantics — the SDK passes it through untouched).
 */
public final class WebhooksApi {

  private final LinkbridgeClient client;

  WebhooksApi(LinkbridgeClient client) {
    this.client = client;
  }

  /**
   * Register a webhook endpoint. The returned {@link Webhook#secret()} is
   * shown ONLY on this response — persist it immediately.
   *
   * @param url HTTPS endpoint ({@code http://} is rejected at registration)
   * @param events event names to deliver; empty list = all events
   * @param description optional human label, or {@code null}
   */
  public Webhook create(String url, List<String> events, String description) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("url", url);
    body.put("events", events == null ? List.of() : events);
    if (description != null) {
      body.put("description", description);
    }
    JsonNode resp = client.sendJson("POST", "/v1/webhooks", null, body, null, true);
    return client.convert(resp, Webhook.class);
  }

  /** All webhook subscriptions for the authenticated tenant + mode. */
  public List<Webhook> list() {
    JsonNode resp = client.sendJson("GET", "/v1/webhooks", null, null, null, true);
    List<Webhook> out = new ArrayList<>();
    JsonNode data = resp == null ? null : resp.get("data");
    if (data != null && data.isArray()) {
      for (JsonNode item : data) {
        out.add(client.convert(item, Webhook.class));
      }
    }
    return out;
  }

  /** Delete a webhook subscription by id. */
  public void delete(String id) {
    client.sendJson(
        "DELETE", "/v1/webhooks/" + LinkbridgeClient.encodePathSegment(id), null, null, null, true);
  }
}
