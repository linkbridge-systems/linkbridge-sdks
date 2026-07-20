using System.Text.Json;
using System.Text.Json.Serialization;

namespace Linkbridge.Models;

/// <summary>
/// Lossless common response from asynchronous, synchronous, or dry-run invoice submission.
/// </summary>
public sealed record InvoiceSubmission
{
    [JsonPropertyName("irn")]
    public string Irn { get; init; } = string.Empty;

    [JsonPropertyName("status")]
    public string Status { get; init; } = string.Empty;

    [JsonPropertyName("tracking_url")]
    public string? TrackingUrl { get; init; }

    [JsonPropertyName("source")]
    public string? Source { get; init; }

    [JsonPropertyName("posting_datetime")]
    public string? PostingDatetime { get; init; }

    [JsonPropertyName("qr_code_data")]
    public string? QrCodeData { get; init; }

    [JsonPropertyName("signed_jws")]
    public string? SignedJws { get; init; }

    [JsonExtensionData]
    public IDictionary<string, JsonElement>? AdditionalProperties { get; init; }
}

/// <summary>A stored invoice returned by get and list operations.</summary>
public sealed record InvoiceRecord
{
    [JsonPropertyName("irn")]
    public string Irn { get; init; } = string.Empty;

    [JsonPropertyName("status")]
    public string Status { get; init; } = string.Empty;

    [JsonPropertyName("created_at")]
    public string? CreatedAt { get; init; }

    [JsonPropertyName("updated_at")]
    public string? UpdatedAt { get; init; }

    [JsonPropertyName("posting_datetime")]
    public string? PostingDatetime { get; init; }

    [JsonPropertyName("signed_jws")]
    public string? SignedJws { get; init; }

    [JsonPropertyName("qr_code_data")]
    public string? QrCodeData { get; init; }

    [JsonPropertyName("nrs_response")]
    public JsonElement? NrsResponse { get; init; }

    [JsonPropertyName("payload")]
    public JsonElement? Payload { get; init; }

    [JsonExtensionData]
    public IDictionary<string, JsonElement>? AdditionalProperties { get; init; }
}

/// <summary>One page from the invoice listing endpoint.</summary>
public sealed record InvoicePage
{
    [JsonPropertyName("data")]
    public IReadOnlyList<InvoiceRecord> Data { get; init; } = [];

    [JsonPropertyName("next_cursor")]
    public string? NextCursor { get; init; }
}

/// <summary>Submission processing mode. This is separate from test/live credential mode.</summary>
public enum InvoiceSubmitMode
{
    Async,
    Sync,
    DryRun,
}

/// <summary>Optional controls for an invoice submission.</summary>
public sealed record InvoiceSubmitOptions
{
    public string? IdempotencyKey { get; init; }

    public InvoiceSubmitMode? Mode { get; init; }
}

/// <summary>Optional invoice list filters.</summary>
public sealed record InvoiceListOptions
{
    public string? Cursor { get; init; }

    public int? Limit { get; init; }

    public string? Status { get; init; }
}
