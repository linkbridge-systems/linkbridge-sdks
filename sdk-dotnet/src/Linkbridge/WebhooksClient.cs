using Linkbridge.Models;

namespace Linkbridge;

/// <summary>Webhook subscription management.</summary>
public sealed class WebhooksClient
{
    private readonly LinkbridgeClient _client;

    internal WebhooksClient(LinkbridgeClient client)
    {
        _client = client;
    }

    /// <summary>
    /// Register a webhook. Store the returned Secret immediately; it is shown only once.
    /// </summary>
    public async Task<WebhookSubscription> CreateAsync(
        string url,
        IReadOnlyCollection<string>? events = null,
        string? description = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(url);
        var body = new Dictionary<string, object>
        {
            ["url"] = url,
            ["events"] = events ?? Array.Empty<string>(),
        };
        if (description is not null)
        {
            body["description"] = description;
        }

        var response = await _client.SendAsync(
            HttpMethod.Post,
            "/v1/webhooks",
            null,
            body,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
        return _client.Deserialize<WebhookSubscription>(response);
    }

    public async Task<IReadOnlyList<WebhookSubscription>> ListAsync(
        CancellationToken cancellationToken = default)
    {
        var response = await _client.SendAsync(
            HttpMethod.Get,
            "/v1/webhooks",
            null,
            null,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
        return _client.Deserialize<WebhookPage>(response).Data;
    }

    public async Task DeleteAsync(
        string id,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(id);
        await _client.SendAsync(
            HttpMethod.Delete,
            "/v1/webhooks/" + LinkbridgeClient.EncodePathSegment(id),
            null,
            null,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
    }
}
