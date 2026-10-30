package ng.linkbridge.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import ng.linkbridge.sdk.model.InvoiceSubmission;
import ng.linkbridge.sdk.model.InvoiceRecord;
import ng.linkbridge.sdk.model.Webhook;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in end-to-end test against a real Linkbridge stack (e.g. `make dev-up`).
 * Skipped unless LB_E2E=1. Required env: LB_BASE_URL, LB_CLIENT_ID,
 * LB_CLIENT_SECRET, LB_SAMPLE_INVOICE (path to apps/api/testdata/sample-invoice.json).
 *
 * <p>Run: {@code LB_E2E=1 LB_BASE_URL=http://localhost:18080 LB_CLIENT_ID=... \
 *   LB_CLIENT_SECRET=... LB_SAMPLE_INVOICE=../../apps/api/testdata/sample-invoice.json \
 *   mvn test -Dtest=E2eLiveStackTest}
 */
@EnabledIfEnvironmentVariable(named = "LB_E2E", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2eLiveStackTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static String submittedIrn;
  private static String webhookId;

  private static LinkbridgeClient client() {
    return LinkbridgeClient.builder()
        .baseUrl(System.getenv("LB_BASE_URL"))
        .clientId(System.getenv("LB_CLIENT_ID"))
        .clientSecret(System.getenv("LB_CLIENT_SECRET"))
        .scopes(java.util.List.of("invoices:write", "invoices:read", "webhooks:manage"))
        .userAgent("sdk-java-e2e/0")
        .build();
  }

  private static ObjectNode sampleInvoice() throws Exception {
    JsonNode base = JSON.readTree(Files.readString(Path.of(System.getenv("LB_SAMPLE_INVOICE"))));
    ObjectNode inv = base.deepCopy();
    inv.put("business_id", UUID.randomUUID().toString());
    // IRN shape the FIRS contract enforces: <InvoiceNo>-<8-char ServiceId>-<YYYYMMDD>.
    byte[] rnd = new byte[4];
    new SecureRandom().nextBytes(rnd);
    inv.put("irn", "JAVAE2E-" + HexFormat.of().formatHex(rnd).toUpperCase() + "-20260711");
    return inv;
  }

  @Test
  @Order(1)
  void submitGetList() throws Exception {
    LinkbridgeClient c = client();
    ObjectNode inv = sampleInvoice();
    InvoiceSubmission acc = c.invoices().submit(inv);
    assertNotNull(acc.irn());
    assertEquals(inv.get("irn").asText(), acc.irn());
    submittedIrn = acc.irn();

    InvoiceRecord rec = c.invoices().get(submittedIrn);
    assertEquals(submittedIrn, rec.irn());
    assertNotNull(rec.status());

    assertFalse(c.invoices().list(null, 10, null).data().isEmpty());
  }

  @Test
  @Order(2)
  void webhookLifecycleAndTestDelivery() {
    LinkbridgeClient c = client();
    Webhook created =
        c.webhooks()
            .create(
                "https://merchant.example/java-e2e",
                WebhookEvents.SUBSCRIBABLE_WEBHOOK_EVENTS,
                "java sdk e2e");
    assertNotNull(created.id());
    assertNotNull(created.secret(), "secret must be present on create");
    webhookId = created.id();

    assertTrue(c.webhooks().list().stream().anyMatch(w -> w.id().equals(webhookId)));

    // The generic escape hatch: fire a webhook.test delivery.
    JsonNode test =
        c.request(
            "POST",
            "/v1/webhooks/" + LinkbridgeClient.encodePathSegment(webhookId) + "/test",
            null,
            null,
            null);
    assertNotNull(test.get("delivery_id"), test.toString());

    c.webhooks().delete(webhookId);
    assertTrue(c.webhooks().list().stream().noneMatch(w -> w.id().equals(webhookId)));
  }

  @Test
  @Order(3)
  void errorEnvelopeSurfaces() {
    LinkbridgeClient c = client();
    LinkbridgeApiException e =
        assertThrows(
            LinkbridgeApiException.class,
            () -> c.invoices().submit(Map.of("irn", "not-a-valid-irn")));
    assertEquals(422, e.status());
    assertEquals("schema_validation_failed", e.code());
    assertNotNull(e.traceId());
  }

  @Test
  @Order(4)
  void invalidEventRejectedByApi() {
    LinkbridgeClient c = client();
    LinkbridgeApiException e =
        assertThrows(
            LinkbridgeApiException.class,
            () ->
                c.webhooks()
                    .create("https://merchant.example/bad", java.util.List.of("invoice.accepted"), null));
    assertEquals(400, e.status());
    assertEquals("invalid_events", e.code());
  }
}
