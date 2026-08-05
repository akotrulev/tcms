/**
 * How the execution screen is laid out, and where that choice lives.
 *
 * **In the URL**, per the project's standing rule that view state belongs in the address bar
 * (`useCaseFilters`, and §93's `?tab=` on the plan detail). A tester who works in Grid and pastes
 * a link to a colleague sends them the screen they were describing; one who works in Focus keeps
 * it across a reload, a session recovery, and the version-skew reload `VersionSkewWatcher` may
 * perform mid-run. Component state would lose all three.
 *
 * The three modes answer three different questions, which is why they are modes and not a
 * preference toggle:
 *
 *   - **Focus** — one case, full width, nothing else on screen. The careful manual pass: a long
 *     case with fifteen steps the tester is reading as they go.
 *   - **Split** — the queue beside the detail. The default, and the ordinary working shape: the
 *     next case is one click away and the tester can see how much is left.
 *   - **Grid** — every case in one table with the verdict buttons inline. Re-running cases whose
 *     behaviour the tester already knows, where opening each one is the whole cost.
 *
 * All three write the same result through the same mutation, and **Blocked routes through the
 * precondition dialog in every one of them** — the rule that stops a broken environment being
 * recorded as a failing feature, and the reason Grid's B opens a dialog rather than writing.
 */

export const EXECUTION_MODES = ['focus', 'split', 'grid'] as const;

export type ExecutionMode = (typeof EXECUTION_MODES)[number];

/** The mode a URL with no `?mode=` means — the ordinary working shape, not the narrowest one. */
export const DEFAULT_EXECUTION_MODE: ExecutionMode = 'split';

/**
 * Reads the mode out of a query string, falling back rather than trusting it.
 *
 * A hand-edited or stale `?mode=` is an ordinary thing to receive — a link from a colleague on an
 * older deployment, a typo, a mode that was renamed. Every one of them must land on a working
 * screen: an unrecognised value that reached the layout as-is would render no queue AND no detail,
 * which is a blank page produced by a query parameter.
 */
export function parseExecutionMode(value: string | null): ExecutionMode {
  return (EXECUTION_MODES as readonly string[]).includes(value ?? '')
    ? (value as ExecutionMode)
    : DEFAULT_EXECUTION_MODE;
}

/**
 * What `?mode=` should read for a chosen mode — **null for the default**, meaning "drop the
 * parameter".
 *
 * So the ordinary URL stays clean and, more usefully, two links to the same screen are the same
 * string: `?mode=split` and no parameter at all are one view, and a reader comparing two pasted
 * links should not have to know that.
 */
export function executionModeParam(mode: ExecutionMode): string | null {
  return mode === DEFAULT_EXECUTION_MODE ? null : mode;
}
