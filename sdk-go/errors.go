package linkbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/linkbridge-systems/linkbridge-sdks/sdk-go/oapi"
)

// fetch performs the OAuth2 client_credentials exchange and returns the
// access token plus its remaining lifetime. It uses the SDK's http
// client directly (no auth middleware) to avoid recursing into the
// token source while it's seeded.
func (ts *tokenSource) fetch(ctx context.Context) (string, time.Duration, error) {
	body, err := json.Marshal(oapi.TokenRequest{
		ClientId:     ts.clientID,
		ClientSecret: ts.clientSecret,
		GrantType:    "client_credentials",
		Scope:        joinScopes(ts.scopes),
	})
	if err != nil {
		return "", 0, fmt.Errorf("marshal token request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		ts.baseURL+"/v1/oauth/token", bytes.NewReader(body))
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "linkbridge-go/"+Version)

	resp, err := ts.http.Do(req)
	if err != nil {
		return "", 0, fmt.Errorf("token exchange: %w", err)
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", 0, decodeAPIError(resp.StatusCode, raw)
	}
	var tr oapi.TokenResponse
	if err := json.Unmarshal(raw, &tr); err != nil {
		return "", 0, fmt.Errorf("decode token response: %w", err)
	}
	if tr.AccessToken == "" {
		return "", 0, fmt.Errorf("token exchange: empty access_token")
	}
	ttl := time.Duration(tr.ExpiresIn) * time.Second
	if ttl <= 0 {
		ttl = 5 * time.Minute // server forgot ExpiresIn — be conservative
	}
	return tr.AccessToken, ttl, nil
}

func joinScopes(scopes []string) *string {
	if len(scopes) == 0 {
		return nil
	}
	out := scopes[0]
	for _, s := range scopes[1:] {
		out += " " + s
	}
	return &out
}

// APIError is returned by the SDK whenever the API responds with a
// non-success status. It mirrors the canonical error envelope defined
// in tools/openapi/openapi.yaml (`Error`) and adds the HTTP status so
// callers can branch on transport-level conditions.
type APIError struct {
	Status  int      `json:"-"`
	Code    string   `json:"code"`
	Message string   `json:"message"`
	Details []string `json:"details,omitempty"`
	TraceID string   `json:"trace_id,omitempty"`
	// Raw is the unmodified response body, useful for debugging when
	// the body did not match the standard envelope.
	Raw []byte `json:"-"`
}

func (e *APIError) Error() string {
	if e.Code != "" {
		return fmt.Sprintf("linkbridge api: %d %s: %s", e.Status, e.Code, e.Message)
	}
	return fmt.Sprintf("linkbridge api: %d %s", e.Status, http.StatusText(e.Status))
}

// decodeAPIError parses the standard error envelope; if the body does
// not match it, returns an APIError with just Status + Raw populated.
func decodeAPIError(status int, body []byte) *APIError {
	apiErr := &APIError{Status: status, Raw: body}
	var env struct {
		Error struct {
			Code    string    `json:"code"`
			Message string    `json:"message"`
			Details *[]string `json:"details,omitempty"`
			TraceID *string   `json:"trace_id,omitempty"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &env); err == nil && env.Error.Code != "" {
		apiErr.Code = env.Error.Code
		apiErr.Message = env.Error.Message
		if env.Error.Details != nil {
			apiErr.Details = *env.Error.Details
		}
		if env.Error.TraceID != nil {
			apiErr.TraceID = *env.Error.TraceID
		}
	}
	return apiErr
}
