/**
 * Hand-curated, stable surface types for the Node SDK.
 *
 * The full generated type surface lives in `./oapi/schema.gen.ts` (run
 * `npm run codegen` to regenerate from `tools/openapi/openapi.yaml`).
 * Application code SHOULD import from this file; internal callers may
 * dip into `./oapi/schema.gen.ts` for the exhaustive union.
 *
 * Why duplicate? `openapi-typescript` produces deeply nested
 * `paths["/v1/invoices"]["post"]["responses"][202]["content"]…` style
 * accessors that are awkward to expose. We re-shape them once here.
 */

/** Canonical NRS invoice. The stable handwritten surface remains permissive
 * for pre-1.0 compatibility; consumers wanting exhaustive field types can use
 * `components["schemas"]["Invoice"]` from `./oapi/schema.gen`. */
export type Invoice = Record<string, unknown>;

export type InvoiceStatus =
  | "pending"
  | "accepted"
  | "rejected"
  | "transmitted"
  | "failed"
  | "cancelled"
  | "paid"
  | "partially_paid"
  | "unpaid";

export interface InvoiceAccepted {
  irn: string;
  status: string;
  tracking_url: string;
  source?: string;
}

export interface InvoiceResult {
  irn: string;
  status: string;
  posting_datetime?: string;
  qr_code_data?: string;
  signed_jws?: string;
}

/** Lossless response from invoice submission. Async/fallback responses include
 * tracking_url; completed sync and dry-run responses include result fields. */
export interface InvoiceSubmission {
  irn: string;
  status: string;
  tracking_url?: string;
  source?: string;
  posting_datetime?: string;
  qr_code_data?: string;
  signed_jws?: string;
}

export interface InvoiceRecord {
  irn: string;
  status: string;
  created_at?: string;
  updated_at?: string;
  posting_datetime?: string;
  signed_jws?: string;
  qr_code_data?: string;
  nrs_response?: Record<string, unknown>;
  payload?: Invoice;
}

export interface InvoicePage {
  data: InvoiceRecord[];
  next_cursor: string | null;
}

export interface InvoiceListParams {
  cursor?: string;
  limit?: number;
  status?: InvoiceStatus | string;
}

export interface TokenResponse {
  access_token: string;
  expires_in: number;
  token_type: string;
  scope?: string;
}

export interface Webhook {
  id: string;
  url: string;
  events: string[];
  active: boolean;
  description?: string;
  /** Returned ONLY on create; subsequent reads omit. */
  secret?: string;
  created_at: string;
  updated_at: string;
}

export interface WebhookCreate {
  url: string;
  events: string[];
  description?: string;
}
