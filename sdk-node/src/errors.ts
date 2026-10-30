/**
 * Custom error thrown for any non-2xx API response. Mirrors the
 * canonical error envelope from `tools/openapi/openapi.yaml` and
 * preserves the HTTP status so callers can branch on transport
 * conditions (rate limits, idempotency conflicts, etc.).
 */
export class LinkbridgeAPIError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: string[];
  readonly traceId: string | undefined;
  readonly raw: string;

  constructor(init: {
    status: number;
    code: string;
    message: string;
    details?: string[];
    traceId?: string;
    raw: string;
  }) {
    super(
      init.code
        ? `linkbridge api: ${init.status} ${init.code}: ${init.message}`
        : `linkbridge api: ${init.status} ${init.message || ""}`.trim(),
    );
    this.name = "LinkbridgeAPIError";
    this.status = init.status;
    this.code = init.code;
    this.details = init.details ?? [];
    this.traceId = init.traceId;
    this.raw = init.raw;
  }
}

/** Decode a non-2xx response into LinkbridgeAPIError, tolerating
 * non-conforming bodies (e.g. an HTML 502 from a load balancer). */
export async function decodeAPIError(resp: Response): Promise<LinkbridgeAPIError> {
  const raw = await resp.text();
  let code = "";
  let message = resp.statusText;
  let details: string[] | undefined;
  let traceId: string | undefined;
  try {
    const env = JSON.parse(raw) as {
      error?: { code?: string; message?: string; details?: string[]; trace_id?: string };
    };
    if (env?.error?.code) {
      code = env.error.code;
      message = env.error.message ?? message;
      details = env.error.details;
      traceId = env.error.trace_id;
    }
  } catch {
    /* body wasn't JSON envelope; keep status text */
  }
  return new LinkbridgeAPIError({
    status: resp.status,
    code,
    message,
    details,
    traceId,
    raw,
  });
}
