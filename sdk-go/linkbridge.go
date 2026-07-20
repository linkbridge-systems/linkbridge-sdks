// Package linkbridge is the official Go SDK for the Linkbridge
// e-invoicing API. It wraps the low-level oapi-codegen client in
// `./oapi` with sane defaults for authentication, retries, and
// idempotency.
//
// Spec source: tools/openapi/openapi.yaml at the same git revision as
// this SDK release. Regenerate the low-level client with:
//
//	./tools/scripts/codegen-sdk-go.sh
//
// Quick start:
//
//	ctx := context.Background()
//	c, err := linkbridge.New(ctx, linkbridge.Config{
//	    BaseURL:      "https://api.linkbridge.ng",
//	    ClientID:     os.Getenv("LB_CLIENT_ID"),
//	    ClientSecret: os.Getenv("LB_CLIENT_SECRET"),
//	    Scopes:       []string{"invoices:write", "invoices:read"},
//	})
//	if err != nil { log.Fatal(err) }
//
//	accepted, err := c.Invoices.Submit(ctx, invoice, linkbridge.IdempotencyKey())
//
// The SDK does not depend on the api server module — it only consumes
// the public OpenAPI contract. That keeps it cleanly publishable as a
// standalone module.
package linkbridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/linkbridge-systems/linkbridge-sdks/sdk-go/oapi"
)

// Version is bumped on each tagged release of the SDK module. It is
// surfaced in the User-Agent so server-side telemetry can spot stale
// SDK versions in the wild.
const Version = "0.4.0"

// Config configures the SDK client.
type Config struct {
	// BaseURL of the API, e.g. "https://api.linkbridge.ng" (no trailing
	// slash). Required.
	BaseURL string

	// ClientID and ClientSecret are the OAuth2 client_credentials
	// principals. When both are set the SDK obtains and refreshes
	// access tokens automatically. Mutually exclusive with StaticToken.
	ClientID     string
	ClientSecret string

	// Scopes requested at token issuance. Default: ["invoices:write",
	// "invoices:read"].
	Scopes []string

	// StaticToken bypasses the OAuth2 dance and uses the given bearer
	// token verbatim. Useful with a pre-issued OAuth access token.
	StaticToken string

	// HTTPClient is the underlying transport. When nil a shared client
	// with sane timeouts is used.
	HTTPClient *http.Client

	// UserAgent is appended to "linkbridge-go/<Version>". Optional.
	UserAgent string
}

// Client is the SDK entry point.
type Client struct {
	raw  *oapi.Client
	http *http.Client
	cfg  Config

	tokens *tokenSource

	// Resource handles. Each one is a thin facade over the low-level
	// generated client; they exist purely so callers get
	// IDE-friendly grouping (`client.Invoices.Submit(...)`).
	Invoices *InvoicesAPI
	Webhooks *WebhooksAPI
	Lookups  *LookupsAPI
	Crypto   *CryptoAPI
}

// New constructs a configured Client. The OAuth token source (if any)
// is initialised lazily on the first authenticated call.
func New(ctx context.Context, cfg Config) (*Client, error) {
	if cfg.BaseURL == "" {
		return nil, errors.New("linkbridge: BaseURL is required")
	}
	if cfg.StaticToken == "" && (cfg.ClientID == "" || cfg.ClientSecret == "") {
		return nil, errors.New("linkbridge: either StaticToken or ClientID+ClientSecret is required")
	}
	if cfg.HTTPClient == nil {
		cfg.HTTPClient = &http.Client{Timeout: 30 * time.Second}
	}
	if len(cfg.Scopes) == 0 {
		cfg.Scopes = []string{"invoices:write", "invoices:read"}
	}

	c := &Client{
		http: cfg.HTTPClient,
		cfg:  cfg,
	}

	// Token source. Static tokens take precedence for callers that already
	// hold an OAuth access token.
	if cfg.StaticToken != "" {
		c.tokens = &tokenSource{static: cfg.StaticToken}
	} else {
		c.tokens = &tokenSource{
			baseURL:      strings.TrimRight(cfg.BaseURL, "/"),
			clientID:     cfg.ClientID,
			clientSecret: cfg.ClientSecret,
			scopes:       cfg.Scopes,
			http:         cfg.HTTPClient,
		}
	}

	raw, err := oapi.NewClient(strings.TrimRight(cfg.BaseURL, "/"),
		oapi.WithHTTPClient(c.http),
		oapi.WithRequestEditorFn(c.injectAuth),
		oapi.WithRequestEditorFn(c.injectUserAgent),
	)
	if err != nil {
		return nil, fmt.Errorf("linkbridge: build client: %w", err)
	}
	c.raw = raw

	c.Invoices = &InvoicesAPI{c: c}
	c.Webhooks = &WebhooksAPI{c: c}
	c.Lookups = &LookupsAPI{c: c}
	c.Crypto = &CryptoAPI{c: c}
	return c, nil
}

// Raw returns the low-level oapi-codegen client. Use sparingly — the
// high-level methods on *InvoicesAPI / *WebhooksAPI / *LookupsAPI are
// the supported surface and won't break across SDK minor versions.
// Direct use of Raw bypasses Authorization injection ergonomics; you
// can still chain WithRequestEditorFn as needed.
func (c *Client) Raw() *oapi.Client { return c.raw }

// injectAuth is the per-request middleware that obtains a fresh bearer
// token and sets the Authorization header.
func (c *Client) injectAuth(ctx context.Context, req *http.Request) error {
	// /oauth/token requests are handled by the tokenSource directly
	// and must not loop back through this editor.
	if strings.HasSuffix(req.URL.Path, "/oauth/token") {
		return nil
	}
	tok, err := c.tokens.Token(ctx)
	if err != nil {
		return fmt.Errorf("linkbridge: obtain token: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	return nil
}

// injectUserAgent stamps an SDK-identifying User-Agent so server-side
// telemetry can correlate clients without callers needing to remember.
func (c *Client) injectUserAgent(ctx context.Context, req *http.Request) error {
	ua := "linkbridge-go/" + Version
	if c.cfg.UserAgent != "" {
		ua = ua + " " + c.cfg.UserAgent
	}
	req.Header.Set("User-Agent", ua)
	return nil
}

// IdempotencyKey returns a fresh, URL-safe random key suitable for the
// `Idempotency-Key` header on POST /v1/invoices and other writes. The
// API enforces a 24-hour replay window per ADR-0003 / §9.4.
func IdempotencyKey() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return "lb-" + hex.EncodeToString(b[:])
}

// WithIdempotencyKey returns a request editor that sets the
// Idempotency-Key header to the given value. Pass it to any generated
// method as a request editor or use the convenience
// `*InvoicesAPI.Submit(ctx, payload, key)` form.
func WithIdempotencyKey(key string) oapi.RequestEditorFn {
	return func(_ context.Context, req *http.Request) error {
		req.Header.Set("Idempotency-Key", key)
		return nil
	}
}

// tokenSource caches and refreshes OAuth2 client_credentials tokens.
// Refresh happens 60s before expiry so callers never see a transient
// 401 caused by clock drift. The source is safe for concurrent use.
type tokenSource struct {
	static string

	baseURL      string
	clientID     string
	clientSecret string
	scopes       []string
	http         *http.Client

	mu      sync.Mutex
	current string
	expires time.Time
}

func (ts *tokenSource) Token(ctx context.Context) (string, error) {
	if ts.static != "" {
		return ts.static, nil
	}
	ts.mu.Lock()
	defer ts.mu.Unlock()
	if ts.current != "" && time.Until(ts.expires) > 60*time.Second {
		return ts.current, nil
	}
	tok, ttl, err := ts.fetch(ctx)
	if err != nil {
		return "", err
	}
	ts.current = tok
	ts.expires = time.Now().Add(ttl)
	return ts.current, nil
}
