package ng.linkbridge.sdk;

/** A webhook delivery failed signature verification. */
public final class WebhookVerificationException extends RuntimeException {

  /** Why verification failed — mirror of the Node SDK's typed {@code reason}. */
  public enum Reason {
    /** Header absent or empty. */
    MISSING,
    /** Header present but unparseable (bad format, bad timestamp, missing v1). */
    MALFORMED,
    /** Timestamp outside the replay window. */
    EXPIRED,
    /** Digest differs (wrong secret, tampered body, or invalid hex). */
    MISMATCH
  }

  private final Reason reason;

  public WebhookVerificationException(Reason reason, String message) {
    super("linkbridge webhook: " + message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
