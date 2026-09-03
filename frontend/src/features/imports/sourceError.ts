import { ApiError } from '../../api/client';
import { describeError } from '../../lib/describeError';

/**
 * A failure of an import operation, worded for the one person who can act on it.
 *
 * `describeError` is the product's single place an error becomes user-facing copy, and it answers
 * every `5xx` with *"The server ran into a problem. Try again in a moment."* — which is right, and
 * is right for a reason: our own server's `detail` on a 500 is written for whoever is holding a
 * stack trace, and it has already once put *"The body is not valid JSON — check the request against
 * the schema in the API contract"* on an end user's screen.
 *
 * **A `502` from an import operation is the one 5xx that is not ours.** The contract says so
 * explicitly: *"The source system rejected the credential, refused the account, or did not answer.
 * The `detail` is the source's own message and is shown only to the person whose credential it
 * is."* That last clause is what makes this safe and what makes it necessary — the only person who
 * ever sees this screen is the one who just pasted the token, and *"the server ran into a problem"*
 * sends them looking for a fault in this product when the fault is a revoked token in theirs.
 *
 * So the narrowing is deliberately as small as it can be: **502 only, on this module's screens
 * only**, falling through to `describeError` for everything else — a 400 on a malformed site URL, a
 * 403 the caller lacks `issue:import` for, a 409 on a second import of the same project. Widening
 * `describeError` itself was the alternative and was refused: it would change the wording of every
 * 502 in the product from one place, including ones whose `detail` nobody has read.
 */
export function describeImportError(error: unknown): { title: string; body: string } {
  if (error instanceof ApiError && error.status === 502 && error.detail !== '') {
    return { title: error.title ?? 'The source system did not answer', body: error.detail };
  }
  const described = describeError(error);
  return { title: described.title, body: described.body };
}
