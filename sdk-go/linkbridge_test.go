package linkbridge

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/linkbridge-systems/linkbridge-sdks/sdk-go/oapi"
)

// fakeAPI spins up an httptest.Server that mimics enough of the
// Linkbridge API surface to exercise the SDK end-to-end without
// pulling in the full apps/api module. This deliberately keeps the
// SDK module dependency-free of the server.
type fakeAPI struct {
	t              *testing.T
	tokenCalls     int
	lastAuth       string
	lastIdemKey    string
	lastUA         string
	statusOnSubmit int
	submitBody     []byte
	listResp       string
	getResp        string
	getStatus      int
}

func (f *fakeAPI) handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/v1/oauth/token", func(w http.ResponseWriter, r *http.Request) {
		f.tokenCalls++
		body, _ := io.ReadAll(r.Body)
		var req oapi.TokenRequest
		if err := json.Unmarshal(body, &req); err != nil || req.GrantType != "client_credentials" {
			http.Error(w, "bad", 400)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(oapi.TokenResponse{
			AccessToken: "tok-abc",
			ExpiresIn:   3600,
			TokenType:   "Bearer",
		})
	})

	mux.HandleFunc("/v1/invoices", func(w http.ResponseWriter, r *http.Request) {
		f.lastAuth = r.Header.Get("Authorization")
		f.lastUA = r.Header.Get("User-Agent")
		switch r.Method {
		case http.MethodPost:
			f.lastIdemKey = r.Header.Get("Idempotency-Key")
			f.submitBody, _ = io.ReadAll(r.Body)
			st := f.statusOnSubmit
			if st == 0 {
				st = http.StatusAccepted
			}
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(st)
			if st == http.StatusAccepted {
				_, _ = w.Write([]byte(`{"irn":"INV-1","status":"queued","tracking_url":"/v1/invoices/INV-1"}`))
			} else if st == http.StatusOK {
				_, _ = w.Write([]byte(`{"irn":"INV-1","status":"transmitted","qr_code_data":"QR","signed_jws":"j.w.s"}`))
			} else if st >= 400 {
				_, _ = w.Write([]byte(`{"error":{"code":"validation_failed","message":"nope"}}`))
			}
		case http.MethodGet:
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			if f.listResp == "" {
				_, _ = w.Write([]byte(`{"data":[],"next_cursor":null}`))
			} else {
				_, _ = w.Write([]byte(f.listResp))
			}
		}
	})

	mux.HandleFunc("/v1/invoices/", func(w http.ResponseWriter, r *http.Request) {
		f.lastAuth = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		st := f.getStatus
		if st == 0 {
			st = http.StatusOK
		}
		w.WriteHeader(st)
		if f.getResp != "" {
			_, _ = w.Write([]byte(f.getResp))
		}
	})

	return mux
}

func newClient(t *testing.T, srv *httptest.Server) *Client {
	t.Helper()
	c, err := New(context.Background(), Config{
		BaseURL:      srv.URL,
		ClientID:     "id",
		ClientSecret: "sec",
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return c
}

func testInvoice(t *testing.T, irn string) oapi.Invoice {
	t.Helper()
	const payload = `{
		"business_id":"1c6eaf77-d0bd-455c-9c5c-500a3f1dbfb2",
		"irn":"INV1-SVC00001-20260714",
		"invoice_kind":"B2B",
		"issue_date":"2026-07-14",
		"invoice_type_code":"380",
		"payment_status":"PENDING",
		"tax_point_date":"2026-07-14",
		"document_currency_code":"NGN",
		"tax_currency_code":"NGN",
		"accounting_supplier_party":{
			"party_name":"SDK Test Supplier",
			"tin":"12345-0001",
			"email":"billing@example.com",
			"telephone":"+2348000000000",
			"postal_address":{"street_name":"1 Test Way","city_name":"Lagos","country":"NG"}
		},
		"invoice_line":[{
			"hsn_code":"1001","product_category":"Services",
			"invoiced_quantity":1,"line_extension_amount":100,
			"item":{"name":"Test item","sellers_item_identification":"SKU-1"},
			"price":{"price_amount":100,"base_quantity":1,"price_unit":"each"}
		}],
		"tax_total":[{"tax_amount":0,"tax_subtotal":[{
			"taxable_amount":100,"tax_amount":0,
			"tax_category":{"id":"ZERO_VAT","percent":0}
		}]}],
		"legal_monetary_total":{
			"line_extension_amount":100,"tax_exclusive_amount":100,
			"tax_inclusive_amount":100,"payable_amount":100
		}
	}`
	var invoice oapi.Invoice
	if err := json.Unmarshal([]byte(payload), &invoice); err != nil {
		t.Fatalf("decode canonical test invoice: %v", err)
	}
	invoice.Irn = irn
	return invoice
}

func TestNew_ValidationErrors(t *testing.T) {
	if _, err := New(context.Background(), Config{}); err == nil {
		t.Fatal("expected BaseURL error")
	}
	if _, err := New(context.Background(), Config{BaseURL: "https://x"}); err == nil {
		t.Fatal("expected creds error")
	}
	c, err := New(context.Background(), Config{BaseURL: "https://x", StaticToken: "t"})
	if err != nil || c == nil {
		t.Fatalf("static token should succeed: %v", err)
	}
}

func TestSubmit_HappyPath(t *testing.T) {
	f := &fakeAPI{t: t}
	srv := httptest.NewServer(f.handler())
	defer srv.Close()
	c := newClient(t, srv)

	out, err := c.Invoices.Submit(context.Background(),
		testInvoice(t, "INV1-SVC00001-20260714"), SubmitOptions{IdempotencyKey: "fixed-key"})
	if err != nil {
		t.Fatalf("Submit: %v", err)
	}
	if out.Irn != "INV-1" || out.Status != "queued" {
		t.Fatalf("unexpected result: %+v", out)
	}
	if f.lastAuth != "Bearer tok-abc" {
		t.Fatalf("wrong auth header: %q", f.lastAuth)
	}
	if f.lastIdemKey != "fixed-key" {
		t.Fatalf("wrong idem key: %q", f.lastIdemKey)
	}
	if !strings.HasPrefix(f.lastUA, "linkbridge-go/") {
		t.Fatalf("missing UA: %q", f.lastUA)
	}
	if f.tokenCalls != 1 {
		t.Fatalf("want 1 token call, got %d", f.tokenCalls)
	}

	// Second call must reuse the cached token.
	if _, err := c.Invoices.Submit(context.Background(), testInvoice(t, "INV2-SVC00001-20260714"), SubmitOptions{}); err != nil {
		t.Fatalf("Submit2: %v", err)
	}
	if f.tokenCalls != 1 {
		t.Fatalf("token cache leak: %d calls", f.tokenCalls)
	}
}

func TestSubmit_AutoIdempotencyKey(t *testing.T) {
	f := &fakeAPI{t: t}
	srv := httptest.NewServer(f.handler())
	defer srv.Close()
	c := newClient(t, srv)

	if _, err := c.Invoices.Submit(context.Background(), testInvoice(t, "INV3-SVC00001-20260714"), SubmitOptions{}); err != nil {
		t.Fatalf("Submit: %v", err)
	}
	if !strings.HasPrefix(f.lastIdemKey, "lb-") || len(f.lastIdemKey) < 10 {
		t.Fatalf("auto idem key looks wrong: %q", f.lastIdemKey)
	}
}

func TestSubmit_SyncPreservesResultFields(t *testing.T) {
	f := &fakeAPI{t: t, statusOnSubmit: http.StatusOK}
	srv := httptest.NewServer(f.handler())
	defer srv.Close()
	c := newClient(t, srv)

	out, err := c.Invoices.Submit(context.Background(), testInvoice(t, "INV4-SVC00001-20260714"), SubmitOptions{Mode: "sync"})
	if err != nil {
		t.Fatalf("Submit: %v", err)
	}
	if out.TrackingUrl != "" || out.QrCodeData == nil || *out.QrCodeData != "QR" || out.SignedJws == nil || *out.SignedJws != "j.w.s" {
		t.Fatalf("sync result lost fields: %+v", out)
	}
}

func TestSubmit_APIError(t *testing.T) {
	f := &fakeAPI{t: t, statusOnSubmit: http.StatusUnprocessableEntity}
	srv := httptest.NewServer(f.handler())
	defer srv.Close()
	c := newClient(t, srv)

	_, err := c.Invoices.Submit(context.Background(), testInvoice(t, "INV5-SVC00001-20260714"), SubmitOptions{IdempotencyKey: "k"})
	var apiErr *APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("want *APIError, got %T: %v", err, err)
	}
	if apiErr.Status != 422 || apiErr.Code != "validation_failed" {
		t.Fatalf("wrong APIError: %+v", apiErr)
	}
	if !strings.Contains(apiErr.Error(), "validation_failed") {
		t.Fatalf("Error() missing code: %q", apiErr.Error())
	}
}

func TestGetAndList(t *testing.T) {
	f := &fakeAPI{
		t:        t,
		getResp:  `{"irn":"INV-1","status":"accepted"}`,
		listResp: `{"data":[{"irn":"INV-1","status":"accepted"}],"next_cursor":"abc"}`,
	}
	srv := httptest.NewServer(f.handler())
	defer srv.Close()
	c := newClient(t, srv)

	rec, err := c.Invoices.Get(context.Background(), "INV-1")
	if err != nil || rec.Irn != "INV-1" {
		t.Fatalf("Get: %v %+v", err, rec)
	}

	page, err := c.Invoices.List(context.Background(), ListOptions{Limit: 10, Status: "accepted", Cursor: "x"})
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(page.Data) != 1 || page.NextCursor == nil || *page.NextCursor != "abc" {
		t.Fatalf("unexpected page: %+v", page)
	}
}

func TestIdempotencyKey_Format(t *testing.T) {
	k := IdempotencyKey()
	if !strings.HasPrefix(k, "lb-") || len(k) != 3+32 {
		t.Fatalf("bad key: %q", k)
	}
}

func TestStaticTokenBypassesTokenEndpoint(t *testing.T) {
	f := &fakeAPI{t: t}
	srv := httptest.NewServer(f.handler())
	defer srv.Close()
	c, err := New(context.Background(), Config{
		BaseURL:     srv.URL,
		StaticToken: "raw-token",
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Invoices.Submit(context.Background(), testInvoice(t, "INV6-SVC00001-20260714"), SubmitOptions{IdempotencyKey: "k"}); err != nil {
		t.Fatalf("Submit: %v", err)
	}
	if f.tokenCalls != 0 {
		t.Fatalf("static token must not call /oauth/token, got %d", f.tokenCalls)
	}
	if f.lastAuth != "Bearer raw-token" {
		t.Fatalf("wrong auth: %q", f.lastAuth)
	}
}

func TestTokenRefreshOnExpiry(t *testing.T) {
	// Server hands out short-lived tokens; the cache should re-fetch.
	calls := 0
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/oauth/token", func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"t","expires_in":1,"token_type":"Bearer"}`))
	})
	mux.HandleFunc("/v1/invoices", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(202)
		_, _ = w.Write([]byte(`{"irn":"X","status":"queued","tracking_url":"/x"}`))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	c, _ := New(context.Background(), Config{BaseURL: srv.URL, ClientID: "i", ClientSecret: "s"})
	_, _ = c.Invoices.Submit(context.Background(), testInvoice(t, "INV7-SVC00001-20260714"), SubmitOptions{IdempotencyKey: "k"})
	// Force expiry: wedge the source's expiry to the past via a refetch
	// path triggered by a tiny TTL minus the 60s safety window
	// (expires_in=1 is already < 60s, so the next call must refresh).
	time.Sleep(10 * time.Millisecond)
	_, _ = c.Invoices.Submit(context.Background(), testInvoice(t, "INV8-SVC00001-20260714"), SubmitOptions{IdempotencyKey: "k2"})
	if calls < 2 {
		t.Fatalf("expected token refresh; got %d calls", calls)
	}
}
