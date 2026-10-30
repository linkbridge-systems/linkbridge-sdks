namespace Linkbridge;

/// <summary>Configuration for <see cref="LinkbridgeClient"/>.</summary>
public sealed class LinkbridgeClientOptions
{
    /// <summary>
    /// LinkBridge API origin. Required; no localhost or environment default is assumed.
    /// </summary>
    public string? BaseUrl { get; init; }

    /// <summary>OAuth client id. Required with <see cref="ClientSecret"/> unless a static token is set.</summary>
    public string? ClientId { get; init; }

    /// <summary>OAuth client secret.</summary>
    public string? ClientSecret { get; init; }

    /// <summary>
    /// Pre-issued bearer token. When set, the SDK never calls the OAuth token endpoint.
    /// </summary>
    public string? StaticToken { get; init; }

    /// <summary>OAuth scopes. Defaults to invoices:write and invoices:read.</summary>
    public IReadOnlyCollection<string>? Scopes { get; init; }

    /// <summary>Optional application identifier appended to the SDK user agent.</summary>
    public string? UserAgent { get; init; }

    /// <summary>Timeout used by the SDK-created <see cref="HttpClient"/>. Defaults to 30 seconds.</summary>
    public TimeSpan Timeout { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>Clock used for token expiry and webhook tests. Defaults to the system clock.</summary>
    public TimeProvider TimeProvider { get; init; } = TimeProvider.System;
}
