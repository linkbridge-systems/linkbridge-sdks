package ng.linkbridge.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** A stored invoice as returned by {@code GET /v1/invoices/{irn}} and list pages. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvoiceRecord(
    @JsonProperty("irn") String irn,
    @JsonProperty("status") String status,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    @JsonProperty("posting_datetime") String postingDatetime,
    @JsonProperty("signed_jws") String signedJws,
    @JsonProperty("qr_code_data") String qrCodeData,
    @JsonProperty("nrs_response") JsonNode nrsResponse,
    @JsonProperty("payload") JsonNode payload) {}
