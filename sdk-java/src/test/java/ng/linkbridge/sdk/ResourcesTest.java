package ng.linkbridge.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import ng.linkbridge.sdk.model.InvoicePage;
import ng.linkbridge.sdk.model.InvoiceRecord;
import ng.linkbridge.sdk.model.InvoiceSubmission;
import ng.linkbridge.sdk.model.Webhook;
import org.junit.jupiter.api.Test;

class ResourcesTest {

  private static LinkbridgeClient client(FakeTransport t) {
    return LinkbridgeClient.builder()
        .baseUrl("https://api.example.test")
        .staticToken("tok")
        .transport(t)
        .build();
  }

  // ── invoices.submit ────────────────────────────────────────────────────

  @Test
  void submitAutoGeneratesIdempotencyKey() {
    FakeTransport t = new FakeTransport();
    t.enqueue(202, "{\"irn\":\"INV1-ABCDEF12-20260711\",\"status\":\"pending\",\"tracking_url\":\"https://x/t\"}");
    InvoiceSubmission acc = client(t).invoices().submit(Map.of("irn", "INV1-ABCDEF12-20260711"));
    assertEquals("INV1-ABCDEF12-20260711", acc.irn());
    String key = t.lastApi().headers().get("idempotency-key");
    assertTrue(key.matches("^lb-[0-9a-f]{32}$"), key);
    assertNull(t.lastApi().uri().getQuery(), "no ?mode= when unset");
  }

  @Test
  void submitCallerKeyAndModeWin() {
    FakeTransport t = new FakeTransport();
    t.enqueue(200, "{\"irn\":\"I-ABCDEF12-20260711\",\"status\":\"transmitted\",\"qr_code_data\":\"QR\",\"signed_jws\":\"j.w.s\"}");
    InvoiceSubmission acc =
        client(t)
            .invoices()
            .submit(
                Map.of("irn", "I-ABCDEF12-20260711"),
                new InvoicesApi.SubmitOptions("lb-00000000000000000000000000000000", InvoicesApi.SubmitMode.SYNC));
    assertEquals("transmitted", acc.status());
    assertEquals("QR", acc.qrCodeData());
    assertEquals("j.w.s", acc.signedJws());
    assertEquals("lb-00000000000000000000000000000000", t.lastApi().headers().get("idempotency-key"));
    assertEquals("mode=sync", t.lastApi().uri().getQuery());
  }

  @Test
  void dryRunModeWire() {
    FakeTransport t = new FakeTransport();
    t.enqueue(200, "{\"irn\":\"I-ABCDEF12-20260711\",\"status\":\"valid\"}");
    client(t).invoices().submit(Map.of(), InvoicesApi.SubmitOptions.withMode(InvoicesApi.SubmitMode.DRY_RUN));
    assertEquals("mode=dry_run", t.lastApi().uri().getQuery());
  }

  // ── invoices.get / list ────────────────────────────────────────────────

  @Test
  void getDecodesRecord() {
    FakeTransport t = new FakeTransport();
    t.enqueue(
        200,
        "{\"irn\":\"A-ABCDEF12-20260711\",\"status\":\"transmitted\",\"created_at\":\"2026-07-11T00:00:00Z\",\"signed_jws\":\"j.w.s\",\"unknown_field\":1}");
    InvoiceRecord rec = client(t).invoices().get("A-ABCDEF12-20260711");
    assertEquals("transmitted", rec.status());
    assertEquals("j.w.s", rec.signedJws());
  }

  @Test
  void listDecodesPage() {
    FakeTransport t = new FakeTransport();
    t.enqueue(
        200,
        "{\"data\":[{\"irn\":\"A-ABCDEF12-20260711\",\"status\":\"pending\"}],\"next_cursor\":\"abc\"}");
    InvoicePage page = client(t).invoices().list(null, 1, null);
    assertEquals(1, page.data().size());
    assertEquals("abc", page.nextCursor());
  }

  // ── invoices.transmit / updateStatus ───────────────────────────────────

  @Test
  void transmitSendsNoBody() {
    FakeTransport t = new FakeTransport();
    t.queue.add(new Transport.Response(202, Map.of(), new byte[0]));
    client(t).invoices().transmit("A-ABCDEF12-20260711");
    FakeTransport.Recorded r = t.lastApi();
    assertEquals("POST", r.method());
    assertTrue(r.uri().getPath().endsWith("/transmit"));
    assertNull(r.body(), "transmit must send no body");
    assertNull(r.headers().get("content-type"), "no content-type without a body");
  }

  @Test
  void updateStatusExactBody() {
    FakeTransport t = new FakeTransport();
    t.enqueue(200, "{\"irn\":\"A-ABCDEF12-20260711\",\"status\":\"transmitted\"}");
    client(t).invoices().updateStatus("A-ABCDEF12-20260711", "PAID", "RCPT-1");
    assertEquals("{\"payment_status\":\"PAID\",\"reference\":\"RCPT-1\"}", t.lastApi().bodyText());
  }

  @Test
  void updateStatusOmitsNullReference() {
    FakeTransport t = new FakeTransport();
    t.enqueue(200, "{\"irn\":\"A-ABCDEF12-20260711\",\"status\":\"transmitted\"}");
    client(t).invoices().updateStatus("A-ABCDEF12-20260711", "UNPAID", null);
    assertEquals("{\"payment_status\":\"UNPAID\"}", t.lastApi().bodyText());
  }

  // ── webhooks ───────────────────────────────────────────────────────────

  @Test
  void webhookCreateShapeAndSecretOnlyOnCreate() {
    FakeTransport t = new FakeTransport();
    t.enqueue(
        201,
        "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"url\":\"https://m.example/h\",\"events\":[\"invoice.transmitted\"],\"active\":true,\"secret\":\"whsec_x\",\"created_at\":\"2026-07-11T00:00:00Z\",\"updated_at\":\"2026-07-11T00:00:00Z\"}");
    Webhook w =
        client(t).webhooks().create("https://m.example/h", List.of("invoice.transmitted"), null);
    assertEquals("whsec_x", w.secret());
    String body = t.lastApi().bodyText();
    assertTrue(body.contains("\"url\":\"https://m.example/h\""));
    assertTrue(body.contains("\"events\":[\"invoice.transmitted\"]"));
    assertFalse(body.contains("description"), "null description omitted");
  }

  @Test
  void webhookEmptyEventsMeansAllAndIsPassedThrough() {
    FakeTransport t = new FakeTransport();
    t.enqueue(
        201,
        "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"url\":\"https://m.example/h\",\"events\":[],\"active\":true}");
    client(t).webhooks().create("https://m.example/h", List.of(), null);
    assertTrue(t.lastApi().bodyText().contains("\"events\":[]"));
  }

  @Test
  void webhookListUnwrapsDataEnvelope() {
    FakeTransport t = new FakeTransport();
    t.enqueue(
        200,
        "{\"data\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"url\":\"https://m.example/h\",\"events\":[\"invoice.failed\"],\"active\":true}]}");
    List<Webhook> hooks = client(t).webhooks().list();
    assertEquals(1, hooks.size());
    assertNull(hooks.get(0).secret(), "secret never present on reads");
  }

  @Test
  void webhookDelete() {
    FakeTransport t = new FakeTransport();
    t.queue.add(new Transport.Response(204, Map.of(), new byte[0]));
    client(t).webhooks().delete("11111111-1111-1111-1111-111111111111");
    assertEquals("DELETE", t.lastApi().method());
  }

  // ── lookups ────────────────────────────────────────────────────────────

  @Test
  void lookupsPaths() {
    FakeTransport t = new FakeTransport();
    LinkbridgeClient c = client(t);
    c.lookups().taxCodes();
    assertEquals("/v1/lookups/tax-codes", t.lastApi().uri().getPath());
    c.lookups().hsnCodes(10, null);
    assertEquals("/v1/lookups/hsn-codes", t.lastApi().uri().getPath());
    assertEquals("limit=10", t.lastApi().uri().getQuery());
  }
}
