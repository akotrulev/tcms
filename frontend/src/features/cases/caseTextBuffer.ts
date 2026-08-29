import { safeStorage } from '../../lib/safeStorage';

/**
 * The Write surface's raw text, buffered in the browser — 003 C4's last clause.
 *
 * ## Why the text needs a buffer of its own
 *
 * The two draft buffers either side of it hold `toCasePayload`'s output, which is *parsed* state:
 * a title, a tag list, step rows. Under D1 the thing the author is actually writing is **text**,
 * and the interesting moment is precisely the one where that text does not parse — a half-typed
 * `Scenario:` line, a table with a missing pipe. There is no payload for such a document, so a
 * buffer that stored only payloads would drop exactly the work that is hardest to retype.
 *
 * C4 states the requirement and the shape in one sentence: the buffer stores the raw text
 * *"alongside the payload, so a restore after a crash returns unparseable work exactly as typed
 * **rather than needing a server round trip to exist**"*. That last clause is why this is
 * `localStorage` and not a member on `CaseDraft.content` — the whole point is that the text is
 * recoverable without asking the server to render or parse anything.
 *
 * ## One key per case, and `new` is a case
 *
 * `useNewCaseDraft`'s rule, for its reason: a single key would offer one project's half-written
 * scenario on another project's editor, which is the tenancy boundary breached in the one place no
 * server check can see. The create form's buffer is keyed `new` under its project, so two projects
 * do not share it either.
 *
 * ## Every failure is swallowed
 *
 * Private-mode Safari throws on `setItem`, a full quota throws, a corrupted value throws on parse.
 * None is worth interrupting somebody's typing over: the work is in the textarea, which is the
 * safest place it can be, and a failed backup of unsent work is not a fact the author can act on.
 */

export const caseTextKey = (projectId: string, caseId: string | null | undefined): string =>
  `tcms.caseText.${projectId}.${caseId ?? 'new'}`;

export interface BufferedText {
  /** ISO, from the browser's clock — the only clock this buffer has. */
  updatedAt: string;
  text: string;
}

export function readCaseText(key: string): BufferedText | null {
  const parsed: unknown = safeStorage.getJSON(key);
  if (parsed === null) return null;
  /*
    Decoded defensively, `fromCasePayload`'s rule: this value was never validated and may have
    been written by an older build. A shape that is not the envelope reads as no buffer rather
    than throwing on the one screen whose job is not losing work.
  */
  if (
    typeof parsed !== 'object'
    || typeof (parsed as BufferedText).text !== 'string'
    || typeof (parsed as BufferedText).updatedAt !== 'string'
  ) {
    return null;
  }
  return parsed as BufferedText;
}

export function writeCaseText(key: string, text: string): void {
  safeStorage.setJSON(key, { updatedAt: new Date().toISOString(), text } satisfies BufferedText);
}

export function clearCaseText(key: string): void {
  safeStorage.remove(key);
}
