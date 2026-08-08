/**
 * A handler's value, or its failure, as the host reads it.
 *
 * ## Why a refusal is a *result* and not a protocol error
 *
 * MCP distinguishes the two, and the distinction is the whole point here: a JSON-RPC error means
 * *the call could not be made*, and the model never sees its text as something to reason about. A
 * 403 is the opposite — the call was made, the server answered, and the answer is exactly what
 * the model should tell the user. So every refusal comes back as `isError: true` with content the
 * **core** composed (`core/refusal.ts`), and the protocol error is reserved for a request this
 * server could not turn into a call at all.
 *
 * That is also what keeps §4.5's boundary in one place. `describeRefusal` knows that a 403 past
 * the read gate may name the permission and that a 404 may name nothing; this file knows only how
 * to put a string in an envelope. A handler that composed its own sentence would be a second
 * implementation of the rule whose failure is a security finding.
 */
import { TcmsTimeoutError, describeRefusal, isAbort } from '@tcms/client';

export interface ToolResult {
  content: { type: 'text'; text: string }[];
  isError?: boolean;
  structuredContent?: Record<string, unknown>;
}

/**
 * A successful answer.
 *
 * JSON text, pretty-printed, and **not** prose. The reader is a model with a context budget and
 * a downstream tool call to compose: it needs the ids and keys back verbatim, in a shape it can
 * quote into the next call, which is the same argument `--json` on the CLI is the composable form
 * for a shell. Rendering a table here would cost tokens to produce something the model would have
 * to parse back.
 */
export function ok(value: unknown): ToolResult {
  return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }] };
}

export interface Attempt {
  /** What the caller asked for, in their words — "approve this case". */
  what: string;
  /**
   * The permission the surface declares for **the arm that ran**, not for the tool.
   *
   * A multi-arm tool refused on its edit arm must name `case:edit`, and naming the tool's first
   * gate key instead would send the user to an administrator asking for the wrong thing. The
   * dispatcher knows which arm the input selected; this file does not, which is why it is a
   * parameter.
   */
  permission: string;
  /** True when the call may have changed something. Only a write gets the "unknown result" copy. */
  write: boolean;
}

/**
 * A failure, in the words the core allows.
 *
 * The API's 403 body is a fixed sentence naming no key, deliberately, so the declaration carried
 * in {@link Attempt} is the only thing that can tell a refused caller what to ask for.
 */
export function failed(error: unknown, attempt: Attempt): ToolResult {
  if (isAbort(error)) {
    // A cancellation is not a failure. The host cancelled; saying "could not" would have the
    // model apologise for something the user did on purpose, and possibly retry it.
    return { content: [{ type: 'text', text: 'Cancelled.' }], isError: true };
  }

  if (error instanceof TcmsTimeoutError) {
    /*
      A timed-out **write** is the one failure whose result is unknown, and the model is the party
      that decides what happens next — so it is told, in as many words, rather than being handed
      "the request failed" and left to retry. The retry is safe precisely because every create arm
      carries an `Idempotency-Key` for the intent; saying so is what makes the model use it.
    */
    return {
      content: [{
        type: 'text',
        text: attempt.write
          ? `${error.message} The write may or may not have happened. Read the resource before `
            + 'trying again; a retry of this call reuses the same idempotency key and cannot '
            + 'create a second record.'
          : error.message,
      }],
      isError: true,
    };
  }

  const copy = describeRefusal(error, { what: attempt.what, permission: attempt.permission });
  return { content: [{ type: 'text', text: copy.message }], isError: true };
}
