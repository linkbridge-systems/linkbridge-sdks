package ng.linkbridge.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ClientTest {

  private static LinkbridgeClient.Builder base(FakeTransport t) {
    return LinkbridgeClient.builder()
        .baseUrl("https://api.example.test")
        .clientId("lb_test_abc")
        .clientSecret("secret")
        .transport(t);
  }

  // ── config validation ──────────────────────────────────────────────────

  @Test
  void baseUrlIsRequired() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> LinkbridgeClient.builder().clientId("a").clientSecret("b").build());
    assertTrue(e.getMessage().contains("base_url is required"));
  }

  @Test
  void credentialsOrStaticTokenRequired() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> LinkbridgeClient.builder().baseUrl("https://api.example.test").build());
    assertTrue(e.getMessage().contains("static_token or client_id+client_secret"));
  }

  @Test
  void trailingSlashesStripped() {
    FakeTransport t = new FakeTransport();
    LinkbridgeClient c =
        LinkbridgeClient.builder()
            .baseUrl("https://api.example.test///")
            .staticToken("tok")
            .transport(t)
            .build();
    c.request("GET", "/v1/invoices");
    assertEquals("https://api.example.test/v1/invoices", t.last().uri().toString());
  }

  // ── user agent ─────────────────────────────────────────────────────────

  @Test
  void userAgentDefaultsAndSuffix() {
    FakeTransport t = new FakeTransport();
    LinkbridgeClient plain = base(t).build();
    assertEquals("linkbridge-java/" + LinkbridgeClient.SDK_VERSION, plain.userAgentForTesting());

    LinkbridgeClient suffixed = base(t).userAgent("myapp/1.0").build();
    assertTrue(suffixed.userAgentForTesting().startsWith("linkbridge-java/"));
    assertTrue(suffixed.userAgentForTesting().endsWith(" myapp/1.0"));
  }

  // ── token flow ─────────────────────────────────────────────────────────

  @Test
  void tokenFetchedOnceAndCached() {
    FakeTransport t = new FakeTransport();
    LinkbridgeClient c = base(t).build();
    c.request("GET", "/v1/invoices");
    c.request("GET", "/v1/invoices");
    assertEquals(1, t.tokenCalls);
    assertEquals("Bearer tok-1", t.lastApi().headers().get("authorization"));
  }

  @Test
  void tokenRequestShape() {
    FakeTransport t = new FakeTransport();
    base(t).scopes(List.of("invoices:write", "webhooks:manage")).build().request("GET", "/v1/x");
    FakeTransport.Recorded tok = t.requests.get(0);
    assertEquals("/v1/oauth/token", tok.uri().getPath());
    assertEquals("POST", tok.method());
    assertNull(tok.headers().get("authorization"), "token request must carry no bearer");
    String body = tok.bodyText();
    assertTrue(body.contains("\"grant_type\":\"client_credentials\""));
    assertTrue(body.contains("\"client_id\":\"lb_test_abc\""));
    assertTrue(body.contains("\"scope\":\"invoices:write webhooks:manage\""), body);
  }

  @Test
  void defaultScopes() {
    FakeTransport t = new FakeTransport();
    base(t).build().request("GET", "/v1/x");
    assertTrue(t.requests.get(0).bodyText().contains("\"scope\":\"invoices:write invoices:read\""));
  }

  @Test
  void tokenRefreshesInsideSixtySecondWindow() {
    FakeTransport t = new FakeTransport();
    t.tokenResponse = "{\"access_token\":\"tok-1\",\"expires_in\":120}";
    LinkbridgeClient c = base(t).build();
    AtomicLong now = new AtomicLong(1_700_000_000L);
    c.setClockForTesting(InstantSource.fixed(Instant.ofEpochSecond(now.get())));

    c.request("GET", "/v1/x");
    assertEquals(1, t.tokenCalls);

    // 59s of validity left (<= 60) → must refresh.
    c.setClockForTesting(InstantSource.fixed(Instant.ofEpochSecond(now.get() + 61)));
    c.request("GET", "/v1/x");
    assertEquals(2, t.tokenCalls);
  }

  @Test
  void missingExpiresInFallsBackTo300() {
    FakeTransport t = new FakeTransport();
    t.tokenResponse = "{\"access_token\":\"tok-1\"}";
    LinkbridgeClient c = base(t).build();
    c.setClockForTesting(InstantSource.fixed(Instant.ofEpochSecond(1_700_000_000L)));
    c.request("GET", "/v1/x");
    // 300s fallback: at +200s there are 100s left (> 60) → still cached.
    c.setClockForTesting(InstantSource.fixed(Instant.ofEpochSecond(1_700_000_200L)));
    c.request("GET", "/v1/x");
    assertEquals(1, t.tokenCalls);
    // at +250s only 50s left → refresh.
    c.setClockForTesting(InstantSource.fixed(Instant.ofEpochSecond(1_700_000_250L)));
    c.request("GET", "/v1/x");
    assertEquals(2, t.tokenCalls);
  }

  @Test
  void staticTokenNeverCallsTokenEndpoint() {
    FakeTransport t = new FakeTransport();
    LinkbridgeClient c =
        LinkbridgeClient.builder()
            .baseUrl("https://api.example.test")
            .staticToken("static-tok")
            .transport(t)
            .build();
    c.request("GET", "/v1/invoices");
    c.request("GET", "/v1/webhooks");
    assertEquals(0, t.tokenCalls);
    assertEquals("Bearer static-tok", t.lastApi().headers().get("authorization"));
  }

  @Test
  void tokenRefreshIsSingleFlightUnderConcurrency() throws Exception {
    FakeTransport t = new FakeTransport();
    LinkbridgeClient c = base(t).build();
    int threads = 12;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var start = new java.util.concurrent.CountDownLatch(1);
    var done = new java.util.concurrent.CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              c.request("GET", "/v1/invoices");
            } catch (Exception ignored) {
              // fall through — the assertion below catches failures
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
    pool.shutdown();
    assertEquals(1, t.tokenCalls, "concurrent callers must coalesce onto one token fetch");
  }

  @Test
  void emptyAccessTokenIsInvalidTokenResponse() {
    FakeTransport t = new FakeTransport();
    t.tokenResponse = "{\"access_token\":\"\",\"expires_in\":3600}";
    LinkbridgeApiException e =
        assertThrows(LinkbridgeApiException.class, () -> base(t).build().request("GET", "/v1/x"));
    assertEquals("invalid_token_response", e.code());
    assertEquals("empty access_token", e.apiMessage());
  }

  // ── idempotency key ────────────────────────────────────────────────────

  @Test
  void idempotencyKeyFormatAndUniqueness() {
    String a = LinkbridgeClient.idempotencyKey();
    String b = LinkbridgeClient.idempotencyKey();
    assertTrue(a.matches("^lb-[0-9a-f]{32}$"), a);
    assertTrue(b.matches("^lb-[0-9a-f]{32}$"), b);
    assertNotEquals(a, b);
  }

  // ── query + path encoding ──────────────────────────────────────────────

  @Test
  void nullAndEmptyQueryValuesDropped() {
    FakeTransport t = new FakeTransport();
    t.enqueue(200, "{\"data\":[],\"next_cursor\":null}");
    base(t).build().invoices().list(null, 20, "");
    String q = t.lastApi().uri().getQuery();
    assertEquals("limit=20", q);
    assertFalse(t.lastApi().uri().toString().contains("cursor="));
    assertFalse(t.lastApi().uri().toString().contains("status="));
  }

  @Test
  void pathSegmentsFullyPercentEncoded() {
    assertEquals("INV%2F1%20x", LinkbridgeClient.encodePathSegment("INV/1 x"));
    FakeTransport t = new FakeTransport();
    t.enqueue(200, "{\"irn\":\"INV/1\",\"status\":\"pending\"}");
    base(t).build().invoices().get("INV/1");
    assertTrue(t.lastApi().uri().getRawPath().endsWith("/v1/invoices/INV%2F1"));
  }

  // ── response + error decoding ──────────────────────────────────────────

  @Test
  void errorEnvelopeDecoded() {
    FakeTransport t = new FakeTransport();
    t.enqueue(
        422,
        "{\"error\":{\"code\":\"schema_validation_failed\",\"message\":\"invoice payload does not satisfy the canonical schema\",\"details\":[\"/irn: bad\"],\"trace_id\":\"abc/def-000001\"}}");
    LinkbridgeApiException e =
        assertThrows(LinkbridgeApiException.class, () -> base(t).build().request("GET", "/v1/x"));
    assertEquals(422, e.status());
    assertEquals("schema_validation_failed", e.code());
    assertEquals("abc/def-000001", e.traceId());
    assertEquals("/irn: bad", e.details().get(0).asText());
    assertEquals(
        "linkbridge: 422 schema_validation_failed: invoice payload does not satisfy the canonical schema",
        e.getMessage());
  }

  @Test
  void nonJsonErrorBodyFallsBackToHttpError() {
    FakeTransport t = new FakeTransport();
    t.enqueue(502, "<html>Bad Gateway</html>");
    LinkbridgeApiException e =
        assertThrows(LinkbridgeApiException.class, () -> base(t).build().request("GET", "/v1/x"));
    assertEquals("http_error", e.code());
    assertEquals("<html>Bad Gateway</html>", e.apiMessage());
  }

  @Test
  void emptyErrorBodyMessageIsHttpStatus() {
    FakeTransport t = new FakeTransport();
    t.enqueue(503, "");
    LinkbridgeApiException e =
        assertThrows(LinkbridgeApiException.class, () -> base(t).build().request("GET", "/v1/x"));
    assertEquals("http_error", e.code());
    assertEquals("http 503", e.apiMessage());
  }

  @Test
  void nonJsonTwoxxBodyIsInvalidJsonResponse() {
    FakeTransport t = new FakeTransport();
    t.enqueue(200, "not-json");
    LinkbridgeApiException e =
        assertThrows(LinkbridgeApiException.class, () -> base(t).build().request("GET", "/v1/x"));
    assertEquals("invalid_json_response", e.code());
  }

  @Test
  void emptyBodyReturnsNull() {
    FakeTransport t = new FakeTransport();
    t.queue.add(new Transport.Response(204, Map.of(), new byte[0]));
    assertNull(base(t).build().request("DELETE", "/v1/webhooks/x"));
  }

  @Test
  void contentTypeOnlyWhenBodyPresent() {
    FakeTransport t = new FakeTransport();
    LinkbridgeClient c = base(t).build();
    c.request("GET", "/v1/invoices");
    assertNull(t.lastApi().headers().get("content-type"));
    c.request("POST", "/v1/webhooks", null, Map.of("url", "https://x.example"), null);
    assertEquals("application/json", t.lastApi().headers().get("content-type"));
  }
}
