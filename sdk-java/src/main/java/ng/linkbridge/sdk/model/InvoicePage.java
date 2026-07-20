package ng.linkbridge.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One page of invoices; {@code nextCursor} is {@code null} on the last page. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvoicePage(
    @JsonProperty("data") List<InvoiceRecord> data,
    @JsonProperty("next_cursor") String nextCursor) {}
