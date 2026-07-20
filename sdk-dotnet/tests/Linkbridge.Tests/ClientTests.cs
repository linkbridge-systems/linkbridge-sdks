using System.Net;
using System.Text.Json;
using Linkbridge.Models;

namespace Linkbridge.Tests;

public sealed class ClientTests
{
    [Fact]
    public void ConstructorRequiresBaseUrlAndCredentials()
    {
        Assert.Throws<ArgumentException>(() => new LinkbridgeClient(
            new LinkbridgeClientOptions { StaticToken = "token" }));
        Assert.Throws<ArgumentException>(() => new LinkbridgeClient(
            new LinkbridgeClientOptions { BaseUrl = "https://api.example.test" }));
        Assert.Throws<ArgumentException>(() => new LinkbridgeClient(
            new LinkbridgeClientOptions { BaseUrl = "file:///tmp/x", StaticToken = "token" }));
        Assert.Throws<ArgumentException>(() => new LinkbridgeClient(
            new LinkbridgeClientOptions
            {
                BaseUrl = "https://api.example.test?redirect=bad",
                StaticToken = "token",
            }));
    }

    [Fact]
    public async Task StaticTokenSkipsOauthAndUserAgentIncludesSuffix()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """{"data":[],"next_cursor":null}""");
        using var httpClient = new HttpClient(handler);
        using var client = new LinkbridgeClient(
            new LinkbridgeClientOptions
            {
                BaseUrl = "https://api.example.test/",
                StaticToken = "static-token",
                UserAgent = "merchant-app/1",
            },
            httpClient);

        await client.Invoices.ListAsync();

        var request = Assert.Single(handler.Requests);
        Assert.Equal("/v1/invoices", request.Uri.AbsolutePath);
        Assert.Equal("Bearer static-token", request.Headers["Authorization"]);
        Assert.Equal(
            "linkbridge-dotnet/0.4.0 merchant-app/1",
            request.Headers["User-Agent"]);
    }

    [Fact]
    public async Task OAuthTokenIsCachedAcrossCalls()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """{"access_token":"tok-1","expires_in":3600}""");
        handler.Enqueue(HttpStatusCode.OK, """{"data":[]}""");
        handler.Enqueue(HttpStatusCode.OK, """{"data":[]}""");
        using var httpClient = new HttpClient(handler);
        using var client = OAuthClient(httpClient);

        await client.Invoices.ListAsync();
        await client.Invoices.ListAsync();

        var requests = handler.Requests.ToArray();
        Assert.Equal(3, requests.Length);
        Assert.Equal("/v1/oauth/token", requests[0].Uri.AbsolutePath);
        Assert.Equal("Bearer tok-1", requests[1].Headers["Authorization"]);
        Assert.Equal("Bearer tok-1", requests[2].Headers["Authorization"]);
        using var tokenBody = JsonDocument.Parse(requests[0].Body!);
        Assert.Equal("client_credentials", tokenBody.RootElement.GetProperty("grant_type").GetString());
        Assert.Equal(
            "invoices:write invoices:read",
            tokenBody.RootElement.GetProperty("scope").GetString());
    }

    [Fact]
    public async Task ConcurrentCallsCoalesceTokenRefresh()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """{"access_token":"tok","expires_in":3600}""");
        for (var index = 0; index < 8; index++)
        {
            handler.Enqueue(HttpStatusCode.OK, """{"data":[]}""");
        }
        using var httpClient = new HttpClient(handler);
        using var client = OAuthClient(httpClient);

        await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => client.Invoices.ListAsync()));

        Assert.Equal(
            1,
            handler.Requests.Count(request => request.Uri.AbsolutePath == "/v1/oauth/token"));
        Assert.Equal(9, handler.Requests.Count);
    }

    [Fact]
    public async Task TokenWithoutExpiryUsesFallbackAndRefreshesInsideLeeway()
    {
        var clock = new FakeTimeProvider(DateTimeOffset.FromUnixTimeSeconds(1_700_000_000));
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """{"access_token":"tok-1"}""");
        handler.Enqueue(HttpStatusCode.OK, """{"data":[]}""");
        handler.Enqueue(HttpStatusCode.OK, """{"data":[]}""");
        handler.Enqueue(HttpStatusCode.OK, """{"access_token":"tok-2","expires_in":300}""");
        handler.Enqueue(HttpStatusCode.OK, """{"data":[]}""");
        using var httpClient = new HttpClient(handler);
        using var client = new LinkbridgeClient(
            new LinkbridgeClientOptions
            {
                BaseUrl = "https://api.example.test",
                ClientId = "cid",
                ClientSecret = "secret",
                TimeProvider = clock,
            },
            httpClient);

        await client.Invoices.ListAsync();
        clock.Now = clock.Now.AddSeconds(239);
        await client.Invoices.ListAsync();
        clock.Now = clock.Now.AddSeconds(2);
        await client.Invoices.ListAsync();

        Assert.Equal(
            2,
            handler.Requests.Count(request => request.Uri.AbsolutePath == "/v1/oauth/token"));
    }

    [Fact]
    public async Task NonObjectTokenResponseIsTypedError()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """["not","a","token"]""");
        using var httpClient = new HttpClient(handler);
        using var client = OAuthClient(httpClient);

        var error = await Assert.ThrowsAsync<LinkbridgeApiException>(
            () => client.Invoices.ListAsync());

        Assert.Equal("invalid_token_response", error.Code);
    }

    [Fact]
    public async Task CanonicalAndOpaqueErrorsAreDecoded()
    {
        var canonicalHandler = new FakeHttpMessageHandler();
        canonicalHandler.Enqueue(
            HttpStatusCode.UnprocessableEntity,
            """{"error":{"code":"schema_validation_failed","message":"bad invoice","trace_id":"trace-1","details":["/irn: bad"]}}""");
        using var canonicalHttp = new HttpClient(canonicalHandler);
        using var canonicalClient = StaticClient(canonicalHttp);

        var canonical = await Assert.ThrowsAsync<LinkbridgeApiException>(
            () => canonicalClient.RequestAsync(HttpMethod.Get, "/v1/x"));
        Assert.Equal(422, canonical.Status);
        Assert.Equal("schema_validation_failed", canonical.Code);
        Assert.Equal("trace-1", canonical.TraceId);
        Assert.NotNull(canonical.Details);
        Assert.Equal("/irn: bad", canonical.Details.Value[0].GetString());

        var opaqueHandler = new FakeHttpMessageHandler();
        opaqueHandler.Enqueue(HttpStatusCode.BadGateway, "<html>bad gateway</html>");
        using var opaqueHttp = new HttpClient(opaqueHandler);
        using var opaqueClient = StaticClient(opaqueHttp);
        var opaque = await Assert.ThrowsAsync<LinkbridgeApiException>(
            () => opaqueClient.RequestAsync(HttpMethod.Get, "/v1/x"));
        Assert.Equal("http_error", opaque.Code);
        Assert.Equal("<html>bad gateway</html>", opaque.ApiMessage);
    }

    [Fact]
    public async Task SuccessfulNonJsonBodyIsTypedError()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, "not-json");
        using var httpClient = new HttpClient(handler);
        using var client = StaticClient(httpClient);

        var error = await Assert.ThrowsAsync<LinkbridgeApiException>(
            () => client.RequestAsync(HttpMethod.Get, "/v1/x"));

        Assert.Equal(200, error.Status);
        Assert.Equal("invalid_json_response", error.Code);
    }

    [Fact]
    public async Task EscapeHatchEncodesQueryAndRejectsAbsoluteUrl()
    {
        var handler = new FakeHttpMessageHandler();
        handler.Enqueue(HttpStatusCode.OK, """{"ok":true}""");
        using var httpClient = new HttpClient(handler);
        using var client = StaticClient(httpClient);

        var response = await client.RequestAsync(
            HttpMethod.Get,
            "/v1/search",
            [
                KeyValuePair.Create<string, string?>("q", "VAT & levy"),
                KeyValuePair.Create<string, string?>("empty", ""),
                KeyValuePair.Create<string, string?>("missing", null),
            ]);

        Assert.True(response?.GetProperty("ok").GetBoolean());
        Assert.Equal("q=VAT%20%26%20levy", Assert.Single(handler.Requests).Uri.Query.TrimStart('?'));
        await Assert.ThrowsAsync<ArgumentException>(
            () => client.RequestAsync(HttpMethod.Get, "https://evil.example/x"));
    }

    [Fact]
    public void IdempotencyAndPathHelpersArePinned()
    {
        var key = LinkbridgeClient.IdempotencyKey();
        Assert.Matches("^lb-[0-9a-f]{32}$", key);
        Assert.NotEqual(key, LinkbridgeClient.IdempotencyKey());
        Assert.Equal("INV%2F1%20x", LinkbridgeClient.EncodePathSegment("INV/1 x"));
    }

    private static LinkbridgeClient StaticClient(HttpClient httpClient)
    {
        return new LinkbridgeClient(
            new LinkbridgeClientOptions
            {
                BaseUrl = "https://api.example.test",
                StaticToken = "token",
            },
            httpClient);
    }

    private static LinkbridgeClient OAuthClient(HttpClient httpClient)
    {
        return new LinkbridgeClient(
            new LinkbridgeClientOptions
            {
                BaseUrl = "https://api.example.test",
                ClientId = "cid",
                ClientSecret = "secret",
            },
            httpClient);
    }
}
