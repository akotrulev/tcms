/**
 * Which verdict a case's step marks point at.
 *
 * Its own module for `stepReorder.ts`'s reason: the rules are four short branches whose
 * *interaction* is the part that can be wrong, and none of them is reachable from a component
 * test without mounting the whole execution screen — a permission gate, three queries and a
 * mutation — to assert one word.
 *
 * **This is a suggestion and the screen treats it as one.** It preselects nothing, disables
 * nothing, and never submits. A tester may pass a case whose step deviated cosmetically, and may
 * fail a case whose every step passed because the thing as a whole was wrong: step marks are
 * evidence about steps, and the verdict is a statement about the case. The value of computing it
 * is that marking fifteen steps and then choosing the obvious overall result by hand is the same
 * judgement made twice, and the second one is where a mis-click turns a failing case green.
 */

/** The kinds a step mark can propose. Never `SKIPPED` — see `suggestedVerdict`. */
export type SuggestedVerdict = 'PASSED' | 'FAILED' | 'BLOCKED';

/**
 * @param marks one entry per step, in step order: the `StatusKind` it is marked with, or `null`
 *              for a step nobody has marked yet. Kinds rather than status ids, so the rules read
 *              as the vocabulary they are about and the caller owns the id → kind resolution it
 *              already does for the buttons.
 */
export function suggestedVerdict(marks: (string | null)[]): SuggestedVerdict | null {
  // A case with no structured steps proposes nothing — there is no evidence to derive from, and
  // `every` over an empty array is vacuously true, which would have proposed PASSED for it.
  if (marks.length === 0) return null;

  // Failed outranks everything, and it is the ONE rule that fires on a half-filled checklist.
  // A step that failed is a statement about the software; it stays true whatever else could not
  // be run, and a tester who has found the defect at step 3 of 15 should not have to mark the
  // remaining twelve before the screen agrees with them.
  if (marks.some((mark) => mark === 'FAILED')) return 'FAILED';

  // Everything below requires a complete checklist. "All passed so far" is not "passed", and a
  // green suggestion over an unfinished run is the single worst thing this function could say.
  if (marks.some((mark) => mark === null)) return null;

  // Blocked beats skipped-and-passed: if any step could not be run, the case was not fully
  // exercised, and Blocked is the verdict that says so. The screen still routes Blocked through
  // the precondition dialog, which is where it has to name what blocked it.
  if (marks.some((mark) => mark === 'BLOCKED')) return 'BLOCKED';

  // Passed only when every step passed. A skipped step is deliberately NOT folded in: the case
  // was partly not exercised, and there is no honest single verdict for that — which is why
  // SKIPPED is not in `SuggestedVerdict` and this returns null instead of guessing.
  return marks.every((mark) => mark === 'PASSED') ? 'PASSED' : null;
}
