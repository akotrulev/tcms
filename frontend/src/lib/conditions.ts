/**
 * How widely a library condition is already used, in one line (§95).
 *
 * ## Its own module for the reason `lib/classification.ts` is
 *
 * Exporting a function beside a component defeats Fast Refresh — the trade `caseSchema.ts` records
 * — and this has two consumers already (`ConditionsPanel`'s picker rows and `ConditionEditDialog`'s
 * impact warning). A second implementation is how one honest sentence becomes two that disagree
 * about the same numbers.
 *
 * ## The two counts are NEVER added, and this function is where that is enforced
 *
 * `usageCount` counts distinct live **cases** attaching the entry directly. `suiteUsageCount`
 * counts **suites**, each of which propagates to every case beneath it — a case reach no client
 * can compute, because the walk is over the suite tree and the server deliberately does not
 * publish that figure (it would need an `ltree` subtree query the library read cannot express).
 *
 * So the suite half is worded as what it is. Summing them, or rendering the suite count as though
 * it were cases, would put a number in front of an author that nothing in the system supports —
 * and this string is what somebody decides from before changing three hundred cases at once.
 */
import type { Condition } from '../api/types';

export function impactSummary(condition: Condition): string {
  const parts: string[] = [
    condition.usageCount === 1 ? 'Used by 1 case' : `Used by ${condition.usageCount} cases`,
  ];
  if (condition.suiteUsageCount > 0) {
    parts.push(
      condition.suiteUsageCount === 1
        ? 'attached to 1 suite — every case beneath inherits it'
        : `attached to ${condition.suiteUsageCount} suites — every case beneath inherits them`,
    );
  }
  return parts.join(', ');
}
