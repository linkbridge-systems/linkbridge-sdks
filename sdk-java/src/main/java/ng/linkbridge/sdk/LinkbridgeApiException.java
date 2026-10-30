package ng.linkbridge.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A non-2xx response from the Linkbridge API, decoded from the canonical
 * error envelope {@code {"error": {"code", "message", "details", "trace_id"}}}.
 *
 * <p>Non-envelope responses (e.g. an HTML 502 from a load balancer) never
 * crash the decoder: {@code code} falls back to {@code "http_error"} and the
 * message carries the first 500 characters of the body.
 */
public final class LinkbridgeApiException extends RuntimeException {

  private final int status;
  private final String code;
  private final String apiMessage;
  private final String traceId;
  private final transient JsonNode details;
  private final String raw;

  public LinkbridgeApiException(
      int status, String code, String apiMessage, String traceId, JsonNode details, String raw) {
    // Message format pinned to the Python/PHP majority: "linkbridge: {status} {code}: {message}".
    super("linkbridge: " + status + " " + code + ": " + apiMessage);
    this.status = status;
    this.code = code;
    this.apiMessage = apiMessage;
    this.traceId = traceId;
    this.details = details;
    this.raw = raw;
  }

  /** HTTP status code. */
  public int status() {
    return status;
  }

  /** Canonical error code (e.g. {@code validation_error}), or {@code http_error} for non-envelope bodies. */
  public String code() {
    return code;
  }

  /** The {@code error.message} field (without the "linkbridge: …" prefix). */
  public String apiMessage() {
    return apiMessage;
  }

  /** The {@code error.trace_id} field, or {@code null}. */
  public String traceId() {
    return traceId;
  }

  /** The {@code error.details} field as loosely-typed JSON, or {@code null}. */
  public JsonNode details() {
    return details;
  }

  /** The raw response body, for debugging. */
  public String raw() {
    return raw;
  }
}
