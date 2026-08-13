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
 * counts **suites**, each of which propagates to the cases filed in it — a case reach no client
 * can compute, because a suite's `caseCount` is its *resolved* membership across three arms and
 * only the filing arm inherits conditions. The server deliberately does not publish that figure.
 *
 * ~~the walk is over the suite tree … an `ltree` subtree query the library read cannot express~~ →
 * **stale since §98**, which flattened suites: there is no subtree and no walk. The conclusion
 * survives its reason, which is why it is corrected rather than deleted.
 *
 * So the suite half is worded as what it is. Summing them, or rendering the suite count as though
 * it were cases, would put a number in front of an author that nothing in the system supports —
 * and this string is what somebody decides from before changing three hundred cases at once.
 *
 * ## The suite branch below had never once rendered
 *
 * `suiteUsageCount` counts `suite_condition` rows, and until §186 nothing in the product could
 * create one — so the count was 0 in every deployment and the branch was dead the whole time it
 * was wrong. It said "every case **beneath**" for eighty-eight slices after §98 removed the thing
 * a case could be beneath, and no screen could show it. That is the same shape as §156's
 * all-clear: copy about a state nothing could reach goes stale silently, and the day it becomes
 * reachable is the day it starts lying.
 */
import type { Condition } from '../api/types';

export function impactSummary(condition: Condition): string {
  const parts: string[] = [
    condition.usageCount === 1 ? 'Used by 1 case' : `Used by ${condition.usageCount} cases`,
  ];
  if (condition.suiteUsageCount > 0) {
    parts.push(
      condition.suiteUsageCount === 1
        ? 'attached to 1 suite — every case filed there inherits it'
        : `attached to ${condition.suiteUsageCount} suites — every case filed there inherits them`,
    );
  }
  return parts.join(', ');
}
