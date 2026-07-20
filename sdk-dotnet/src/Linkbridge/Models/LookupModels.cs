namespace Linkbridge.Models;

/// <summary>Optional filters for the HSN catalogue.</summary>
public sealed record HsnLookupOptions
{
    public string? Query { get; init; }

    public string? Prefix { get; init; }

    public string? Parent { get; init; }

    public string? Cursor { get; init; }

    public int? Limit { get; init; }

    public bool? IncludeInactive { get; init; }
}
