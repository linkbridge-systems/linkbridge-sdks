package ng.linkbridge.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import ng.linkbridge.sdk.model.InvoicePage;
import ng.linkbridge.sdk.model.InvoiceRecord;
import ng.linkbridge.sdk.model.InvoiceSubmission;

/** Invoice submission, retrieval, listing, retransmission and payment-status updates. */
public final class InvoicesApi {

  /** Processing mode for {@code POST /v1/invoices} (distinct from test/live key mode). */
  public enum SubmitMode {
    ASYNC("async"),
    SYNC("sync"),
    DRY_RUN("dry_run");

    private final String wire;

    SubmitMode(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }
  }

  /**
   * Options for {@link #submit}. {@code idempotencyKey} overrides the
   * auto-generated key; {@code mode} is sent as {@code ?mode=} only when set
   * (async is the server default).
   */
  public record SubmitOptions(String idempotencyKey, SubmitMode mode) {
    public static SubmitOptions defaults() {
      return new SubmitOptions(null, null);
    }

    public static SubmitOptions withMode(SubmitMode mode) {
      return new SubmitOptions(null, mode);
    }

    public static SubmitOptions withIdempotencyKey(String key) {
      return new SubmitOptions(key, null);
    }
  }

  private final LinkbridgeClient client;

  InvoicesApi(LinkbridgeClient client) {
    this.client = client;
  }

  /**
   * Submit a canonical invoice document. An {@code Idempotency-Key} is
   * auto-generated when the options don't supply one.
   *
   * <p>Pass a {@code Map<String, Object>} or a Jackson {@code JsonNode} that
   * conforms to the canonical invoice schema. The server rejects unknown or
   * missing fields even though this Java wrapper deliberately accepts a generic
   * JSON value.
   */
  public InvoiceSubmission submit(Object invoice, SubmitOptions options) {
    SubmitOptions opts = options == null ? SubmitOptions.defaults() : options;
    String key = opts.idempotencyKey() != null ? opts.idempotencyKey() : LinkbridgeClient.idempotencyKey();
    Map<String, String> query = new LinkedHashMap<>();
    if (opts.mode() != null) {
      query.put("mode", opts.mode().wire());
    }
    JsonNode resp =
        client.sendJson("POST", "/v1/invoices", query, invoice, Map.of("idempotency-key", key), true);
    return client.convert(resp, InvoiceSubmission.class);
  }

  /** {@link #submit(Object, SubmitOptions)} with default options. */
  public InvoiceSubmission submit(Object invoice) {
    return submit(invoice, null);
  }

  /** Fetch one invoice by IRN. */
  public InvoiceRecord get(String irn) {
    JsonNode resp =
        client.sendJson(
            "GET", "/v1/invoices/" + LinkbridgeClient.encodePathSegment(irn), null, null, null, true);
    return client.convert(resp, InvoiceRecord.class);
  }

  /**
   * List invoices. All parameters are optional; pass {@code null} to omit.
   *
   * @param cursor opaque pagination cursor from {@link InvoicePage#nextCursor()}
   * @param limit page size (server default 50, max 200)
   * @param status filter, e.g. {@code "transmitted"}
   */
  public InvoicePage list(String cursor, Integer limit, String status) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("cursor", cursor);
    query.put("limit", limit == null ? null : String.valueOf(limit));
    query.put("status", status);
    JsonNode resp = client.sendJson("GET", "/v1/invoices", query, null, null, true);
    return client.convert(resp, InvoicePage.class);
  }

  /** List the first page with server defaults. */
  public InvoicePage list() {
    return list(null, null, null);
  }

  /**
   * Manually re-queue a terminally-failed invoice for transmission
   * ({@code POST /v1/invoices/{irn}/transmit}, no request body).
   */
  public void transmit(String irn) {
    client.sendJson(
        "POST",
        "/v1/invoices/" + LinkbridgeClient.encodePathSegment(irn) + "/transmit",
        null,
        null,
        null,
        true);
  }

  /**
   * Update the payment status of a submitted invoice.
   *
   * @param paymentStatus one of {@code PAID | PARTIALLY_PAID | UNPAID | CANCELLED}
   * @param reference optional payment reference, e.g. a receipt number
   */
  public InvoiceRecord updateStatus(String irn, String paymentStatus, String reference) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payment_status", paymentStatus);
    if (reference != null) {
      body.put("reference", reference);
    }
    JsonNode resp =
        client.sendJson(
            "POST",
            "/v1/invoices/" + LinkbridgeClient.encodePathSegment(irn) + "/status",
            null,
            body,
            null,
            true);
    return client.convert(resp, InvoiceRecord.class);
  }
}
