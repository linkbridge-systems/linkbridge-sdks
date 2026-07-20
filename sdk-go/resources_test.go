package linkbridge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestLookupsHSNCodes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/lookups/hsn-codes" {
			t.Errorf("path = %s, want /v1/lookups/hsn-codes", r.URL.Path)
		}
		if r.URL.Query().Get("prefix") != "85" {
			t.Errorf("prefix not forwarded: %q", r.URL.Query().Get("prefix"))
		}
		w.Header().Set("ETag", `"abc"`)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"items":       []map[string]any{{"code": "8517.12", "description": "Phones"}},
			"next_cursor": "c2",
		})
	}))
	defer srv.Close()

	c, _ := New(context.Background(), Config{BaseURL: srv.URL, StaticToken: "t"})
	page, etag, err := c.Lookups.HSNCodes(context.Background(), HSNCodesOptions{Prefix: "85", Limit: 50})
	if err != nil {
		t.Fatalf("HSNCodes: %v", err)
	}
	if etag != `"abc"` {
		t.Errorf("etag = %q, want \"abc\"", etag)
	}
	if page == nil || len(page.Items) != 1 || page.Items[0].Code != "8517.12" {
		t.Fatalf("unexpected page: %+v", page)
	}
	if page.NextCursor == nil || *page.NextCursor != "c2" {
		t.Errorf("next_cursor = %v, want c2", page.NextCursor)
	}
}

func TestLookupsHSNCodes304(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("ETag", `"same"`)
		w.WriteHeader(http.StatusNotModified)
	}))
	defer srv.Close()

	c, _ := New(context.Background(), Config{BaseURL: srv.URL, StaticToken: "t"})
	page, etag, err := c.Lookups.HSNCodes(context.Background(), HSNCodesOptions{ETag: `"same"`})
	if err != nil {
		t.Fatalf("HSNCodes 304: %v", err)
	}
	if page != nil {
		t.Errorf("expected nil page on 304, got %+v", page)
	}
	if etag != `"same"` {
		t.Errorf("etag = %q, want \"same\"", etag)
	}
}
