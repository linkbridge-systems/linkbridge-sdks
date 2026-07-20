using System.Globalization;
using System.Text.Json;
using Linkbridge.Models;

namespace Linkbridge;

/// <summary>Invoice submission, retrieval, listing, retransmission, and status updates.</summary>
public sealed class InvoicesClient
{
    private readonly LinkbridgeClient _client;

    internal InvoicesClient(LinkbridgeClient client)
    {
        _client = client;
    }

    /// <summary>
    /// Submit a canonical invoice. An idempotency key is generated when none is supplied.
    /// </summary>
    public async Task<InvoiceSubmission> SubmitAsync(
        object invoice,
        InvoiceSubmitOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(invoice);
        options ??= new InvoiceSubmitOptions();
        var key = string.IsNullOrWhiteSpace(options.IdempotencyKey)
            ? LinkbridgeClient.IdempotencyKey()
            : options.IdempotencyKey;
        var query = options.Mode is null
            ? null
            : new[] { KeyValuePair.Create<string, string?>("mode", ModeValue(options.Mode.Value)) };
        var response = await _client.SendAsync(
            HttpMethod.Post,
            "/v1/invoices",
            query,
            invoice,
            new Dictionary<string, string> { ["Idempotency-Key"] = key },
            authenticated: true,
            cancellationToken).ConfigureAwait(false);

        return _client.Deserialize<InvoiceSubmission>(response);
    }

    public async Task<InvoiceRecord> GetAsync(
        string irn,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(irn);
        var response = await _client.SendAsync(
            HttpMethod.Get,
            "/v1/invoices/" + LinkbridgeClient.EncodePathSegment(irn),
            null,
            null,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
        return _client.Deserialize<InvoiceRecord>(response);
    }

    public async Task<InvoicePage> ListAsync(
        InvoiceListOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        options ??= new InvoiceListOptions();
        var query = new[]
        {
            KeyValuePair.Create<string, string?>("cursor", options.Cursor),
            KeyValuePair.Create<string, string?>(
                "limit",
                options.Limit?.ToString(CultureInfo.InvariantCulture)),
            KeyValuePair.Create<string, string?>("status", options.Status),
        };
        var response = await _client.SendAsync(
            HttpMethod.Get,
            "/v1/invoices",
            query,
            null,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
        return _client.Deserialize<InvoicePage>(response);
    }

    public async Task TransmitAsync(
        string irn,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(irn);
        await _client.SendAsync(
            HttpMethod.Post,
            "/v1/invoices/" + LinkbridgeClient.EncodePathSegment(irn) + "/transmit",
            null,
            null,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
    }

    public async Task<InvoiceRecord> UpdateStatusAsync(
        string irn,
        string paymentStatus,
        string? reference = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(irn);
        ArgumentException.ThrowIfNullOrWhiteSpace(paymentStatus);
        var body = new Dictionary<string, string>
        {
            ["payment_status"] = paymentStatus,
        };
        if (reference is not null)
        {
            body["reference"] = reference;
        }

        var response = await _client.SendAsync(
            HttpMethod.Post,
            "/v1/invoices/" + LinkbridgeClient.EncodePathSegment(irn) + "/status",
            null,
            body,
            null,
            authenticated: true,
            cancellationToken).ConfigureAwait(false);
        return _client.Deserialize<InvoiceRecord>(response);
    }

    private static string ModeValue(InvoiceSubmitMode mode)
    {
        return mode switch
        {
            InvoiceSubmitMode.Async => "async",
            InvoiceSubmitMode.Sync => "sync",
            InvoiceSubmitMode.DryRun => "dry_run",
            _ => throw new ArgumentOutOfRangeException(nameof(mode)),
        };
    }
}
