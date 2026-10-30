package linkbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

// CryptoAPI groups the FIRS taxpayer crypto-material endpoints
// (/v1/crypto). The transmitter uses the uploaded public key +
// certificate to stamp the FIRS QR/CSID locally.
//
// This resource is hand-written against the raw HTTP client rather than
// the generated oapi client, so it does not depend on regenerating the
// low-level client when /v1/crypto changes.
type CryptoAPI struct{ c *Client }

// CryptoMaterial is the PUT /v1/crypto request body. PublicKey is the
// base64-encoded PEM exactly as it appears in the taxpayer's
// crypto_keys.txt; ServiceID is the FIRS-assigned 8-character identifier.
type CryptoMaterial struct {
	ServiceID   string `json:"service_id"`
	PublicKey   string `json:"public_key"`
	Certificate string `json:"certificate,omitempty"`
}

// CryptoStatus is the response shape for PUT and GET /v1/crypto.
type CryptoStatus struct {
	ServiceID            string `json:"service_id"`
	Configured           bool   `json:"configured"`
	PublicKeyFingerprint string `json:"public_key_fingerprint"`
	CertificatePresent   bool   `json:"certificate_present"`
	CreatedAt            string `json:"created_at,omitempty"`
	UpdatedAt            string `json:"updated_at,omitempty"`
}

// Set uploads (or replaces) the calling tenant's FIRS crypto material.
func (a *CryptoAPI) Set(ctx context.Context, m CryptoMaterial) (*CryptoStatus, error) {
	body, err := json.Marshal(m)
	if err != nil {
		return nil, err
	}
	return a.do(ctx, http.MethodPut, body)
}

// Status reports whether FIRS crypto material is configured for the
// calling tenant. A 404 from the API surfaces as an *APIError with
// status 404 (use errors.As to detect "not configured").
func (a *CryptoAPI) Status(ctx context.Context) (*CryptoStatus, error) {
	return a.do(ctx, http.MethodGet, nil)
}

func (a *CryptoAPI) do(ctx context.Context, method string, body []byte) (*CryptoStatus, error) {
	req, err := a.c.newAuthedRequest(ctx, method, "/v1/crypto", body)
	if err != nil {
		return nil, err
	}
	resp, err := a.c.http.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode/100 != 2 {
		raw, _ := readAll(resp)
		return nil, decodeAPIError(resp.StatusCode, raw)
	}
	defer resp.Body.Close()
	var out CryptoStatus
	if err := decodeJSON(resp, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// newAuthedRequest builds an HTTP request to the API with the SDK's
// bearer-token + User-Agent editors applied, mirroring what the
// generated client gets via WithRequestEditorFn.
func (c *Client) newAuthedRequest(ctx context.Context, method, path string, body []byte) (*http.Request, error) {
	var rdr io.Reader
	if body != nil {
		rdr = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.cfg.BaseURL, "/")+path, rdr)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if err := c.injectAuth(ctx, req); err != nil {
		return nil, err
	}
	if err := c.injectUserAgent(ctx, req); err != nil {
		return nil, err
	}
	return req, nil
}
