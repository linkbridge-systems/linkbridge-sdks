package ng.linkbridge.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Official Java client for the Linkbridge e-invoicing API.
 *
 * <p>Mirrors the behavioral contract of the sibling SDKs (Go/Node/Python/PHP):
 * OAuth2 client-credentials with an in-memory token cache refreshed 60 seconds
 * before expiry (single-flight under concurrency), an {@code Idempotency-Key}
 * helper, the canonical error envelope decoded into
 * {@link LinkbridgeApiException}, and an injectable {@link Transport} for
 * hermetic tests. The constructor performs no network I/O — the first
 * authenticated call fetches the token lazily.
 *
 * <pre>{@code
 * LinkbridgeClient client = LinkbridgeClient.builder()
 *     .baseUrl("https://api.linkbridge.ng")
 *     .clientId(System.getenv("LB_CLIENT_ID"))
 *     .clientSecret(System.getenv("LB_CLIENT_SECRET"))
 *     .build();
 * InvoiceSubmission accepted = client.invoices().submit(invoice);
 * }</pre>
 */
public final class LinkbridgeClient {

  /** SDK version, kept in lockstep with pom.xml and the sibling SDKs. */
  public static final String SDK_VERSION = "0.4.0";

  /** Scopes requested when none are configured. */
  public static final List<String> DEFAULT_SCOPES = List.of("invoices:write", "invoices:read");

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String baseUrl;
  private final String clientId;
  private final String clientSecret;
  private final String staticToken;
  private final List<String> scopes;
  private final String userAgent;
  private final Transport transport;

  private final InvoicesApi invoices;
  private final WebhooksApi webhooks;
  private final LookupsApi lookups;

  // Token cache. Guarded by tokenLock: concurrent callers block on a single
  // in-flight refresh, matching the Python lock / Node promise-coalescing.
  private final Object tokenLock = new Object();
  private String cachedToken;
  private long tokenExpiresAtEpochSeconds;

  // Injectable clock so tests can drive the early-refresh window.
  private InstantSource clock = InstantSource.system();

  private LinkbridgeClient(Builder b) {
    if (b.baseUrl == null || b.baseUrl.isBlank()) {
      // No default on purpose: a localhost fallback was a prod footgun.
      throw new IllegalArgumentException("linkbridge: base_url is required (e.g. https://api.linkbridge.ng)");
    }
    boolean hasCreds = notBlank(b.clientId) && notBlank(b.clientSecret);
    if (!notBlank(b.staticToken) && !hasCreds) {
      throw new IllegalArgumentException(
          "linkbridge: either static_token or client_id+client_secret is required");
    }
    this.baseUrl = b.baseUrl.replaceAll("/+$", "");
    this.clientId = b.clientId;
    this.clientSecret = b.clientSecret;
    this.staticToken = notBlank(b.staticToken) ? b.staticToken : null;
    this.scopes = b.scopes == null || b.scopes.isEmpty() ? DEFAULT_SCOPES : List.copyOf(b.scopes);
    String base = "linkbridge-java/" + SDK_VERSION;
    // User config is a SUFFIX appended to the SDK identifier, per Python/Node.
    this.userAgent = notBlank(b.userAgent) ? base + " " + b.userAgent.trim() : base;
    this.transport = b.transport != null ? b.transport : new HttpClientTransport();
    this.invoices = new InvoicesApi(this);
    this.webhooks = new WebhooksApi(this);
    this.lookups = new LookupsApi(this);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Invoice submission, retrieval, listing, retransmission, status updates. */
  public InvoicesApi invoices() {
    return invoices;
  }

  /** Webhook subscription management. */
  public WebhooksApi webhooks() {
    return webhooks;
  }

  /** Reference-data lookups (tax codes, HS codes). */
  public LookupsApi lookups() {
    return lookups;
  }

  /**
   * A fresh idempotency key: {@code "lb-"} + 32 lowercase hex characters
   * (16 random bytes). Successive calls always differ.
   */
  public static String idempotencyKey() {
    byte[] buf = new byte[16];
    RANDOM.nextBytes(buf);
    StringBuilder sb = new StringBuilder("lb-");
    for (byte x : buf) {
      sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
    }
    return sb.toString();
  }

  // ── generic request escape hatch ───────────────────────────────────────

  /**
   * Perform an authenticated JSON request. This is the public escape hatch for
   * endpoints the typed resource groups don't wrap (e.g. {@code /v1/crypto},
   * bulk NDJSON submission via your own encoding).
   *
   * @param method HTTP method, e.g. {@code "POST"}
   * @param path path starting with {@code /}, e.g. {@code "/v1/crypto"} —
   *     embed user input via {@link #encodePathSegment(String)}
   * @param query query parameters; {@code null} values and empty strings are dropped
   * @param body JSON-serializable request body, or {@code null} for none
   * @param extraHeaders extra request headers, or {@code null}
   * @return the decoded JSON response, or {@code null} on 204 / empty body
   * @throws LinkbridgeApiException on any non-2xx response
   */
  public JsonNode request(
      String method,
      String path,
      Map<String, String> query,
      Object body,
      Map<String, String> extraHeaders) {
    return sendJson(method, path, query, body, extraHeaders, true);
  }

  /** {@code request(method, path, null, null, null)}. */
  public JsonNode request(String method, String path) {
    return request(method, path, null, null, null);
  }

  /** Percent-encode a path segment (all reserved characters, including {@code /}). */
  public static String encodePathSegment(String segment) {
    StringBuilder sb = new StringBuilder(segment.length());
    for (byte x : segment.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (x & 0xFF);
      boolean unreserved =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '.'
              || c == '_'
              || c == '~';
      if (unreserved) {
        sb.append(c);
      } else {
        sb.append('%').append(String.format(Locale.ROOT, "%02X", x & 0xFF));
      }
    }
    return sb.toString();
  }

  // ── internals ──────────────────────────────────────────────────────────

  JsonNode sendJson(
      String method,
      String path,
      Map<String, String> query,
      Object body,
      Map<String, String> extraHeaders,
      boolean authenticated) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("accept", "application/json");
    headers.put("user-agent", userAgent);
    if (authenticated) {
      headers.put("authorization", "Bearer " + token());
    }
    byte[] bodyBytes = null;
    if (body != null) {
      headers.put("content-type", "application/json");
      try {
        bodyBytes = JSON.writeValueAsBytes(body);
      } catch (IOException e) {
        throw new UncheckedIOException("linkbridge: failed to encode request body", e);
      }
    }
    if (extraHeaders != null) {
      extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
    }

    Transport.Response resp = doSend(method, path, query, headers, bodyBytes);
    return decode(resp);
  }

  private Transport.Response doSend(
      String method,
      String path,
      Map<String, String> query,
      Map<String, String> headers,
      byte[] body) {
    StringBuilder url = new StringBuilder(baseUrl).append(path);
    if (query != null) {
      StringBuilder qs = new StringBuilder();
      for (Map.Entry<String, String> e : query.entrySet()) {
        // Drop null and empty-string values entirely (pinned across SDKs).
        if (e.getValue() == null || e.getValue().isEmpty()) {
          continue;
        }
        if (qs.length() > 0) {
          qs.append('&');
        }
        qs.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
            .append('=')
            .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
      }
      if (qs.length() > 0) {
        url.append('?').append(qs);
      }
    }
    try {
      return transport.send(
          new Transport.Request(method, URI.create(url.toString()), headers, body));
    } catch (IOException e) {
      throw new UncheckedIOException("linkbridge: request failed: " + method + " " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("linkbridge: request interrupted", e);
    }
  }

  private JsonNode decode(Transport.Response resp) {
    String bodyText = new String(resp.body(), StandardCharsets.UTF_8);
    if (resp.status() / 100 != 2) {
      throw decodeError(resp.status(), bodyText);
    }
    if (resp.status() == 204 || bodyText.isEmpty()) {
      return null;
    }
    try {
      return JSON.readTree(bodyText);
    } catch (IOException e) {
      throw new LinkbridgeApiException(
          resp.status(), "invalid_json_response", "server returned non-JSON body", null, null, bodyText);
    }
  }

  static LinkbridgeApiException decodeError(int status, String bodyText) {
    try {
      JsonNode root = JSON.readTree(bodyText);
      JsonNode err = root == null ? null : root.get("error");
      if (err != null && err.isObject()) {
        String code = err.path("code").asText("");
        String message = err.path("message").asText("");
        String traceId = err.hasNonNull("trace_id") ? err.get("trace_id").asText() : null;
        JsonNode details = err.hasNonNull("details") ? err.get("details") : null;
        return new LinkbridgeApiException(status, code, message, traceId, details, bodyText);
      }
    } catch (IOException ignored) {
      // fall through to the non-envelope path
    }
    String message =
        bodyText.isEmpty()
            ? "http " + status
            : bodyText.substring(0, Math.min(bodyText.length(), 500));
    return new LinkbridgeApiException(status, "http_error", message, null, null, bodyText);
  }

  <T> T convert(JsonNode node, Class<T> type) {
    if (node == null) {
      // 204 / empty 2xx body — mirror the sibling SDKs, which return None/null.
      return null;
    }
    try {
      return JSON.treeToValue(node, type);
    } catch (IOException e) {
      // Status 0: the HTTP exchange succeeded (some 2xx); the failure is a
      // shape mismatch between the response and this SDK's model.
      throw new LinkbridgeApiException(
          0, "invalid_json_response", "response did not match the expected shape: " + e.getMessage(),
          null, null, String.valueOf(node));
    }
  }

  // ── OAuth token flow ───────────────────────────────────────────────────

  private String token() {
    if (staticToken != null) {
      return staticToken; // never calls /v1/oauth/token
    }
    synchronized (tokenLock) {
      long now = clock.instant().getEpochSecond();
      // Reuse only while more than 60s of validity remains.
      if (cachedToken != null && tokenExpiresAtEpochSeconds - now > 60) {
        return cachedToken;
      }
      ObjectNode body = JSON.createObjectNode();
      body.put("client_id", clientId);
      body.put("client_secret", clientSecret);
      body.put("grant_type", "client_credentials");
      body.put("scope", String.join(" ", scopes));

      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("accept", "application/json");
      headers.put("user-agent", userAgent);
      headers.put("content-type", "application/json");
      byte[] bodyBytes;
      try {
        bodyBytes = JSON.writeValueAsBytes(body);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      Transport.Response resp = doSend("POST", "/v1/oauth/token", null, headers, bodyBytes);
      String text = new String(resp.body(), StandardCharsets.UTF_8);
      if (resp.status() / 100 != 2) {
        throw decodeError(resp.status(), text);
      }
      JsonNode decoded;
      try {
        decoded = JSON.readTree(text);
      } catch (IOException e) {
        throw new LinkbridgeApiException(
            resp.status(), "invalid_token_response", "non-JSON token response", null, null, text);
      }
      String accessToken = decoded.path("access_token").asText("");
      if (accessToken.isEmpty()) {
        throw new LinkbridgeApiException(
            resp.status(), "invalid_token_response", "empty access_token", null, null, text);
      }
      long expiresIn = decoded.path("expires_in").asLong(0);
      if (expiresIn <= 0) {
        expiresIn = 300; // fallback when the server omits it
      }
      cachedToken = accessToken;
      tokenExpiresAtEpochSeconds = now + expiresIn;
      return cachedToken;
    }
  }

  // Test seam: lets the suite drive the early-refresh window without sleeping.
  void setClockForTesting(InstantSource clock) {
    this.clock = clock;
  }

  String userAgentForTesting() {
    return userAgent;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  static Map<String, String> mapOfNonNull(String k1, String v1, String k2, String v2) {
    Map<String, String> m = new HashMap<>();
    if (v1 != null) {
      m.put(k1, v1);
    }
    if (v2 != null) {
      m.put(k2, v2);
    }
    return m;
  }

  // ── builder ────────────────────────────────────────────────────────────

  public static final class Builder {
    private String baseUrl;
    private String clientId;
    private String clientSecret;
    private String staticToken;
    private List<String> scopes;
    private String userAgent;
    private Transport transport;

    /** Required. The single Linkbridge API host, e.g. {@code https://api.linkbridge.ng}. */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder clientId(String clientId) {
      this.clientId = clientId;
      return this;
    }

    public Builder clientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
      return this;
    }

    /** Bypass OAuth entirely with a pre-issued bearer token. */
    public Builder staticToken(String staticToken) {
      this.staticToken = staticToken;
      return this;
    }

    /** OAuth scopes; defaults to {@link #DEFAULT_SCOPES}. */
    public Builder scopes(List<String> scopes) {
      this.scopes = scopes;
      return this;
    }

    /** Appended to the SDK User-Agent, e.g. {@code "myapp/1.0"}. */
    public Builder userAgent(String userAgent) {
      this.userAgent = userAgent;
      return this;
    }

    /** Custom transport (tests, proxies). */
    public Builder transport(Transport transport) {
      this.transport = transport;
      return this;
    }

    public LinkbridgeClient build() {
      return new LinkbridgeClient(this);
    }
  }
}
