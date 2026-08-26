/**
 * One failure, classified — the half of a refusal a caller can *branch* on.
 *
 * `refusal.ts` composes the sentence, and the sentence is good; what it cannot do is tell a script
 * or a model whether to wait and retry, stop, or fix its own input. Until this existed both
 * adapters had to infer that from English: the MCP handed a model prose with `isError: true` and
 * nothing else, and the CLI collapsed 403, 404, 409, 429, every 5xx and every timeout into exit
 * code 1. So a pipeline could not tell *"rate limited, back off"* from *"this token will never be
 * allowed to do this"*, and would either retry the unretryable or give up on the transient.
 *
 * The information was never missing — it was **discarded at the boundary**. `describeRefusal`
 * already branches on 403/404/401/429, and `isTransient`/`retryAfterMs` have been exported from
 * this package all along with no consumer. This module is the branch taken, kept.
 *
 * It lives in the core rather than in either adapter for the reason the surface spec's §6 states
 * and §4 of the review found untrue: *"refusal copy … comes from the core, so a CLI command and
 * its MCP counterpart cannot disagree."* One classifier, two surfaces — the MCP renders it as
 * `structuredContent`, the CLI as an exit code, and neither composes its own.
 */
import { TcmsApiError, TcmsClientError, TcmsTimeoutError, isAbort, isTransient, retryAfterMs } from '../errors.js';
import { describeRefusal, type RefusedOperation } from './refusal.js';

/**
 * The closed vocabulary. Closed is the point: an open one is a string a caller must pattern-match,
 * which is the English-parsing this exists to remove.
 *
 * `failed` is the catch-all — a transport error, a client misuse, a status with no branch of its
 * own — and it is deliberately not retryable. Guessing that an unclassified failure is worth
 * retrying is how a loop starts.
 */
export type FailureCode =
  | 'cancelled'
  | 'unauthenticated'
  | 'forbidden'
  | 'not_found'
  | 'conflict'
  | 'invalid_input'
  | 'rate_limited'
  | 'timeout'
  | 'unavailable'
  | 'failed';

export interface Failure {
  code: FailureCode;
  /** The prose — `describeRefusal`'s sentence, unchanged. Safe to print or hand to a model. */
  message: string;
  /**
   * Whether trying the same call again could plausibly succeed. **Stated, never inferred.**
   *
   * A 409 is false: it means a concurrent edit or a state that forbids the operation, and both
   * need a re-read first. A timed-out *write* is the one true value that carries a caveat rather
   * than an invitation — see {@link Failure.mayHaveWritten}.
   */
  retryable: boolean;
  /** Present only when the server said so, in `Retry-After`. An invented number would be obeyed. */
  retryAfterSeconds?: number;
  /**
   * The permission to go and ask for — set **only** when the refusal is allowed to name one.
   *
   * `refusal.ts` owns that boundary (§4.5: a 404 names nothing, because naming would confirm a row
   * exists to a caller in another company). This field copies its answer rather than re-deciding.
   */
  permission?: string;
  /** Per-field rejections from a 400, keyed as the caller spelled them. */
  fields?: Record<string, string[]>;
  /** The handle a support conversation uses, when the server sent one. */
  correlationId?: string;
  /**
   * True when the call may have changed something despite failing.
   *
   * Only a timed-out write. A 429 is written by a filter before routing, so nothing half-ran and
   * the same call can simply be repeated — the two must not be confused, and this is the field
   * that keeps them apart without reading the sentence.
   */
  mayHaveWritten?: boolean;
  /** The HTTP status, when there was one. Diagnostic; branch on {@link Failure.code}. */
  status?: number;
}

/** What ran, so the copy can name it and the missing permission. */
export interface FailedOperation extends RefusedOperation {
  /** True when the call may have changed something — a write arm or a write command. */
  write?: boolean;
}

const BY_STATUS: Record<number, FailureCode> = {
  400: 'invalid_input',
  401: 'unauthenticated',
  403: 'forbidden',
  404: 'not_found',
  409: 'conflict',
  422: 'invalid_input',
  429: 'rate_limited',
};

/**
 * Classify a thrown error, composing the same sentence both surfaces already print.
 *
 * The ordering is the same as every caller's used to be, and for the same reason: a cancellation
 * is **not a failure** — the host or a Ctrl-C ended the call on purpose — so it is checked first
 * and never rendered as one.
 */
export function describeFailure(error: unknown, operation: FailedOperation): Failure {
  if (isAbort(error)) return { code: 'cancelled', message: 'Cancelled.', retryable: false };

  if (error instanceof TcmsTimeoutError) {
    /*
      A timed-out write is the one failure whose result is unknown, and the caller is the party
      that decides what happens next — so it is told, in as many words, rather than being handed
      "the request failed" and left to retry. The retry is safe precisely because every create arm
      carries an `Idempotency-Key` for the intent; saying so is what makes the caller use it.
    */
    return {
      code: 'timeout',
      message: operation.write === true
        ? `${error.message} The write may or may not have happened. Read the resource before `
          + 'trying again; a retry of this call reuses the same idempotency key and cannot '
          + 'create a second record.'
        : error.message,
      retryable: true,
      ...(operation.write === true ? { mayHaveWritten: true } : {}),
    };
  }

  if (error instanceof TcmsClientError) {
    // Includes the MCP adapter's `InputError` and the CLI's argument checks: the call was never
    // made, because the arguments could not build one. That is the caller's input, not the
    // server's state, and a retry of the same call cannot fix it.
    return { code: 'invalid_input', message: error.message, retryable: false };
  }

  if (!(error instanceof TcmsApiError)) {
    return { code: 'failed', message: error instanceof Error ? error.message : String(error), retryable: false };
  }

  const copy = describeRefusal(error, operation);
  const wait = retryAfterMs(error);
  const code = BY_STATUS[error.status]
    ?? (error.status >= 500 && error.status <= 599 ? 'unavailable' : 'failed');

  return {
    code,
    message: copy.message,
    // Read from the core's own predicate rather than re-derived here, so "which failures a retry
    // could fix" stays one answer for the SPA, the CLI and the MCP alike.
    retryable: isTransient(error),
    ...(wait === null ? {} : { retryAfterSeconds: Math.max(1, Math.ceil(wait / 1000)) }),
    ...(copy.namesPermission ? { permission: operation.permission } : {}),
    ...(error.fieldErrors ? { fields: error.fieldErrors } : {}),
    ...(error.correlationId ? { correlationId: error.correlationId } : {}),
    status: error.status,
  };
}
