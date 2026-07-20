package ng.linkbridge.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A webhook subscription. {@code secret} is populated ONLY on the create
 * response — store it then; it is never returned again.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Webhook(
    @JsonProperty("id") String id,
    @JsonProperty("url") String url,
    @JsonProperty("events") List<String> events,
    @JsonProperty("description") String description,
    @JsonProperty("active") boolean active,
    @JsonProperty("secret") String secret,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt) {}
