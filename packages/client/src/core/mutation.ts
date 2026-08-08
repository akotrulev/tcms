/**
 * What a write carries — the one place `Idempotency-Key` and `If-Match` are decided.
 *
 * `core/idempotency.ts` and `core/concurrency.ts` each hold half of this and neither of them
 * makes a request: one produces a key, the other produces a header value, and until this module
 * existed it was the *call site* that remembered to ask for both. That is the arrangement §137
 * shipped and the arrangement this slice found insufficient, because "remember to ask" is what a
 * twelfth write command does not do.
 *
 * ## The rule that is not obvious, and that is the whole reason this file has an opinion
 *
 * **An `If-Match` the client read for itself is only honest when the request is not a delta.**
 *
 * A *flag* edit states its whole intent in its arguments — `PATCH` reads an absent key as "leave
 * it alone", so `--title x` asks for a title and nothing else, and a version read one millisecond
 * before the write is a truthful precondition. A concurrent change to another field is not a
 * conflict, and refusing it would be the header being wrong in the safe direction.
 *
 * **A whole-body edit inverts that silently.** Its body was composed from an *earlier* read — the
 * caller pulled the resource, edited three fields, and is sending the result — so its precondition
 * is the version *that* read returned, which may be several saves old. Reading a fresh version
 * here would send an `If-Match` that matches, the server would accept it, and the other author's
 * words would be gone: last-write-wins arriving **through** the mechanism that exists to prevent
 * it, with every party's code looking correct and no error anywhere.
 *
 * So `editIntent` takes the version as a discriminated input rather than a number: either the
 * caller supplies one, or it names the read that is allowed to supply it. There is no spelling of
 * "work it out" — see {@link EditVersion}.
 */
import { TcmsClientError } from '../errors.js';
import type { TcmsRequestOptions } from '../http.js';
import { ifMatchFor, versionOf, type Concurrency } from './concurrency.js';
import { newIntentKey, requireIntentKey } from './idempotency.js';

/**
 * What kind of write an operation is, declared by the command or tool that owns it.
 *
 * This is a **registry field, not a comment**: the surface spec §8.3 asks for the header coverage
 * to be asserted over the registry rather than per operation, which needs the registry to say what
 * each operation is. Four values, and the two that carry no header say so by name rather than by
 * being absent from a list — an operation missing from a list is indistinguishable from one
 * nobody has classified yet.
 */
export type WriteKind =
  /** Allocates something. Carries `Idempotency-Key`. */
  | 'create'
  /** Changes something that has a `lockVersion`. Carries `If-Match`. */
  | 'edit'
  /**
   * Names an absolute target state — the run, plan and suite status transitions. Carries
   * neither, and the contract says why on each: the precondition is the *state*, not a version
   * the client read, so there is no lost update for `If-Match` to protect and no allocation for
   * a key to deduplicate. Re-sending is already a no-op server-side.
   */
  | 'state'
  /** Removes something. Carries neither, for `state`'s reason. */
  | 'delete';

/**
 * Where an edit's `lockVersion` comes from.
 *
 * `explicit` is a version the caller holds — from a `--lock-version` flag, or from the read whose
 * body they are now sending back. `fresh` is a version this command read for itself, and it is
 * only offered to writes whose body is not a delta (see the module note).
 */
export type EditVersion =
  | { explicit: number }
  | { fresh: unknown };

export interface CreateOptions {
  /**
   * A key the caller supplied — `--idempotency-key`, or the MCP adapter's tool-call id. Absent
   * generates one, which is right for an interactive invocation and wrong for a scripted retry:
   * a fresh key per attempt makes the header look present and do nothing.
   */
  key?: string | undefined;
}

/** The per-request options a create arm sends. */
export function createIntent(options: CreateOptions = {}): TcmsRequestOptions {
  return { idempotencyKey: options.key === undefined ? newIntentKey() : requireIntentKey(options.key) };
}

/**
 * The per-request options an edit arm sends.
 *
 * `operation` is what the caller asked for in their words — it is the subject of every error
 * message this can raise, and those messages are read by somebody deciding what to do next.
 */
export function editIntent(version: EditVersion, operation: string): TcmsRequestOptions {
  const concurrency: Concurrency =
    'explicit' in version ? { version: version.explicit } : versionOf(version.fresh, operation);

  const ifMatch = ifMatchFor(concurrency, operation);
  // `ifMatchFor` returns undefined only for the `none` arm, which this function never builds —
  // an edit that legitimately has no version is a `state` write and does not come through here.
  if (ifMatch === undefined) {
    throw new TcmsClientError(`${operation} produced no If-Match, which an edit may never do.`);
  }

  return { ifMatch };
}

/**
 * Refuses a whole-body edit that names no version.
 *
 * Separate from {@link editIntent} because the caller has to be told *what to do*, and the answer
 * is specific: read the resource, take the `lockVersion` it returned, pass it. A generic "version
 * required" would read as a flag somebody forgot rather than as the reason the flag exists.
 */
export function requireExplicitVersion(
  version: number | undefined,
  operation: string,
  flag = '--lock-version',
): EditVersion {
  if (version === undefined) {
    throw new TcmsClientError(
      `${operation} sends a whole body, so it needs the lockVersion of the read that body came `
        + `from — pass ${flag}. Reading a fresh version here would send an If-Match that matches `
        + 'a state this body was never based on, which silently overwrites whoever saved in '
        + 'between: exactly what If-Match exists to prevent.',
    );
  }

  if (!Number.isInteger(version) || version < 0) {
    throw new TcmsClientError(`${flag} takes a whole number; ${String(version)} is not one.`);
  }

  return { explicit: version };
}
