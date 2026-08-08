/**
 * `If-Match` and `lockVersion` — surface spec §4.3.
 *
 * The contract is explicit that *omitting `If-Match` is last-write-wins*. An edit that does not
 * thread the version silently overwrites a colleague's concurrent change with no error anywhere,
 * which is the single worst thing this surface can do: the SPA has a person who at least saw the
 * screen they were editing, and an agent has nothing.
 *
 * So the rule is stated as a **type**, not as a convention. An edit either carries a version or
 * declares in one word why it has none, and `requireIfMatch` is what a write path calls — there
 * is no spelling of "just leave it out" that does not say so.
 */
import { TcmsClientError } from '../errors.js';

/**
 * The one legitimate reason an edit sends no `If-Match`.
 *
 * Some writes have no `lockVersion` at all, and the contract records why: the run and plan status
 * transitions are preconditioned on the *state* rather than on the version, so `PUT …/status`
 * carries none. That is not the same as forgetting, and a tool description must say which of the
 * two it is rather than leave a model to infer it.
 */
export type NoVersionReason = 'state-preconditioned';

export type Concurrency =
  | { version: number }
  | { none: NoVersionReason };

/**
 * The `If-Match` value for an edit, or undefined when the operation legitimately has none.
 *
 * Throws when a version is required and absent — and the message names the read that carries it,
 * because the fix is always "read it first", never "try again".
 */
export function ifMatchFor(concurrency: Concurrency, operation: string): string | undefined {
  if ('none' in concurrency) return undefined;

  if (!Number.isInteger(concurrency.version) || concurrency.version < 0) {
    throw new TcmsClientError(
      `${operation} was given lockVersion ${String(concurrency.version)}, which is not a version. `
        + 'Read the resource first and send the lockVersion it returned.',
    );
  }

  return String(concurrency.version);
}

/**
 * Pulls the version off a resource that was just read.
 *
 * A structural consequence of §4.3 that is easy to miss: **an edit arm may only exist where the
 * paired read returns `lockVersion`.** Verified present on `Suite`, `TestCase`, `Condition`,
 * `TestPlan`, `DocPage` and the execution detail. Reading it through this function rather than at
 * each call site is what makes a resource that lacks one fail loudly at the one place that
 * knows, instead of quietly sending no header.
 */
export function versionOf(resource: unknown, operation: string): Concurrency {
  if (
    resource
    && typeof resource === 'object'
    && 'lockVersion' in resource
    && typeof (resource as { lockVersion: unknown }).lockVersion === 'number'
  ) {
    return { version: (resource as { lockVersion: number }).lockVersion };
  }

  throw new TcmsClientError(
    `${operation} needs a lockVersion and the resource it read carries none. Omitting If-Match `
      + 'is last-write-wins, so this would overwrite a concurrent edit with no error — refusing '
      + 'instead.',
  );
}

/**
 * True when a refusal is the concurrency one: somebody else saved first.
 *
 * A 409 is *not* retryable (see `isTransient`) and the distinction matters to an agent, whose
 * correct next move is to re-read, re-apply its change to the new state, and write again — not
 * to send the same body a second time, which would either fail identically or, worse, succeed
 * against a version it never saw.
 */
export function isVersionConflict(error: unknown): boolean {
  return (
    typeof error === 'object'
    && error !== null
    && 'status' in error
    && (error as { status: unknown }).status === 409
  );
}
