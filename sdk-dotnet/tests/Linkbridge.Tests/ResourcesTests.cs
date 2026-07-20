using System.Net;
using System.Text.Json;
using Linkbridge.Models;

namespace Linkbridge.Tests;

public sealed class ResourcesTests
{
    [Fact]
    public async Task SubmitGeneratesIdempotencyKeyAndSerializesMode()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(
            HttpStatusCode.OK,
            """{"irn":"INV-ABCDEF12-20260717","status":"transmitted","signed_jws":"j.w.s"}""");
        using var httpClient = new HttpClient(handler);
        using var client = Client(httpClient);

        var result = await client.Invoices.SubmitAsync(
            new { irn = "INV-ABCDEF12-20260717" },
            new InvoiceSubmitOptions { Mode = InvoiceSubmitMode.Sync });

        Assert.Equal("j.w.s", result.SignedJws);
        var request = Assert.Single(handler.Requests);
        Assert.Equal("mode=sync", request.Uri.Query.TrimStart('?'));
        Assert.Matches("^lb-[0-9a-f]{32}$", request.Headers["Idempotency-Key"]);
        Assert.Equal("application/json", request.Headers["Content-Type"]);
    }

    [Fact]
    public async Task SubmitCallerKeyWinsAndDryRunUsesWireValue()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """{"irn":"I-1","status":"valid"}""");
        using var httpClient = new HttpClient(handler);
        using var client = Client(httpClient);

        await client.Invoices.SubmitAsync(
            new { irn = "I-1" },
            new InvoiceSubmitOptions
            {
                IdempotencyKey = "lb-00000000000000000000000000000000",
                Mode = InvoiceSubmitMode.DryRun,
            });

        var request = Assert.Single(handler.Requests);
        Assert.Equal("lb-00000000000000000000000000000000", request.Headers["Idempotency-Key"]);
        Assert.Equal("mode=dry_run", request.Uri.Query.TrimStart('?'));
    }

    [Fact]
    public async Task GetAndListDecodeModelsAndEncodePaths()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(
            HttpStatusCode.OK,
            """{"irn":"INV/1","status":"transmitted","unknown_field":1}""");
        handler.Enqueue(
            HttpStatusCode.OK,
            """{"data":[{"irn":"INV-2","status":"pending"}],"next_cursor":"next"}""");
        using var httpClient = new HttpClient(handler);
        using var client = Client(httpClient);

        var invoice = await client.Invoices.GetAsync("INV/1");
        var page = await client.Invoices.ListAsync(new InvoiceListOptions
        {
            Cursor = "",
            Limit = 20,
            Status = "pending",
        });

        Assert.Equal("transmitted", invoice.Status);
        Assert.EndsWith("/v1/invoices/INV%2F1", handler.Requests.First().Uri.AbsoluteUri);
        Assert.Single(page.Data);
        Assert.Equal("next", page.NextCursor);
        Assert.Equal("limit=20&status=pending", handler.Requests.Last().Uri.Query.TrimStart('?'));
    }

    [Fact]
    public async Task TransmitHasNoBodyAndStatusOmitsNullReference()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.Accepted);
        handler.Enqueue(HttpStatusCode.OK, """{"irn":"INV-1","status":"transmitted"}""");
        using var httpClient = new HttpClient(handler);
        using var client = Client(httpClient);

        await client.Invoices.TransmitAsync("INV-1");
        await client.Invoices.UpdateStatusAsync("INV-1", "UNPAID");

        var requests = handler.Requests.ToArray();
        Assert.Null(requests[0].Body);
        Assert.False(requests[0].Headers.ContainsKey("Content-Type"));
        using var statusBody = JsonDocument.Parse(requests[1].Body!);
        Assert.Equal("UNPAID", statusBody.RootElement.GetProperty("payment_status").GetString());
        Assert.False(statusBody.RootElement.TryGetProperty("reference", out _));
    }

    [Fact]
    public async Task WebhookCreateListAndDeleteMatchContract()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(
            HttpStatusCode.Created,
            """{"id":"wh-1","url":"https://merchant.test/hook","events":[],"active":true,"secret":"whsec_x"}""");
        handler.Enqueue(
            HttpStatusCode.OK,
            """{"data":[{"id":"wh-1","url":"https://merchant.test/hook","events":["invoice.failed"],"active":true}]}""");
        handler.Enqueue(HttpStatusCode.NoContent);
        using var httpClient = new HttpClient(handler);
        using var client = Client(httpClient);

        var created = await client.Webhooks.CreateAsync(
            "https://merchant.test/hook",
            [],
            "ERP events");
        var listed = await client.Webhooks.ListAsync();
        await client.Webhooks.DeleteAsync("wh/1");

        Assert.Equal("whsec_x", created.Secret);
        Assert.Single(listed);
        Assert.Null(listed[0].Secret);
        using var createBody = JsonDocument.Parse(handler.Requests.First().Body!);
        Assert.Equal("ERP events", createBody.RootElement.GetProperty("description").GetString());
        Assert.Equal(
            "/v1/webhooks/wh%2F1",
            handler.Requests.Last().Uri.AbsolutePath);
    }

    [Fact]
    public async Task LookupOptionsUseExactWireNames()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """[]""");
        handler.Enqueue(HttpStatusCode.OK, """{"data":[]}""");
        using var httpClient = new HttpClient(handler);
        using var client = Client(httpClient);

        await client.Lookups.TaxCodesAsync(includeInactive: true);
        await client.Lookups.HsnCodesAsync(new HsnLookupOptions
        {
            Query = "solar panel",
            Prefix = "85",
            Limit = 10,
            IncludeInactive = false,
        });

        var requests = handler.Requests.ToArray();
        Assert.Equal("include_inactive=true", requests[0].Uri.Query.TrimStart('?'));
        Assert.Equal(
            "q=solar%20panel&prefix=85&limit=10&include_inactive=false",
            requests[1].Uri.Query.TrimStart('?'));
    }

    [Fact]
    public void EventConstantsMatchOpenApi()
    {
        Assert.Equal(
            [
                "invoice.submitted",
                "invoice.signed",
                "invoice.transmitted",
                "invoice.failed",
                "webhook.test",
            ],
            WebhookEvents.All.ToArray());
        Assert.DoesNotContain("webhook.test", WebhookEvents.Subscribable);
        Assert.DoesNotContain("invoice.accepted", WebhookEvents.All);
    }

    private static LinkbridgeClient Client(HttpClient httpClient)
    {
        return new LinkbridgeClient(
            new LinkbridgeClientOptions
            {
                BaseUrl = "https://api.example.test",
                StaticToken = "token",
            },
            httpClient);
    }
}
