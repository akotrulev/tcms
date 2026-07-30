/**
 * Thin fetch wrapper. All requests go to /api/v1 and are intercepted by MSW in
 * development — there is no backend yet (see src/mocks/).
 *
 * Two things here are load-bearing rather than boilerplate:
 *
 *   1. Every call carries a **timeout budget**. A request nothing aborts hangs until the
 *      browser's own ~300s limit, which renders as a region spinning forever — a state no
 *      amount of loading/failed branching can express.
 *   2. `TimeoutError` and `AbortError` are kept apart. A timeout is a failure to surface;
 *      an abort (unmount, navigation, a superseded query) is **not a failure at all** and
 *      must never be rendered or notified.
 */

import { safeStorage } from '../lib/safeStorage';

const BASE = '/api/v1';

/**
 * Budgets per endpoint class, not one global constant — a typeahead that waits 10s is
 * broken, and an export that gives up after 10s is equally broken.
 */
export const TIMEOUTS = {
  /** Type-ahead and search-as-you-type. */
  typeahead: 3_000,
  /** Ordinary interactive reads and writes. */
  interactive: 10_000,
  /** Reports, exports, bulk operations. */
  long: 60_000,
} as const;

export class ApiError extends Error {
  // Declared as fields rather than constructor parameter properties: the project sets
  // `erasableSyntaxOnly`, which forbids the parameter-property shorthand.
  readonly status: number;
  readonly detail: string;
  /** RFC-7807 `errors` map, when the server rejected specific fields. */
  readonly fieldErrors: Record<string, string[]> | null;
  readonly correlationId: string | null;
  /**
   * The `Retry-After` header, verbatim. Kept as sent (it may be delta-seconds *or* an HTTP
   * date) and parsed at the point of use by `retryAfterMs`.
   */
  readonly retryAfter: string | null;

  constructor(
    status: number,
    detail: string,
    fieldErrors: Record<string, string[]> | null = null,
    correlationId: string | null = null,
    retryAfter: string | null = null,
  ) {
    super(detail);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
    this.fieldErrors = fieldErrors;
    this.correlationId = correlationId;
    this.retryAfter = retryAfter;
  }

  /** True when the server rejected individual fields, so a form can land them per input. */
  get hasFieldErrors(): boolean {
    return this.fieldErrors !== null && Object.keys(this.fieldErrors).length > 0;
  }
}

/** A request that ran past its deadline. Distinct from a cancellation. */
export class TimeoutError extends Error {
  constructor(ms: number) {
    super(`The server did not respond within ${Math.round(ms / 1000)} seconds.`);
    this.name = 'TimeoutError';
  }
}

/**
 * True when the reason a request ended was cancellation — an unmount, a navigation, or a
 * newer query superseding this one. Callers must not render or notify these.
 */
export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

/**
 * How long the server asked us to wait, in milliseconds, or `null` if it didn't say.
 *
 * `Retry-After` comes in two forms and only handling one of them is a common bug: a
 * delta-seconds count (`Retry-After: 30`) or an HTTP date (`Retry-After: Wed, 21 Oct 2026
 * 07:28:00 GMT`). A clock-skewed client can compute a negative delay from the date form, which
 * is why the result is floored at zero rather than trusted.
 */
export function retryAfterMs(error: unknown): number | null {
  if (!(error instanceof ApiError) || !error.retryAfter) return null;

  const seconds = Number(error.retryAfter);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);

  const at = Date.parse(error.retryAfter);
  if (Number.isNaN(at)) return null;
  return Math.max(0, at - Date.now());
}

/**
 * A key identifying one **user intent**, for `Idempotency-Key` on a write.
 *
 * The distinction that makes this work: the key belongs to the *intent* ("create this test
 * case"), not to the *attempt*. Every retry of that intent carries the same key, so a server
 * that already processed the first attempt returns the original result instead of creating a
 * second record. Generating a fresh key per attempt would make the header decorative.
 *
 * This is what makes a timed-out POST safe to retry at all — a request that ran past its
 * deadline may well have succeeded server-side, and without a key there is no way to ask again
 * without risking a duplicate.
 */
export function newIntentKey(): string {
  // `randomUUID` needs a secure context; a dev server on plain http over a LAN IP is not one.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Composes the caller's cancel signal with a deadline, rather than choosing between them:
 * TanStack Query's `signal` still cancels on unmount, and the timeout still fires.
 */
export function budget(ms: number, external?: AbortSignal): AbortSignal {
  const deadline = AbortSignal.timeout(ms);
  return external ? AbortSignal.any([external, deadline]) : deadline;
}

function authHeader(): Record<string, string> {
  // Read through `safeStorage`, not `localStorage` directly. `AuthProvider` *writes* the token
  // through the guarded wrapper, which falls back to an in-memory map when localStorage throws
  // (private mode, quota, a locked-down profile). Reading raw would then find nothing and send
  // every request unauthenticated — a session that appears to work until the first API call.
  const token = safeStorage.get('tcms.token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

interface RequestOptions {
  signal?: AbortSignal;
  /** Which budget class this endpoint belongs to. Defaults to `interactive`. */
  timeoutMs?: number;
  headers?: Record<string, string>;
  /**
   * Idempotency key for a write, from `newIntentKey()`. One key per user intent, reused across
   * every retry of that intent, so the server can dedupe rather than create a second record.
   */
  idempotencyKey?: string;
}

async function request<T>(path: string, init: RequestInit = {}, options: RequestOptions = {}): Promise<T> {
  const timeoutMs = options.timeoutMs ?? TIMEOUTS.interactive;

  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, {
      ...init,
      signal: budget(timeoutMs, options.signal),
      headers: {
        'Content-Type': 'application/json',
        ...authHeader(),
        ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}),
        ...options.headers,
        ...init.headers,
      },
    });
  } catch (error) {
    // AbortSignal.timeout() rejects with a TimeoutError DOMException; a caller-driven
    // cancellation is an AbortError. Only the first is a failure worth surfacing.
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw new TimeoutError(timeoutMs);
    }
    throw error;
  }

  if (res.status === 204) return undefined as T;

  if (!res.ok) {
    let detail = res.statusText;
    let fieldErrors: Record<string, string[]> | null = null;
    try {
      const body = await res.json();
      detail = body.detail ?? body.title ?? detail;
      if (body.errors && typeof body.errors === 'object') {
        fieldErrors = body.errors as Record<string, string[]>;
      }
    } catch {
      /* non-JSON error body — the status text is all we have, and it's not raw HTML */
    }
    throw new ApiError(
      res.status,
      detail,
      fieldErrors,
      res.headers.get('x-correlation-id'),
      res.headers.get('retry-after'),
    );
  }

  return res.json() as Promise<T>;
}

/** Serializes filter objects to a query string, dropping empties and expanding arrays. */
export function qs(params: Record<string, unknown> = {}): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) {
      if (v.length === 0) continue;
      v.forEach((item) => sp.append(k, String(item)));
    } else {
      sp.set(k, String(v));
    }
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>(path, {}, options),
  /** Pass `idempotencyKey` from `newIntentKey()` on any create the user could retry. */
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(body ?? {}) }, options),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(body ?? {}) }, options),
  del: <T>(path: string, options?: RequestOptions) => request<T>(path, { method: 'DELETE' }, options),
};
