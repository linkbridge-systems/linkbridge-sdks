package ng.linkbridge.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Legacy async-only submission envelope. New code should use
 * {@link InvoiceSubmission}, which preserves both 202 and 200 response fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Deprecated(forRemoval = false)
public record InvoiceAccepted(
    @JsonProperty("irn") String irn,
    @JsonProperty("status") String status,
    @JsonProperty("tracking_url") String trackingUrl,
    @JsonProperty("source") String source) {}
