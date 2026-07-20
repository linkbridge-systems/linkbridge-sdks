using System.Text;

namespace Linkbridge.Tests;

public sealed class WebhookVerifierTests
{
    private const long Now = 1_700_000_000;
    private const string PythonSecret = "shhh-it-is-a-secret";
    private const string PythonBody =
        """{"event":"invoice.accepted","data":{"irn":"INV-1"}}""";
    private const string PythonHeader =
        "t=1700000000,v1=c3d95840ab1eec96bdf88f1e3d5fce825cce4e172278c088e20148a8f979c627";
    private const string NodeSecret = "shh";
    private const string NodeBody = """{"event":"invoice.accepted"}""";
    private const string NodeHeader =
        "t=1700000000,v1=9cadac3f4eadeb10ab430d08eaced69eb8f871ab775271576e20f2baddcca814";

    [Fact]
    public void CrossSdkHappyPathsAndForwardCompatibilityPass()
    {
        WebhookVerifier.Verify(PythonSecret, PythonBody, PythonHeader, Now);
        WebhookVerifier.Verify(
            PythonSecret,
            PythonBody,
            PythonHeader + ",v2=ignored",
            Now);
        WebhookVerifier.Verify(
            PythonSecret,
            PythonBody,
            PythonHeader.Replace("t=1700000000", "t=01700000000"),
            Now);
        WebhookVerifier.Verify(
            PythonSecret,
            PythonBody,
            PythonHeader.ToUpperInvariant().Replace("T=", "t=").Replace("V1=", "v1="),
            Now);
        WebhookVerifier.Verify(NodeSecret, NodeBody, NodeHeader, Now);
        WebhookVerifier.Verify(
            "whsec_test_secret",
            """{"event":"invoice.transmitted","data":{}}""",
            "t=1700000000,v1=f885bb43bb171aaae01d91a285dcd8c76de1743f72b274e13dada1b48175c733",
            Now);
    }

    [Fact]
    public void BoundaryAtExactly300SecondsPasses()
    {
        WebhookVerifier.Verify(
            NodeSecret,
            NodeBody,
            "t=1699999700,v1=153fb2eca9b47a7945b5e610b4a0f370d0f5c328c14e30836f5852fd2c0ee9f0",
            Now);
    }

    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData(null)]
    public void MissingHeaderHasTypedReason(string? header)
    {
        AssertFailure(WebhookVerificationFailure.Missing, () =>
            WebhookVerifier.Verify(PythonSecret, PythonBody, header, Now));
    }

    [Theory]
    [InlineData("abc")]
    [InlineData("t=,v1=ff")]
    [InlineData("t=abc,v1=ff")]
    [InlineData("t=-1,v1=ff")]
    [InlineData("t=1700000000")]
    [InlineData("v1=ff")]
    public void MalformedHeadersHaveTypedReason(string header)
    {
        AssertFailure(WebhookVerificationFailure.Malformed, () =>
            WebhookVerifier.Verify(PythonSecret, PythonBody, header, Now));
    }

    [Fact]
    public void ReplayPastAndFutureAreExpired()
    {
        AssertFailure(WebhookVerificationFailure.Expired, () =>
            WebhookVerifier.Verify(PythonSecret, PythonBody, PythonHeader, Now + 301));
        AssertFailure(WebhookVerificationFailure.Expired, () =>
            WebhookVerifier.Verify(PythonSecret, PythonBody, PythonHeader, Now - 301));
    }

    [Fact]
    public void WrongSecretTamperingAndInvalidHexAreMismatch()
    {
        AssertFailure(WebhookVerificationFailure.Mismatch, () =>
            WebhookVerifier.Verify("wrong", PythonBody, PythonHeader, Now));
        AssertFailure(WebhookVerificationFailure.Mismatch, () =>
            WebhookVerifier.Verify(PythonSecret, "tampered", PythonHeader, Now));
        AssertFailure(WebhookVerificationFailure.Mismatch, () =>
            WebhookVerifier.Verify(
                NodeSecret,
                NodeBody,
                "t=1700000000,v1=zzz-not-hex",
                Now));
        AssertFailure(WebhookVerificationFailure.Mismatch, () =>
            WebhookVerifier.Verify(
                PythonSecret,
                PythonBody,
                PythonHeader.Replace("v1=c3", "v1=c٣"),
                Now));
    }

    [Fact]
    public void ByteOverloadUsesRawPayloadAndToleranceCanBeOverridden()
    {
        WebhookVerifier.Verify(
            Encoding.UTF8.GetBytes(PythonSecret),
            Encoding.UTF8.GetBytes(PythonBody),
            PythonHeader,
            Now + 301,
            toleranceSeconds: 600);
    }

    [Fact]
    public void EmptySecretAndNegativeToleranceAreConfigurationErrors()
    {
        Assert.Throws<ArgumentException>(() =>
            WebhookVerifier.Verify("", "body", "t=1,v1=aa", 1));
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            WebhookVerifier.Verify("secret", "body", "t=1,v1=aa", 1, -1));
    }

    [Fact]
    public void ConstantsArePinned()
    {
        Assert.Equal("X-Linkbridge-Signature", WebhookVerifier.SignatureHeader);
        Assert.Equal(300, WebhookVerifier.MaxSkewSeconds);
    }

    private static void AssertFailure(
        WebhookVerificationFailure expected,
        Action action)
    {
        var error = Assert.Throws<WebhookVerificationException>(action);
        Assert.Equal(expected, error.Reason);
    }
}
