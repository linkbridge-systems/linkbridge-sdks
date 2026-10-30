package ng.linkbridge.sdk;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ng.linkbridge.sdk.WebhookVerificationException.Reason;

/**
 * HMAC signature verification for Linkbridge webhook deliveries.
 *
 * <p>Every delivery carries:
 *
 * <pre>X-Linkbridge-Signature: t=&lt;unix-seconds&gt;,v1=&lt;hex&gt;</pre>
 *
 * where {@code v1 = HMAC-SHA256(secret, "{t}" + "." + body)}. Receivers MUST
 * verify against the raw request body bytes exactly as read off the wire —
 * never re-serialized JSON — and reject timestamps outside a 5-minute replay
 * window.
 *
 * <p>This verifier reproduces the audited cross-SDK contract bit-for-bit
 * (see {@code audit/threat-model/stride-analysis.md}, T-23): the same test
 * vectors pass in the Go, Node, Python, PHP and Java suites.
 */
public final class Webhooks {

  /** Header carrying the signature (lookup should be case-insensitive). */
  public static final String SIGNATURE_HEADER = "X-Linkbridge-Signature";

  /** Maximum allowed clock skew, seconds. Exactly this old is still accepted. */
  public static final long MAX_WEBHOOK_SKEW_SECONDS = 300;

  private Webhooks() {}

  /** {@link #verify(byte[], byte[], String, Long, Long)} with the system clock and default tolerance. */
  public static void verify(byte[] secret, byte[] body, String signatureHeader) {
    verify(secret, body, signatureHeader, null, null);
  }

  /**
   * Verify a webhook delivery. Returns normally on success; throws
   * {@link WebhookVerificationException} with a typed {@code reason} on any
   * failure.
   *
   * @param secret the webhook signing secret (from the create response), UTF-8 bytes
   * @param body the raw request body bytes, exactly as read off the wire
   * @param signatureHeader the {@code X-Linkbridge-Signature} header value
   * @param nowEpochSeconds test/clock override; {@code null} = system clock
   * @param toleranceSeconds replay-window override; {@code null} = {@link #MAX_WEBHOOK_SKEW_SECONDS}
   */
  public static void verify(
      byte[] secret, byte[] body, String signatureHeader, Long nowEpochSeconds, Long toleranceSeconds) {
    if (secret == null || secret.length == 0) {
      throw new IllegalArgumentException("linkbridge webhook: secret must not be empty");
    }
    if (body == null) {
      throw new IllegalArgumentException("linkbridge webhook: body must not be null");
    }
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new WebhookVerificationException(Reason.MISSING, "missing " + SIGNATURE_HEADER);
    }

    long t = -1;
    String v1 = null;
    for (String part : signatureHeader.split(",")) {
      part = part.trim();
      int eq = part.indexOf('='); // split on the FIRST '=' — the value may contain '='
      if (eq < 0) {
        throw new WebhookVerificationException(Reason.MALFORMED, "malformed signature header");
      }
      String key = part.substring(0, eq).trim();
      String value = part.substring(eq + 1).trim();
      switch (key) {
        case "t" -> {
          try {
            t = Long.parseLong(value);
          } catch (NumberFormatException e) {
            throw new WebhookVerificationException(Reason.MALFORMED, "malformed signature timestamp");
          }
          if (t <= 0) {
            throw new WebhookVerificationException(Reason.MALFORMED, "malformed signature timestamp");
          }
        }
        case "v1" -> v1 = value;
        default -> {
          // Unknown keys (e.g. a future v2=...) are tolerated for forward compat.
        }
      }
    }
    if (t < 0 || v1 == null || v1.isEmpty()) {
      throw new WebhookVerificationException(Reason.MALFORMED, "malformed signature header");
    }

    long now = nowEpochSeconds != null ? nowEpochSeconds : Instant.now().getEpochSecond();
    long tolerance = toleranceSeconds != null ? toleranceSeconds : MAX_WEBHOOK_SKEW_SECONDS;
    // Strictly greater-than: a signature exactly `tolerance` old is accepted.
    if (Math.abs(now - t) > tolerance) {
      throw new WebhookVerificationException(
          Reason.EXPIRED, "signature timestamp outside replay window");
    }

    byte[] expected = hmacSha256(secret, t, body);
    // Non-hex / wrong-length v1 classifies as MISMATCH (not MALFORMED),
    // matching the Node SDK's pinned behavior. Uppercase hex is accepted.
    byte[] provided = hexDecodeOrNull(v1.toLowerCase(java.util.Locale.ROOT));
    if (provided == null || !MessageDigest.isEqual(expected, provided)) {
      throw new WebhookVerificationException(Reason.MISMATCH, "signature mismatch");
    }
  }

  private static byte[] hmacSha256(byte[] secret, long t, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      // The HMAC input is the DECIMAL timestamp string, a literal dot, then
      // the raw body bytes — re-rendered from the parsed integer like every
      // sibling SDK.
      mac.update(Long.toString(t).getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) '.');
      mac.update(body);
      return mac.doFinal();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("linkbridge webhook: HmacSHA256 unavailable", e);
    }
  }

  private static byte[] hexDecodeOrNull(String hex) {
    if (hex.length() % 2 != 0) {
      return null;
    }
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = asciiHexDigit(hex.charAt(2 * i));
      int lo = asciiHexDigit(hex.charAt(2 * i + 1));
      if (hi < 0 || lo < 0) {
        return null;
      }
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  // ASCII-only on purpose: Character.digit() also accepts non-ASCII Unicode
  // digits (e.g. Arabic-Indic '٣'), which every sibling SDK's verifier
  // rejects — the wire contract is lowercase ASCII hex.
  private static int asciiHexDigit(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return -1;
  }
}
