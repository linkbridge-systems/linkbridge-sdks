using System.Globalization;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Linkbridge;

/// <summary>
/// Async client for the LinkBridge e-invoicing API.
/// </summary>
public sealed class LinkbridgeClient : IDisposable
{
    public const string SdkVersion = "0.4.0";

    public static IReadOnlyList<string> DefaultScopes { get; } =
        Array.AsReadOnly<string>(["invoices:write", "invoices:read"]);

    internal static JsonSerializerOptions JsonOptions { get; } = new()
    {
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string _baseUrl;
    private readonly string? _clientId;
    private readonly string? _clientSecret;
    private readonly string? _staticToken;
    private readonly IReadOnlyList<string> _scopes;
    private readonly string _userAgent;
    private readonly HttpClient _httpClient;
    private readonly bool _ownsHttpClient;
    private readonly TimeProvider _timeProvider;
    private readonly SemaphoreSlim _tokenLock = new(1, 1);

    private string? _cachedToken;
    private DateTimeOffset _tokenExpiresAt;
    private bool _disposed;

    /// <summary>Create a client. Construction performs no network I/O.</summary>
    /// <param name="options">API, authentication, and timeout configuration.</param>
    /// <param name="httpClient">
    /// Optional caller-owned client. Inject one to integrate with IHttpClientFactory or to test
    /// with a custom message handler. Its timeout and handler policy are left unchanged.
    /// </param>
    public LinkbridgeClient(LinkbridgeClientOptions options, HttpClient? httpClient = null)
    {
        ArgumentNullException.ThrowIfNull(options);

        if (string.IsNullOrWhiteSpace(options.BaseUrl)
            || !Uri.TryCreate(options.BaseUrl, UriKind.Absolute, out var baseUri)
            || (baseUri.Scheme != Uri.UriSchemeHttps && baseUri.Scheme != Uri.UriSchemeHttp)
            || baseUri.Query.Length != 0
            || baseUri.Fragment.Length != 0
            || baseUri.UserInfo.Length != 0)
        {
            throw new ArgumentException(
                "linkbridge: base_url is required and must be an absolute HTTP(S) URL",
                nameof(options));
        }

        _staticToken = Normalize(options.StaticToken);
        _clientId = Normalize(options.ClientId);
        _clientSecret = Normalize(options.ClientSecret);
        if (_staticToken is null && (_clientId is null || _clientSecret is null))
        {
            throw new ArgumentException(
                "linkbridge: either static_token or client_id+client_secret is required",
                nameof(options));
        }

        _baseUrl = options.BaseUrl.TrimEnd('/');
        _scopes = NormalizeScopes(options.Scopes);
        _userAgent = "linkbridge-dotnet/" + SdkVersion;
        if (Normalize(options.UserAgent) is { } suffix)
        {
            _userAgent += " " + suffix;
        }

        _timeProvider = options.TimeProvider
            ?? throw new ArgumentException("linkbridge: time provider must not be null", nameof(options));

        if (httpClient is null)
        {
            if (options.Timeout <= TimeSpan.Zero && options.Timeout != Timeout.InfiniteTimeSpan)
            {
                throw new ArgumentOutOfRangeException(
                    nameof(options),
                    "linkbridge: timeout must be positive or infinite");
            }

            _httpClient = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
            {
                Timeout = options.Timeout,
            };
            _ownsHttpClient = true;
        }
        else
        {
            _httpClient = httpClient;
        }

        Invoices = new InvoicesClient(this);
        Webhooks = new WebhooksClient(this);
        Lookups = new LookupsClient(this);
    }

    public InvoicesClient Invoices { get; }

    public WebhooksClient Webhooks { get; }

    public LookupsClient Lookups { get; }

    /// <summary>Create an idempotency key shaped as lb- plus 32 lowercase hex characters.</summary>
    public static string IdempotencyKey()
    {
        return "lb-" + Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
    }

    /// <summary>Percent-encode one URL path segment, including any slash.</summary>
    public static string EncodePathSegment(string segment)
    {
        ArgumentNullException.ThrowIfNull(segment);
        return Uri.EscapeDataString(segment);
    }

    /// <summary>
    /// Authenticated JSON escape hatch for endpoints without a resource method.
    /// </summary>
    public Task<JsonElement?> RequestAsync(
        HttpMethod method,
        string path,
        IEnumerable<KeyValuePair<string, string?>>? query = null,
        object? body = null,
        IReadOnlyDictionary<string, string>? headers = null,
        CancellationToken cancellationToken = default)
    {
        return SendAsync(method, path, query, body, headers, authenticated: true, cancellationToken);
    }

    internal async Task<JsonElement?> SendAsync(
        HttpMethod method,
        string path,
        IEnumerable<KeyValuePair<string, string?>>? query,
        object? body,
        IReadOnlyDictionary<string, string>? headers,
        bool authenticated,
        CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        ArgumentNullException.ThrowIfNull(method);
        if (string.IsNullOrEmpty(path) || path[0] != '/')
        {
            throw new ArgumentException("linkbridge: path must start with '/'", nameof(path));
        }

        using var request = new HttpRequestMessage(method, BuildUri(path, query));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.TryAddWithoutValidation("User-Agent", _userAgent);

        if (authenticated)
        {
            request.Headers.Authorization =
                new AuthenticationHeaderValue("Bearer", await TokenAsync(cancellationToken).ConfigureAwait(false));
        }

        if (body is not null)
        {
            var bytes = JsonSerializer.SerializeToUtf8Bytes(body, body.GetType(), JsonOptions);
            request.Content = new ByteArrayContent(bytes);
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }

        if (headers is not null)
        {
            foreach (var (name, value) in headers)
            {
                if (name.Equals("Content-Type", StringComparison.OrdinalIgnoreCase) && request.Content is not null)
                {
                    request.Content.Headers.ContentType = MediaTypeHeaderValue.Parse(value);
                }
                else
                {
                    request.Headers.Remove(name);
                    request.Headers.TryAddWithoutValidation(name, value);
                }
            }
        }

        using var response = await _httpClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        var bodyBytes = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        var rawBody = Encoding.UTF8.GetString(bodyBytes);
        var status = (int)response.StatusCode;

        if (!response.IsSuccessStatusCode)
        {
            throw DecodeError(status, rawBody);
        }

        if (status == 204 || bodyBytes.Length == 0)
        {
            return null;
        }

        try
        {
            using var document = JsonDocument.Parse(bodyBytes);
            return document.RootElement.Clone();
        }
        catch (JsonException exception)
        {
            throw new LinkbridgeApiException(
                status,
                "invalid_json_response",
                "server returned non-JSON body",
                null,
                null,
                rawBody,
                exception);
        }
    }

    internal T Deserialize<T>(JsonElement? element)
    {
        if (element is null)
        {
            throw new LinkbridgeApiException(
                0,
                "invalid_json_response",
                "response body was empty",
                null,
                null,
                string.Empty);
        }

        try
        {
            return element.Value.Deserialize<T>(JsonOptions)
                ?? throw new JsonException("deserializer returned null");
        }
        catch (JsonException exception)
        {
            throw new LinkbridgeApiException(
                0,
                "invalid_json_response",
                "response did not match the expected shape",
                null,
                null,
                element.Value.GetRawText(),
                exception);
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _tokenLock.Dispose();
        if (_ownsHttpClient)
        {
            _httpClient.Dispose();
        }
    }

    private async Task<string> TokenAsync(CancellationToken cancellationToken)
    {
        if (_staticToken is not null)
        {
            return _staticToken;
        }

        await _tokenLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var now = _timeProvider.GetUtcNow();
            if (_cachedToken is not null && _tokenExpiresAt - now > TimeSpan.FromSeconds(60))
            {
                return _cachedToken;
            }

            var payload = new Dictionary<string, string>
            {
                ["client_id"] = _clientId!,
                ["client_secret"] = _clientSecret!,
                ["grant_type"] = "client_credentials",
                ["scope"] = string.Join(' ', _scopes),
            };
            var response = await SendAsync(
                HttpMethod.Post,
                "/v1/oauth/token",
                null,
                payload,
                null,
                authenticated: false,
                cancellationToken).ConfigureAwait(false);

            if (response is null
                || response.Value.ValueKind != JsonValueKind.Object
                || !response.Value.TryGetProperty("access_token", out var tokenElement)
                || tokenElement.ValueKind != JsonValueKind.String
                || string.IsNullOrEmpty(tokenElement.GetString()))
            {
                throw new LinkbridgeApiException(
                    200,
                    "invalid_token_response",
                    "empty access_token",
                    null,
                    null,
                    response?.GetRawText() ?? string.Empty);
            }

            var expiresIn = 0L;
            if (response.Value.TryGetProperty("expires_in", out var expiryElement)
                && expiryElement.TryGetInt64(out var parsedExpiry))
            {
                expiresIn = parsedExpiry;
            }
            if (expiresIn <= 0)
            {
                expiresIn = 300;
            }

            _cachedToken = tokenElement.GetString()!;
            _tokenExpiresAt = now.AddSeconds(expiresIn);
            return _cachedToken;
        }
        finally
        {
            _tokenLock.Release();
        }
    }

    private Uri BuildUri(
        string path,
        IEnumerable<KeyValuePair<string, string?>>? query)
    {
        var builder = new StringBuilder(_baseUrl).Append(path);
        var separator = '?';
        if (query is not null)
        {
            foreach (var (key, value) in query)
            {
                if (string.IsNullOrEmpty(value))
                {
                    continue;
                }

                builder
                    .Append(separator)
                    .Append(Uri.EscapeDataString(key))
                    .Append('=')
                    .Append(Uri.EscapeDataString(value));
                separator = '&';
            }
        }

        return new Uri(builder.ToString(), UriKind.Absolute);
    }

    private static LinkbridgeApiException DecodeError(int status, string rawBody)
    {
        try
        {
            using var document = JsonDocument.Parse(rawBody);
            if (document.RootElement.TryGetProperty("error", out var error)
                && error.ValueKind == JsonValueKind.Object)
            {
                var code = error.TryGetProperty("code", out var codeElement)
                    ? codeElement.GetString() ?? string.Empty
                    : string.Empty;
                var message = error.TryGetProperty("message", out var messageElement)
                    ? messageElement.GetString() ?? string.Empty
                    : string.Empty;
                var traceId = error.TryGetProperty("trace_id", out var traceElement)
                    && traceElement.ValueKind == JsonValueKind.String
                    ? traceElement.GetString()
                    : null;
                JsonElement? details = error.TryGetProperty("details", out var detailsElement)
                    ? detailsElement.Clone()
                    : null;

                return new LinkbridgeApiException(
                    status,
                    code,
                    message,
                    traceId,
                    details,
                    rawBody);
            }
        }
        catch (JsonException)
        {
            // Fall through to the opaque HTTP error.
        }

        var messageFallback = rawBody.Length == 0
            ? "http " + status.ToString(CultureInfo.InvariantCulture)
            : rawBody[..Math.Min(rawBody.Length, 500)];
        return new LinkbridgeApiException(
            status,
            "http_error",
            messageFallback,
            null,
            null,
            rawBody);
    }

    private static string? Normalize(string? value)
    {
        return string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    }

    private static IReadOnlyList<string> NormalizeScopes(IReadOnlyCollection<string>? scopes)
    {
        if (scopes is null || scopes.Count == 0)
        {
            return DefaultScopes;
        }

        var normalized = scopes
            .Where(scope => !string.IsNullOrWhiteSpace(scope))
            .Select(scope => scope.Trim())
            .ToArray();
        return normalized.Length == 0 ? DefaultScopes : Array.AsReadOnly(normalized);
    }
}
