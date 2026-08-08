/**
 * The error surface every caller of this package sees.
 *
 * These are deliberately **near-copies of `frontend/src/api/client.ts`'s classes, not imports of
 * them**, and the reason is worth stating because the copy looks like the drift this repo keeps
 * finding. The SPA's client is a *browser* client: it reads the session from `localStorage`, it
 * carries a dev-only fault seam guarded by `import.meta.env.DEV`, and its timeout budgets are
 * chosen for a person watching a region spin. None of that compiles or means anything in a CLI
 * process. What the two genuinely share is the **wire format** — RFC-7807 `Problem`, `Retry-After`,
 * `x-correlation-id` — and that is owned by `openapi.yaml`, which both sides read.
 *
 * The one thing that must not diverge is the *reading* of that format, so
 * `problemShape.test.ts` derives the field list from the contract rather than from either copy.
 */

/**
 * A response the server refused, carrying its RFC-7807 problem document.
 *
 * `title` is kept apart from `detail` for the reason the SPA records: one status can mean two
 * things wanting opposite advice — a 409 is either "someone else saved first" (re-read and
 * reapply) or "this is not in a state that allows it" (re-reading changes nothing).
 */
export class TcmsApiError extends Error {
  readonly status: number;
  readonly detail: string;
  /** RFC-7807 `title` — the problem's *kind*. Null when the body carried none. */
  readonly title: string | null;
  /** RFC-7807 `type`. Null when the body carried none. */
  readonly type: string | null;
  /**
   * RFC-7807 `errors` — field name to the messages it failed on, present only on a validation
   * rejection. Keys are spelled as on the input schema (`gherkinSource`), which is what lets a
   * CLI print `gherkinSource: must not be blank` against the flag the user typed.
   */
  readonly fieldErrors: Record<string, string[]> | null;
  readonly correlationId: string | null;
  /** `Retry-After` verbatim — delta-seconds *or* an HTTP date. Parsed by {@link retryAfterMs}. */
  readonly retryAfter: string | null;
  /** The request that was refused, for a message that can name it. */
  readonly method: string;
  readonly path: string;

  constructor(init: {
    status: number;
    detail: string;
    method: string;
    path: string;
    title?: string | null;
    type?: string | null;
    fieldErrors?: Record<string, string[]> | null;
    correlationId?: string | null;
    retryAfter?: string | null;
  }) {
    super(init.detail);
    this.name = 'TcmsApiError';
    this.status = init.status;
    this.detail = init.detail;
    this.method = init.method;
    this.path = init.path;
    this.title = init.title ?? null;
    this.type = init.type ?? null;
    this.fieldErrors = init.fieldErrors ?? null;
    this.correlationId = init.correlationId ?? null;
    this.retryAfter = init.retryAfter ?? null;
  }

  /** True when the server rejected individual fields, so a CLI can print them per flag. */
  get hasFieldErrors(): boolean {
    return this.fieldErrors !== null && Object.keys(this.fieldErrors).length > 0;
  }
}

/**
 * A request that ran past its deadline. Distinct from a cancellation, and distinct from a
 * refusal: a timed-out write **may well have succeeded server-side**, which is the whole reason
 * `Idempotency-Key` exists on this surface (`core/idempotency.ts`).
 */
export class TcmsTimeoutError extends Error {
  readonly method: string;
  readonly path: string;
  readonly timeoutMs: number;

  constructor(method: string, path: string, timeoutMs: number) {
    super(
      `${method} ${path} did not answer within ${Math.round(timeoutMs / 1000)} seconds. `
        + 'If this was a write, it may still have been applied — retry it with the same '
        + 'Idempotency-Key rather than a new one.',
    );
    this.name = 'TcmsTimeoutError';
    this.method = method;
    this.path = path;
    this.timeoutMs = timeoutMs;
  }
}

/** Thrown when the client is constructed or driven with an argument it cannot use. */
export class TcmsClientError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TcmsClientError';
  }
}

/**
 * True when a request ended because the caller cancelled it — a Ctrl-C, a superseded call, a
 * host shutting an MCP session. Callers must not render or report these as failures.
 */
export function isAbort(error: unknown): boolean {
  return (
    (error instanceof DOMException || error instanceof Error) && error.name === 'AbortError'
  );
}

/**
 * How long the server asked us to wait, in milliseconds, or null if it did not say.
 *
 * `Retry-After` comes in two forms and handling only one is the common bug: delta-seconds
 * (`Retry-After: 30`) or an HTTP date. A clock-skewed client computes a negative delay from the
 * date form, so the result is floored at zero rather than trusted.
 */
export function retryAfterMs(error: unknown): number | null {
  if (!(error instanceof TcmsApiError) || !error.retryAfter) return null;

  const seconds = Number(error.retryAfter);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);

  const at = Date.parse(error.retryAfter);
  if (Number.isNaN(at)) return null;
  return Math.max(0, at - Date.now());
}

/**
 * True for the failures a retry could plausibly fix. A 409 is **not** one of them: it means
 * either a concurrent edit or a state that forbids the operation, and both need the caller to
 * re-read before trying again.
 */
export function isTransient(error: unknown): boolean {
  if (error instanceof TcmsTimeoutError) return true;
  if (!(error instanceof TcmsApiError)) return false;
  return error.status === 429 || (error.status >= 500 && error.status <= 599);
}
