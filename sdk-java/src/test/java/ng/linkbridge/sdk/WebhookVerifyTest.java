package ng.linkbridge.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import ng.linkbridge.sdk.WebhookVerificationException.Reason;
import org.junit.jupiter.api.Test;

/**
 * Webhook signature verification, pinned to the audited cross-SDK contract
 * (audit/threat-model/stride-analysis.md T-23): the vectors below are copied
 * VERBATIM from the Python, Node, and PHP suites so all seven SDKs prove the
 * same bytes. Do not regenerate them.
 */
class WebhookVerifyTest {

  private static final long NOW = 1_700_000_000L;

  private static byte[] b(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static Reason reasonOf(Runnable r) {
    return assertThrows(WebhookVerificationException.class, r::run).reason();
  }

  // ── Python suite vectors (tests/test_webhook.py) ───────────────────────

  private static final String PY_SECRET = "shhh-it-is-a-secret";
  private static final String PY_BODY = "{\"event\":\"invoice.accepted\",\"data\":{\"irn\":\"INV-1\"}}";
  private static final String PY_HEADER =
      "t=1700000000,v1=c3d95840ab1eec96bdf88f1e3d5fce825cce4e172278c088e20148a8f979c627";

  @Test
  void pythonVector_happyPath() {
    assertDoesNotThrow(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), PY_HEADER, NOW, null));
  }

  @Test
  void pythonVector_unknownKeysTolerated() {
    assertDoesNotThrow(
        () -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), PY_HEADER + ",v2=ignored", NOW, null));
  }

  @Test
  void pythonVector_uppercaseHexAccepted() {
    String upper = PY_HEADER.replace(
        "c3d95840ab1eec96bdf88f1e3d5fce825cce4e172278c088e20148a8f979c627",
        "C3D95840AB1EEC96BDF88F1E3D5FCE825CCE4E172278C088E20148A8F979C627");
    assertDoesNotThrow(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), upper, NOW, null));
  }

  @Test
  void pythonVector_replayRejectedAt301() {
    assertEquals(
        Reason.EXPIRED,
        reasonOf(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), PY_HEADER, NOW + 301, null)));
  }

  @Test
  void pythonVector_wrongSecretMismatch() {
    assertEquals(
        Reason.MISMATCH,
        reasonOf(() -> Webhooks.verify(b("different-secret"), b(PY_BODY), PY_HEADER, NOW, null)));
  }

  @Test
  void pythonVector_missingAndMalformed() {
    assertEquals(Reason.MISSING, reasonOf(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), "", NOW, null)));
    assertEquals(
        Reason.MALFORMED, reasonOf(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), "garbage", NOW, null)));
    assertEquals(
        Reason.MALFORMED,
        reasonOf(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), "t=-1,v1=abc", NOW, null)));
  }

  // ── Node suite vectors (src/webhook.test.ts) ───────────────────────────

  private static final String NODE_SECRET = "shh";
  private static final String NODE_BODY = "{\"event\":\"invoice.accepted\"}";
  private static final String NODE_HEADER =
      "t=1700000000,v1=9cadac3f4eadeb10ab430d08eaced69eb8f871ab775271576e20f2baddcca814";

  @Test
  void nodeVector_happyPath() {
    assertDoesNotThrow(() -> Webhooks.verify(b(NODE_SECRET), b(NODE_BODY), NODE_HEADER, NOW, null));
  }

  @Test
  void nodeVector_boundaryExactly300sOldAccepted() {
    String header =
        "t=1699999700,v1=153fb2eca9b47a7945b5e610b4a0f370d0f5c328c14e30836f5852fd2c0ee9f0";
    assertDoesNotThrow(() -> Webhooks.verify(b(NODE_SECRET), b(NODE_BODY), header, NOW, null));
  }

  @Test
  void nodeVector_malformedSet() {
    for (String header : new String[] {"abc", "t=,v1=ff", "t=abc,v1=ff", "t=1700000000", "v1=ff"}) {
      assertEquals(
          Reason.MALFORMED,
          reasonOf(() -> Webhooks.verify(b(NODE_SECRET), b(NODE_BODY), header, NOW, null)),
          "header: " + header);
    }
  }

  @Test
  void nodeVector_pastAndFutureExpired() {
    assertEquals(
        Reason.EXPIRED,
        reasonOf(() -> Webhooks.verify(b(NODE_SECRET), b(NODE_BODY), NODE_HEADER, NOW + 600, null)));
    assertEquals(
        Reason.EXPIRED,
        reasonOf(() -> Webhooks.verify(b(NODE_SECRET), b(NODE_BODY), NODE_HEADER, NOW - 600, null)));
  }

  @Test
  void nodeVector_wrongSecretTamperedBodyAndNonHexAreMismatch() {
    assertEquals(
        Reason.MISMATCH,
        reasonOf(() -> Webhooks.verify(b("other-secret"), b(NODE_BODY), NODE_HEADER, NOW, null)));
    assertEquals(
        Reason.MISMATCH,
        reasonOf(() -> Webhooks.verify(b(NODE_SECRET), b("tampered"), NODE_HEADER, NOW, null)));
    // Non-hex v1 classifies as MISMATCH, not MALFORMED (pinned Node behavior).
    assertEquals(
        Reason.MISMATCH,
        reasonOf(
            () -> Webhooks.verify(b(NODE_SECRET), b(NODE_BODY), "t=1700000000,v1=zzz-not-hex", NOW, null)));
  }

  // ── PHP suite vectors (tests/WebhookTest.php) ──────────────────────────

  private static final String PHP_SECRET = "whsec_test_secret";

  @Test
  void phpVector_happyPath() {
    assertDoesNotThrow(
        () ->
            Webhooks.verify(
                b(PHP_SECRET),
                b("{\"event\":\"invoice.transmitted\",\"data\":{}}"),
                "t=1700000000,v1=f885bb43bb171aaae01d91a285dcd8c76de1743f72b274e13dada1b48175c733",
                NOW,
                null));
  }

  @Test
  void phpVector_unknownTokenTolerated() {
    assertDoesNotThrow(
        () ->
            Webhooks.verify(
                b(PHP_SECRET),
                b("{\"event\":\"x\"}"),
                "t=1700000000,v1=cefad3c90e34b0ce4339529ffb005cf02fb77dac6ca7feb84bf006a280e3f2fe,v2=ignored",
                NOW,
                null));
  }

  @Test
  void phpVector_replayRejected() {
    assertEquals(
        Reason.EXPIRED,
        reasonOf(
            () ->
                Webhooks.verify(
                    b(PHP_SECRET),
                    b("body"),
                    "t=1700000000,v1=b9f17f9d628e629e7b341b024e9a10e6f1c8f26cb405129d7ef8ce7e92dd13fb",
                    1_700_000_600L,
                    null)));
  }

  @Test
  void phpVector_malformedTimestampAndWrongSecret() {
    assertEquals(
        Reason.MALFORMED,
        reasonOf(() -> Webhooks.verify(b(PHP_SECRET), b("body"), "t=abc,v1=deadbeef", NOW, null)));
    assertEquals(
        Reason.MISMATCH,
        reasonOf(
            () ->
                Webhooks.verify(
                    b("other-secret"),
                    b("{\"event\":\"invoice.transmitted\",\"data\":{}}"),
                    "t=1700000000,v1=f885bb43bb171aaae01d91a285dcd8c76de1743f72b274e13dada1b48175c733",
                    NOW,
                    null)));
  }

  // ── Java-specific guards ───────────────────────────────────────────────

  @Test
  void nonAsciiUnicodeDigitsInHexAreMismatch() {
    // Discriminating probe: swap an ASCII '3' for the Arabic-Indic digit '٣'
    // (U+0663, numeric value 3). Character.digit() decodes it to the SAME
    // bytes — a lenient decoder would accept this signature. The wire
    // contract is ASCII hex only; every sibling SDK rejects it.
    String header = PY_HEADER.replace("v1=c3", "v1=c٣");
    assertEquals(
        Reason.MISMATCH,
        reasonOf(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), header, NOW, null)));
  }

  @Test
  void emptySecretRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Webhooks.verify(new byte[0], b("body"), "t=1,v1=aa", NOW, null));
  }

  @Test
  void constantsPinned() {
    assertEquals("X-Linkbridge-Signature", Webhooks.SIGNATURE_HEADER);
    assertEquals(300, Webhooks.MAX_WEBHOOK_SKEW_SECONDS);
  }

  @Test
  void toleranceOverride() {
    // 301s old fails at the default tolerance but passes at 600.
    assertEquals(
        Reason.EXPIRED,
        reasonOf(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), PY_HEADER, NOW + 301, null)));
    assertDoesNotThrow(() -> Webhooks.verify(b(PY_SECRET), b(PY_BODY), PY_HEADER, NOW + 301, 600L));
  }
}
