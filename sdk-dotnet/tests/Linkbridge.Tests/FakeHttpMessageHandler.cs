using System.Collections.Concurrent;
using System.Net;
using System.Text;

namespace Linkbridge.Tests;

internal sealed record RecordedRequest(
    HttpMethod Method,
    Uri Uri,
    IReadOnlyDictionary<string, string> Headers,
    string? Body);

internal sealed class FakeHttpMessageHandler : HttpMessageHandler
{
    private readonly ConcurrentQueue<HttpResponseMessage> _responses = new();

    public ConcurrentQueue<RecordedRequest> Requests { get; } = new();

    public void Enqueue(HttpStatusCode status, string body = "")
    {
        _responses.Enqueue(new HttpResponseMessage(status)
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json"),
        });
    }

    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        var headerPairs = request.Content is null
            ? request.Headers.AsEnumerable()
            : request.Headers.Concat(request.Content.Headers);
        var headers = headerPairs
            .ToDictionary(
                pair => pair.Key,
                pair => string.Join(
                    pair.Key.Equals("User-Agent", StringComparison.OrdinalIgnoreCase) ? " " : ",",
                    pair.Value),
                StringComparer.OrdinalIgnoreCase);
        var body = request.Content is null
            ? null
            : await request.Content.ReadAsStringAsync(cancellationToken);
        Requests.Enqueue(new RecordedRequest(
            request.Method,
            request.RequestUri!,
            headers,
            body));

        if (!_responses.TryDequeue(out var response))
        {
            throw new InvalidOperationException("no fake response queued");
        }
        return response;
    }
}

internal sealed class FakeTimeProvider : TimeProvider
{
    public FakeTimeProvider(DateTimeOffset now)
    {
        Now = now;
    }

    public DateTimeOffset Now { get; set; }

    public override DateTimeOffset GetUtcNow() => Now;
}
