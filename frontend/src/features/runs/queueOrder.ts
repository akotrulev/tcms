import type { TestExecutionSummary } from '../../api/types';
import { compareCaseKeys } from '../../lib/format';

/**
 * The order Split mode's queue rail walks a run in — **by case key, and deliberately not by
 * what still needs doing.**
 *
 * `RunDetailPage` sorts the same executions unexecuted-first, then failures, then the rest. That
 * is right for that screen and wrong for this one, and the difference is not an oversight:
 *
 *   - The run page is a **triage** view. It is opened to answer "what is left" and "what went
 *     wrong", it carries filter chips for exactly those two questions, and nothing on it moves
 *     while it is read.
 *   - The rail is a **position** view, read *while marking*. Under a needs-doing sort, recording
 *     a result re-ranks the row the tester is standing on and it jumps out from under them —
 *     every mark reshuffles the list, and "the next case" stops meaning anything. The status dot
 *     already says what is done; the order does not have to say it too.
 *
 * A checklist walked front to back is also what a tester expects a run to be, and a stable order
 * is what makes "case 7 of 412" a fact rather than a coincidence of when they last saved.
 *
 * Pure and separate from the component so the ordering is assertable without mounting a rail.
 */
export function orderedQueue(executions: TestExecutionSummary[]): TestExecutionSummary[] {
  // Copied before sorting: `executions` belongs to the TanStack Query cache, and sorting in
  // place mutates cached data every consumer of `useRun` shares.
  return [...executions].sort(
    // Key first, id as the unique tiebreaker. Two executions in one run cannot share a case, so
    // the id never actually decides — it is here so the comparator is a total order regardless,
    // which is what stops rows swapping between renders if that assumption ever stops holding.
    (a, b) => compareCaseKeys(a.caseKey, b.caseKey) || a.id.localeCompare(b.id),
  );
}
