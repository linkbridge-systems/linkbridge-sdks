package ng.linkbridge.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reference-data lookups (mode-agnostic): FIRS tax codes and WCO HS codes. */
public final class LookupsApi {

  private final LinkbridgeClient client;

  LookupsApi(LinkbridgeClient client) {
    this.client = client;
  }

  /** {@code GET /v1/lookups/tax-codes}. Returned as loose JSON (reference data). */
  public JsonNode taxCodes() {
    return client.sendJson("GET", "/v1/lookups/tax-codes", null, null, null, true);
  }

  /** {@code GET /v1/lookups/hsn-codes}; both parameters optional ({@code null} to omit). */
  public JsonNode hsnCodes(Integer limit, String cursor) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("limit", limit == null ? null : String.valueOf(limit));
    query.put("cursor", cursor);
    return client.sendJson("GET", "/v1/lookups/hsn-codes", query, null, null, true);
  }
}
