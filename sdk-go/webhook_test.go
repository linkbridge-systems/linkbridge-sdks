package linkbridge

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func sign(secret, body []byte, t int64) string {
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(strconv.FormatInt(t, 10)))
	mac.Write([]byte{'.'})
	mac.Write(body)
	return "t=" + strconv.FormatInt(t, 10) + ",v1=" + hex.EncodeToString(mac.Sum(nil))
}

func TestVerifyWebhook_OK(t *testing.T) {
	secret := []byte("shh")
	body := []byte(`{"event":"invoice.accepted"}`)
	now := time.Unix(1_700_000_000, 0)
	if err := VerifyWebhook(secret, body, sign(secret, body, now.Unix()), now); err != nil {
		t.Fatalf("expected ok, got %v", err)
	}
}

func TestVerifyWebhook_Errors(t *testing.T) {
	secret := []byte("shh")
	body := []byte("payload")
	now := time.Unix(1_700_000_000, 0)
	good := sign(secret, body, now.Unix())

	cases := []struct {
		name   string
		header string
		body   []byte
		now    time.Time
		want   error
	}{
		{"missing", "", body, now, ErrSignatureMissing},
		{"malformed-no-equals", "txxx,v1yy", body, now, ErrSignatureMalformed},
		{"malformed-no-t", "v1=" + strings.TrimPrefix(strings.Split(good, ",")[1], "v1="), body, now, ErrSignatureMalformed},
		{"malformed-bad-t", "t=abc,v1=ff", body, now, ErrSignatureMalformed},
		{"expired-old", good, body, now.Add(10 * time.Minute), ErrSignatureExpired},
		{"expired-future", good, body, now.Add(-10 * time.Minute), ErrSignatureExpired},
		{"bad-hex", "t=" + strconv.FormatInt(now.Unix(), 10) + ",v1=zzz", body, now, ErrSignatureMalformed},
		{"mismatch-secret", sign([]byte("other"), body, now.Unix()), body, now, ErrSignatureMismatch},
		{"mismatch-body", good, []byte("tampered"), now, ErrSignatureMismatch},
	}
	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			err := VerifyWebhook(secret, tc.body, tc.header, tc.now)
			if !errors.Is(err, tc.want) {
				t.Fatalf("want %v, got %v", tc.want, err)
			}
		})
	}
}

func TestVerifyWebhook_BoundaryAcceptsExactlyAtSkew(t *testing.T) {
	secret := []byte("shh")
	body := []byte("x")
	now := time.Unix(1_700_000_000, 0)
	// signed at now-5m (exactly the skew limit) should still pass.
	hdr := sign(secret, body, now.Add(-MaxWebhookSkew).Unix())
	if err := VerifyWebhook(secret, body, hdr, now); err != nil {
		t.Fatalf("boundary should pass, got %v", err)
	}
}

func TestVerifyHTTPRequest(t *testing.T) {
	secret := []byte("k")
	now := time.Unix(1_700_000_000, 0)
	body := []byte(`{"a":1}`)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got, err := VerifyHTTPRequest(secret, r, now)
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		_, _ = w.Write(got)
	}))
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodPost, srv.URL, strings.NewReader(string(body)))
	req.Header.Set(SignatureHeader, sign(secret, body, now.Unix()))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	got, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || string(got) != string(body) {
		t.Fatalf("verify failed: %d %s", resp.StatusCode, got)
	}
}
