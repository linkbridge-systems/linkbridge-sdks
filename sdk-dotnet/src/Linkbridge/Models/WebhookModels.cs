using System.Text.Json;
using System.Text.Json.Serialization;

namespace Linkbridge.Models;

/// <summary>A webhook subscription. Secret is populated only on the create response.</summary>
public sealed record WebhookSubscription
{
    [JsonPropertyName("id")]
    public string Id { get; init; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; init; } = string.Empty;

    [JsonPropertyName("events")]
    public IReadOnlyList<string> Events { get; init; } = [];

    [JsonPropertyName("description")]
    public string? Description { get; init; }

    [JsonPropertyName("active")]
    public bool Active { get; init; }

    [JsonPropertyName("secret")]
    public string? Secret { get; init; }

    [JsonPropertyName("created_at")]
    public string? CreatedAt { get; init; }

    [JsonPropertyName("updated_at")]
    public string? UpdatedAt { get; init; }

    [JsonExtensionData]
    public IDictionary<string, JsonElement>? AdditionalProperties { get; init; }
}

internal sealed record WebhookPage
{
    [JsonPropertyName("data")]
    public IReadOnlyList<WebhookSubscription> Data { get; init; } = [];
}

/// <summary>Webhook event constants pinned to the OpenAPI subscription enum.</summary>
public static class WebhookEvents
{
    public static IReadOnlyList<string> All { get; } = Array.AsReadOnly<string>(
    [
        "invoice.submitted",
        "invoice.signed",
        "invoice.transmitted",
        "invoice.failed",
        "webhook.test",
    ]);

    public static IReadOnlyList<string> Subscribable { get; } = Array.AsReadOnly<string>(
    [
        "invoice.submitted",
        "invoice.signed",
        "invoice.transmitted",
        "invoice.failed",
    ]);
}
