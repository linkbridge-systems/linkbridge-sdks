// Webhook signature verification for Linkbridge HMAC-signed deliveries.
//
// Per spec §8.5, every webhook request carries:
//
//	X-Linkbridge-Signature: t=<unix>,v1=<hex>
//
// where v1 = HMAC-SHA256(secret, t + "." + body). Receivers MUST reject
// requests outside a 5-minute clock window to bound replay attacks.

package linkbridge

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// MaxWebhookSkew is the largest |now - t| the verifier accepts.
const MaxWebhookSkew = 5 * time.Minute

// SignatureHeader is the canonical header name carrying the
// `t=...,v1=...` payload.
const SignatureHeader = "X-Linkbridge-Signature"

// Errors returned by VerifyWebhook.
var (
	ErrSignatureMissing  = errors.New("linkbridge: missing X-Linkbridge-Signature")
	ErrSignatureMalformed = errors.New("linkbridge: malformed X-Linkbridge-Signature")
	ErrSignatureExpired  = errors.New("linkbridge: signature timestamp outside replay window")
	ErrSignatureMismatch = errors.New("linkbridge: signature mismatch")
)

// VerifyWebhook authenticates an incoming webhook delivery. `header`
// is the raw `X-Linkbridge-Signature` value, `body` is the exact bytes
// the receiver read off the wire, and `now` is the current time
// (injected for testability — pass time.Now() in production).
func VerifyWebhook(secret, body []byte, header string, now time.Time) error {
	if header == "" {
		return ErrSignatureMissing
	}
	t, sig, err := parseSignatureHeader(header)
	if err != nil {
		return err
	}
	if d := now.Sub(time.Unix(t, 0)); d > MaxWebhookSkew || d < -MaxWebhookSkew {
		return ErrSignatureExpired
	}
	mac := hmac.New(sha256.New, secret)
	// Use Write twice to avoid an extra allocation for "<t>.<body>".
	mac.Write([]byte(strconv.FormatInt(t, 10)))
	mac.Write([]byte{'.'})
	mac.Write(body)
	expected := mac.Sum(nil)
	got, err := hex.DecodeString(sig)
	if err != nil {
		return ErrSignatureMalformed
	}
	if !hmac.Equal(expected, got) {
		return ErrSignatureMismatch
	}
	return nil
}

// VerifyHTTPRequest is a convenience wrapper that reads the request
// body (consuming it), verifies the signature, and returns the body
// bytes for the caller to decode. On error the body may have been
// fully or partially consumed.
func VerifyHTTPRequest(secret []byte, r *http.Request, now time.Time) ([]byte, error) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, err
	}
	if err := VerifyWebhook(secret, body, r.Header.Get(SignatureHeader), now); err != nil {
		return nil, err
	}
	return body, nil
}

func parseSignatureHeader(h string) (int64, string, error) {
	var (
		t   int64
		sig string
		err error
	)
	for _, part := range strings.Split(h, ",") {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) != 2 {
			return 0, "", ErrSignatureMalformed
		}
		switch kv[0] {
		case "t":
			t, err = strconv.ParseInt(kv[1], 10, 64)
			if err != nil || t <= 0 {
				return 0, "", ErrSignatureMalformed
			}
		case "v1":
			sig = kv[1]
		}
	}
	if t == 0 || sig == "" {
		return 0, "", ErrSignatureMalformed
	}
	return t, sig, nil
}
