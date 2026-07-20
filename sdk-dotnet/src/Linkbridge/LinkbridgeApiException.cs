using System.Text.Json;

namespace Linkbridge;

/// <summary>A non-success LinkBridge response or an invalid successful response.</summary>
public sealed class LinkbridgeApiException : Exception
{
    internal LinkbridgeApiException(
        int status,
        string code,
        string apiMessage,
        string? traceId,
        JsonElement? details,
        string rawBody,
        Exception? innerException = null)
        : base($"linkbridge: {status} {code}: {apiMessage}", innerException)
    {
        Status = status;
        Code = code;
        ApiMessage = apiMessage;
        TraceId = traceId;
        Details = details;
        RawBody = rawBody;
    }

    /// <summary>HTTP status, or zero for a post-response model conversion failure.</summary>
    public int Status { get; }

    /// <summary>Stable canonical error code.</summary>
    public string Code { get; }

    /// <summary>Human-readable API message.</summary>
    public string ApiMessage { get; }

    /// <summary>Server trace id, when supplied.</summary>
    public string? TraceId { get; }

    /// <summary>Structured error details, when supplied.</summary>
    public JsonElement? Details { get; }

    /// <summary>Raw response body.</summary>
    public string RawBody { get; }
}
