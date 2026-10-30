package ng.linkbridge.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lossless response from invoice submission. Async and sync-timeout responses
 * include {@code tracking_url}; completed sync and dry-run responses may
 * include posting, QR, and detached-JWS fields instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvoiceSubmission(
    @JsonProperty("irn") String irn,
    @JsonProperty("status") String status,
    @JsonProperty("tracking_url") String trackingUrl,
    @JsonProperty("source") String source,
    @JsonProperty("posting_datetime") String postingDatetime,
    @JsonProperty("qr_code_data") String qrCodeData,
    @JsonProperty("signed_jws") String signedJws) {}
