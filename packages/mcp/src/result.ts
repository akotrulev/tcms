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
 * That is also what keeps §4.5's boundary in one place. `describeFailure` — and `describeRefusal`
 * under it — knows that a 403 past the read gate may name the permission and that a 404 may name
 * nothing; this file knows only how to put a string in an envelope. A handler that composed its
 * own sentence would be a second implementation of the rule whose failure is a security finding.
 *
 * ## Every result carries a machine-readable half beside the prose
 *
 * The prose stays exactly as it was — it is good prose, and a model reads it. What sits beside it
 * now is `structuredContent`, declared here since the package was written and assigned nowhere:
 * one envelope, `{ ok }` either way, and on a failure a **closed** `code` with `retryable` stated
 * rather than implied. Without it the one decision a model must get right — wait and retry, or
 * stop — was recoverable only by pattern-matching an English sentence that the core had composed
 * from a branch it had already taken. See `client/src/core/outcome.ts`.
 */
import { TcmsApiError, describeFailure } from '@tcms/client';

export interface ToolResult {
  content: { type: 'text'; text: string }[];
  isError?: boolean;
  structuredContent?: Record<string, unknown>;
}

/**
 * The envelope, declared as every tool's `outputSchema`.
 *
 * One schema for the whole surface rather than one per tool: what a caller must be able to branch
 * on is the *outcome*, and `data` is already described by the tool that produced it. Declaring a
 * per-tool response shape would be a second copy of `openapi.yaml` — the thing this package spends
 * a contract test per slice not becoming.
 *
 * `data` is left unconstrained on purpose. A host validates `structuredContent` against this, and a
 * schema that guessed at each payload would refuse correct answers.
 */
export const RESULT_SCHEMA = {
  type: 'object' as const,
  properties: {
    ok: { type: 'boolean', description: 'False on any refusal, timeout or cancellation.' },
    data: { description: "The tool's answer, when `ok` is true. Shape varies by tool." },
    error: {
      type: 'object',
      description: 'Present when `ok` is false.',
      properties: {
        code: {
          type: 'string',
          enum: [
            'cancelled', 'unauthenticated', 'forbidden', 'not_found', 'conflict',
            'invalid_input', 'rate_limited', 'timeout', 'unavailable', 'failed',
          ],
          description: 'A closed vocabulary — branch on this, never on the message.',
        },
        message: { type: 'string', description: 'The same sentence as the text content.' },
        retryable: {
          type: 'boolean',
          description: 'Whether repeating this exact call could succeed. Stated, not inferred.',
        },
        retryAfterSeconds: { type: 'number', description: 'Only when the server said so.' },
        permission: {
          type: 'string',
          description: 'The permission to ask an administrator for — only when it may be named.',
        },
        mayHaveWritten: {
          type: 'boolean',
          description: 'A timed-out write. Read the resource before retrying.',
        },
        fields: { type: 'object', description: 'Per-field rejections, keyed as this tool spells them.' },
        correlationId: { type: 'string' },
      },
      required: ['code', 'message', 'retryable'],
    },
  },
  required: ['ok'],
};

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
  return {
    content: [{ type: 'text', text: JSON.stringify(value, null, 2) }],
    structuredContent: { ok: true, data: value },
  };
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
  const failure = describeFailure(error, {
    what: attempt.what,
    permission: attempt.permission,
    write: attempt.write,
  });

  return {
    content: [{ type: 'text', text: failure.message + fieldDetail(error) }],
    isError: true,
    // The prose above and this object say the same thing; only one of them can be branched on.
    structuredContent: { ok: false, error: { ...failure } },
  };
}

/**
 * The per-field messages a validation rejection carries, appended to the sentence above.
 *
 * **Without this a model is told "Check the highlighted fields." and nothing else** — the API's
 * `detail` for a 400 is copy written for a form, and the fields it refers to are in the `errors`
 * map that only `@tcms/cli` was reading. The model cannot see a screen, so the one caller that
 * literally cannot act on that sentence was the only one receiving it alone; its next move is to
 * resend the same body, which is the loop §141 spent a slice closing one status over.
 *
 * Found by the first live pass: `tcms_write_case` with an edit that omitted three required fields
 * answered with the form's sentence and no field names.
 *
 * Keys are spelled as on the input schema, so they name the tool's own argument — which is what
 * makes this actionable rather than merely longer.
 */
function fieldDetail(error: unknown): string {
  if (!(error instanceof TcmsApiError) || !error.fieldErrors) return '';

  const lines = Object.entries(error.fieldErrors)
    .map(([field, messages]) => `  ${field}: ${messages.join(' ')}`);

  return lines.length === 0 ? '' : `\n${lines.join('\n')}`;
}
