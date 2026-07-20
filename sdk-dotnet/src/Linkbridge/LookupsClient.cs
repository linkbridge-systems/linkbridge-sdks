using System.Globalization;
using System.Text.Json;
using Linkbridge.Models;

namespace Linkbridge;

/// <summary>Reference-data lookups.</summary>
public sealed class LookupsClient
{
    private readonly LinkbridgeClient _client;

    internal LookupsClient(LinkbridgeClient client)
    {
        _client = client;
    }

    public async Task<JsonElement> TaxCodesAsync(
        bool? includeInactive = null,
        CancellationToken cancellationToken = default)
    {
        var query = new[]
        {
            KeyValuePair.Create<string, string?>(
                "include_inactive",
                includeInactive?.ToString().ToLowerInvariant()),
        };
        var response = await _client.SendAsync(
            HttpMethod.Get,
            "/v1/lookups/tax-codes",
            query,
            null,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
        return RequireJson(response);
    }

    public async Task<JsonElement> HsnCodesAsync(
        HsnLookupOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        options ??= new HsnLookupOptions();
        var query = new[]
        {
            KeyValuePair.Create<string, string?>("q", options.Query),
            KeyValuePair.Create<string, string?>("prefix", options.Prefix),
            KeyValuePair.Create<string, string?>("parent", options.Parent),
            KeyValuePair.Create<string, string?>("cursor", options.Cursor),
            KeyValuePair.Create<string, string?>(
                "limit",
                options.Limit?.ToString(CultureInfo.InvariantCulture)),
            KeyValuePair.Create<string, string?>(
                "include_inactive",
                options.IncludeInactive?.ToString().ToLowerInvariant()),
        };
        var response = await _client.SendAsync(
            HttpMethod.Get,
            "/v1/lookups/hsn-codes",
            query,
            null,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
        return RequireJson(response);
    }

    private static JsonElement RequireJson(JsonElement? response)
    {
        return response ?? throw new LinkbridgeApiException(
            0,
            "invalid_json_response",
            "response body was empty",
            null,
            null,
            string.Empty);
    }
}
