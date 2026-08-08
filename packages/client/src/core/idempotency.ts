/**
 * `Idempotency-Key` — surface spec §4.2.
 *
 * The distinction the whole mechanism rests on: **the key belongs to the intent, not to the
 * attempt.** Every retry of "create this suite" carries the same key, so a server that already
 * processed the first attempt answers 200 with what that intent created instead of creating a
 * second. A key generated per attempt makes the header look right and do nothing — the
 * contract's own parameter description says so in as many words.
 *
 * This matters more here than in the SPA. A person who times out sees a spinner and hesitates;
 * an agent retries immediately, and the MCP adapter derives its key from the tool-call id
 * precisely so that a model's retry of the same call is the same intent.
 */
import { TcmsClientError } from '../errors.js';

/**
 * The contract's `maxLength`, which is also the backend's cap.
 *
 * §136 found `MAX_IDEMPOTENCY_KEY = 200` declared in seven services across four modules with
 * nothing comparing them, and answered it with a computed check that pins the contract's
 * `maxLength` to the code's cap. This constant is an eighth declaration of the same number, on
 * the other side of the wire — so `idempotency.test.ts` reads it out of `openapi.yaml` rather
 * than trusting it, which is the same check arriving from the client side.
 */
export const MAX_IDEMPOTENCY_KEY = 200;

/**
 * A key for one user intent.
 *
 * `randomUUID` needs a secure context in a browser; in Node it is always available, so unlike
 * the SPA's version this needs no fallback — and a fallback here would be worse than none, since
 * a weaker key on a *write* dedupe is a collision between two different intents.
 */
export function newIntentKey(): string {
  return crypto.randomUUID();
}

/**
 * Validates a key the caller supplied — `--idempotency-key` for a scripted retry, or the MCP
 * adapter's tool-call id.
 *
 * Checked here rather than left to the server because the server's refusal arrives *after* the
 * request, and the whole point of the key is that the caller cannot tell whether a failed
 * request landed. A 400 for an over-long key is unambiguous; a timeout is not.
 */
export function requireIntentKey(key: string): string {
  const trimmed = key.trim();

  if (!trimmed) {
    throw new TcmsClientError('An idempotency key cannot be blank. Omit it, or give it a value.');
  }

  if (trimmed.length > MAX_IDEMPOTENCY_KEY) {
    throw new TcmsClientError(
      `An idempotency key is at most ${MAX_IDEMPOTENCY_KEY} characters; this one is `
        + `${trimmed.length}.`,
    );
  }

  return trimmed;
}
