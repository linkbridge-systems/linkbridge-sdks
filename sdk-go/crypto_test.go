package linkbridge

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCryptoSet(t *testing.T) {
	var gotMethod, gotAuth string
	var gotBody CryptoMaterial
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotAuth = r.Header.Get("Authorization")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(CryptoStatus{
			ServiceID: gotBody.ServiceID, Configured: true,
			PublicKeyFingerprint: "fp123", CertificatePresent: gotBody.Certificate != "",
		})
	}))
	defer srv.Close()

	c, err := New(context.Background(), Config{BaseURL: srv.URL, StaticToken: "tok"})
	if err != nil {
		t.Fatal(err)
	}
	st, err := c.Crypto.Set(context.Background(), CryptoMaterial{
		ServiceID: "94ND90NR", PublicKey: "BASE64PEM", Certificate: "CERT",
	})
	if err != nil {
		t.Fatalf("set: %v", err)
	}
	if gotMethod != http.MethodPut {
		t.Errorf("method = %s, want PUT", gotMethod)
	}
	if gotAuth != "Bearer tok" {
		t.Errorf("auth = %q, want Bearer tok", gotAuth)
	}
	if gotBody.ServiceID != "94ND90NR" || gotBody.PublicKey != "BASE64PEM" {
		t.Errorf("body not forwarded: %+v", gotBody)
	}
	if !st.Configured || st.PublicKeyFingerprint != "fp123" || !st.CertificatePresent {
		t.Errorf("unexpected status: %+v", st)
	}
}

func TestCryptoStatusNotConfigured(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"error": map[string]any{"code": "not_configured", "message": "no material"},
		})
	}))
	defer srv.Close()

	c, _ := New(context.Background(), Config{BaseURL: srv.URL, StaticToken: "tok"})
	_, err := c.Crypto.Status(context.Background())
	if err == nil {
		t.Fatal("expected error for 404")
	}
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusNotFound {
		t.Fatalf("expected APIError 404, got %v", err)
	}
}
