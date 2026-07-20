import { randomBytes } from "node:crypto";
import { decodeAPIError, LinkbridgeAPIError } from "./errors.js";
import type {
  Invoice,
  InvoiceSubmission,
  InvoiceListParams,
  InvoicePage,
  InvoiceRecord,
  InvoiceStatus,
  TokenResponse,
  Webhook,
  WebhookCreate,
} from "./types.js";

export const SDK_VERSION = "0.4.0";

export interface ClientConfig {
  /** API base URL, e.g. "https://api.linkbridge.ng" (no trailing slash). */
  baseURL: string;
  /** OAuth2 client_credentials principal (use instead of staticToken). */
  clientId?: string;
  clientSecret?: string;
  /** Use a pre-issued OAuth access token and skip client_credentials. */
  staticToken?: string;
  /** Defaults to ["invoices:write", "invoices:read"]. */
  scopes?: string[];
  /** Custom fetch (for tests or non-Node runtimes). Defaults to global fetch. */
  fetch?: typeof fetch;
  /** Suffix appended to the SDK's User-Agent. */
  userAgent?: string;
}

interface TokenCache {
  token: string;
  expiresAt: number; // epoch ms
}

/**
 * Linkbridge SDK entry point. The constructor performs no network I/O —
 * the OAuth token is fetched lazily on the first authenticated call.
 */
export class LinkbridgeClient {
  readonly invoices: InvoicesAPI;
  readonly webhooks: WebhooksAPI;

  private readonly cfg: Required<Pick<ClientConfig, "baseURL">> & ClientConfig;
  private readonly fetchImpl: typeof fetch;
  private tokenCache: TokenCache | null = null;
  private readonly tokenLock: { p: Promise<string> | null } = { p: null };

  constructor(cfg: ClientConfig) {
    if (!cfg.baseURL) throw new Error("linkbridge: baseURL is required");
    if (!cfg.staticToken && !(cfg.clientId && cfg.clientSecret)) {
      throw new Error("linkbridge: staticToken or clientId+clientSecret is required");
    }
    this.cfg = {
      ...cfg,
      baseURL: cfg.baseURL.replace(/\/+$/, ""),
      scopes: cfg.scopes ?? ["invoices:write", "invoices:read"],
    };
    this.fetchImpl = cfg.fetch ?? fetch;
    this.invoices = new InvoicesAPI(this);
    this.webhooks = new WebhooksAPI(this);
  }

  /** Generate a fresh URL-safe Idempotency-Key. */
  static idempotencyKey(): string {
    return "lb-" + randomBytes(16).toString("hex");
  }

  /** Returns a valid bearer token, refreshing 60s before expiry. */
  async token(): Promise<string> {
    if (this.cfg.staticToken) return this.cfg.staticToken;
    const now = Date.now();
    if (this.tokenCache && this.tokenCache.expiresAt - now > 60_000) {
      return this.tokenCache.token;
    }
    // Coalesce concurrent refreshes onto a single in-flight request.
    if (this.tokenLock.p) return this.tokenLock.p;
    this.tokenLock.p = this.fetchToken().finally(() => {
      this.tokenLock.p = null;
    });
    return this.tokenLock.p;
  }

  private async fetchToken(): Promise<string> {
    const body = JSON.stringify({
      client_id: this.cfg.clientId,
      client_secret: this.cfg.clientSecret,
      grant_type: "client_credentials",
      scope: this.cfg.scopes!.join(" "),
    });
    const resp = await this.fetchImpl(this.cfg.baseURL + "/v1/oauth/token", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        "user-agent": this.userAgent(),
      },
      body,
    });
    if (!resp.ok) throw await decodeAPIError(resp);
    const tr = (await resp.json()) as TokenResponse;
    if (!tr.access_token) {
      throw new LinkbridgeAPIError({
        status: resp.status,
        code: "invalid_token_response",
        message: "empty access_token",
        raw: JSON.stringify(tr),
      });
    }
    const ttlMs = (tr.expires_in > 0 ? tr.expires_in : 300) * 1000;
    this.tokenCache = { token: tr.access_token, expiresAt: Date.now() + ttlMs };
    return tr.access_token;
  }

  /** Internal: perform an authenticated JSON request. */
  async request<T>(
    method: string,
    path: string,
    init: { query?: Record<string, string | number | undefined>; body?: unknown; headers?: Record<string, string> } = {},
  ): Promise<T> {
    const url = new URL(this.cfg.baseURL + path);
    if (init.query) {
      for (const [k, v] of Object.entries(init.query)) {
        if (v !== undefined && v !== "") url.searchParams.set(k, String(v));
      }
    }
    const tok = await this.token();
    const headers: Record<string, string> = {
      authorization: "Bearer " + tok,
      accept: "application/json",
      "user-agent": this.userAgent(),
      ...(init.headers ?? {}),
    };
    let body: string | undefined;
    if (init.body !== undefined) {
      headers["content-type"] = "application/json";
      body = JSON.stringify(init.body);
    }
    const resp = await this.fetchImpl(url, { method, headers, body });
    if (!resp.ok) throw await decodeAPIError(resp);
    if (resp.status === 204 || resp.headers.get("content-length") === "0") {
      return undefined as T;
    }
    return (await resp.json()) as T;
  }

  private userAgent(): string {
    const base = `linkbridge-node/${SDK_VERSION}`;
    return this.cfg.userAgent ? `${base} ${this.cfg.userAgent}` : base;
  }
}

export interface SubmitOptions {
  /** Optional explicit Idempotency-Key. Defaults to a fresh random key. */
  idempotencyKey?: string;
  /** Optional `mode` query param: "async" (default) | "sync" | "dry_run". */
  mode?: "async" | "sync" | "dry_run";
}

export class InvoicesAPI {
  constructor(private readonly client: LinkbridgeClient) {}

  submit(payload: Invoice, opts: SubmitOptions = {}): Promise<InvoiceSubmission> {
    return this.client.request<InvoiceSubmission>("POST", "/v1/invoices", {
      query: opts.mode ? { mode: opts.mode } : undefined,
      body: payload,
      headers: { "idempotency-key": opts.idempotencyKey ?? LinkbridgeClient.idempotencyKey() },
    });
  }

  get(irn: string): Promise<InvoiceRecord> {
    return this.client.request<InvoiceRecord>("GET", `/v1/invoices/${encodeURIComponent(irn)}`);
  }

  list(params: InvoiceListParams = {}): Promise<InvoicePage> {
    return this.client.request<InvoicePage>("GET", "/v1/invoices", {
      query: {
        cursor: params.cursor,
        limit: params.limit,
        status: params.status,
      },
    });
  }
}

export class WebhooksAPI {
  constructor(private readonly client: LinkbridgeClient) {}

  create(input: WebhookCreate): Promise<Webhook> {
    return this.client.request<Webhook>("POST", "/v1/webhooks", { body: input });
  }

  list(): Promise<{ data: Webhook[] }> {
    return this.client.request<{ data: Webhook[] }>("GET", "/v1/webhooks");
  }
}

export type { Invoice, InvoiceSubmission, InvoiceRecord, InvoicePage, InvoiceStatus, Webhook, WebhookCreate };
