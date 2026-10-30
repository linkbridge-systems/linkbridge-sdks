using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace Linkbridge;

public enum WebhookVerificationFailure
{
    Missing,
    Malformed,
    Expired,
    Mismatch,
}

/// <summary>A webhook delivery failed signature verification.</summary>
public sealed class WebhookVerificationException : Exception
{
    internal WebhookVerificationException(WebhookVerificationFailure reason, string message)
        : base("linkbridge webhook: " + message)
    {
        Reason = reason;
    }

    public WebhookVerificationFailure Reason { get; }
}

/// <summary>HMAC-SHA256 verification for raw LinkBridge webhook deliveries.</summary>
public static class WebhookVerifier
{
    public const string SignatureHeader = "X-Linkbridge-Signature";
    public const long MaxSkewSeconds = 300;

    public static void Verify(
        string secret,
        string body,
        string? signatureHeader,
        long? nowEpochSeconds = null,
        long toleranceSeconds = MaxSkewSeconds)
    {
        ArgumentNullException.ThrowIfNull(body);
        Verify(
            Encoding.UTF8.GetBytes(secret ?? string.Empty),
            Encoding.UTF8.GetBytes(body),
            signatureHeader,
            nowEpochSeconds,
            toleranceSeconds);
    }

    public static void Verify(
        ReadOnlySpan<byte> secret,
        ReadOnlySpan<byte> body,
        string? signatureHeader,
        long? nowEpochSeconds = null,
        long toleranceSeconds = MaxSkewSeconds)
    {
        if (secret.IsEmpty)
        {
            throw new ArgumentException("linkbridge webhook: secret must not be empty", nameof(secret));
        }
        if (toleranceSeconds < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(toleranceSeconds));
        }
        if (string.IsNullOrWhiteSpace(signatureHeader))
        {
            throw new WebhookVerificationException(
                WebhookVerificationFailure.Missing,
                "missing " + SignatureHeader);
        }

        long timestamp = -1;
        string? signature = null;
        foreach (var rawPart in signatureHeader.Split(','))
        {
            var part = rawPart.Trim();
            var equals = part.IndexOf('=');
            if (equals < 0)
            {
                throw Malformed("malformed signature header");
            }

            var key = part[..equals].Trim();
            var value = part[(equals + 1)..].Trim();
            if (key == "t")
            {
                if (!IsAsciiPositiveInteger(value)
                    || !long.TryParse(
                        value,
                        NumberStyles.None,
                        CultureInfo.InvariantCulture,
                        out timestamp)
                    || timestamp <= 0)
                {
                    throw Malformed("malformed signature timestamp");
                }
            }
            else if (key == "v1")
            {
                signature = value;
            }
        }

        if (timestamp <= 0 || string.IsNullOrEmpty(signature))
        {
            throw Malformed("malformed signature header");
        }

        var now = nowEpochSeconds ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (decimal.Abs((decimal)now - timestamp) > toleranceSeconds)
        {
            throw new WebhookVerificationException(
                WebhookVerificationFailure.Expired,
                "signature timestamp outside replay window");
        }

        var prefix = Encoding.ASCII.GetBytes(
            timestamp.ToString(CultureInfo.InvariantCulture) + ".");
        var message = new byte[prefix.Length + body.Length];
        prefix.CopyTo(message, 0);
        body.CopyTo(message.AsSpan(prefix.Length));

        byte[] expected;
        using (var hmac = new HMACSHA256(secret.ToArray()))
        {
            expected = hmac.ComputeHash(message);
        }

        var provided = DecodeAsciiHex(signature);
        if (provided is null || !CryptographicOperations.FixedTimeEquals(expected, provided))
        {
            throw new WebhookVerificationException(
                WebhookVerificationFailure.Mismatch,
                "signature mismatch");
        }
    }

    private static WebhookVerificationException Malformed(string message)
    {
        return new WebhookVerificationException(WebhookVerificationFailure.Malformed, message);
    }

    private static bool IsAsciiPositiveInteger(string value)
    {
        if (value.Length == 0)
        {
            return false;
        }
        foreach (var character in value)
        {
            if (character is < '0' or > '9')
            {
                return false;
            }
        }
        return true;
    }

    private static byte[]? DecodeAsciiHex(string value)
    {
        if (value.Length % 2 != 0)
        {
            return null;
        }

        var bytes = new byte[value.Length / 2];
        for (var index = 0; index < bytes.Length; index++)
        {
            var high = HexDigit(value[index * 2]);
            var low = HexDigit(value[(index * 2) + 1]);
            if (high < 0 || low < 0)
            {
                return null;
            }
            bytes[index] = (byte)((high << 4) | low);
        }
        return bytes;
    }

    private static int HexDigit(char value)
    {
        return value switch
        {
            >= '0' and <= '9' => value - '0',
            >= 'a' and <= 'f' => value - 'a' + 10,
            >= 'A' and <= 'F' => value - 'A' + 10,
            _ => -1,
        };
    }
}
