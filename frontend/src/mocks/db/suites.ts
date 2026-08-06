/**
 * The suite list, and the two case counts that are NOT the same number.
 *
 * Suite case counts are derived, never stored — a stored count drifts the moment a case
 * moves suite, and the drift is invisible until someone reconciles by hand.
 */
import type { Suite } from '../../api/types';
import { db } from './store';

/**
 * Every live case **filed** in this suite.
 *
 * What "is this suite empty" means — the question the delete asks. It walked a subtree until §98,
 * where a case three levels down was still a reason to refuse; there is no subtree now, and the
 * arm is deliberately `suiteId` alone rather than the resolved membership below. Deleting a suite
 * that merely *claims* a case by tag takes the claim, not the case.
 */
export function suiteCaseCount(suiteId: string): number {
  // `suiteId` is nullable on the wire — an unfiled case is filed nowhere, so it can never keep a
  // suite from being deleted.
  return db.cases.filter((c) => c.suiteId === suiteId).length;
}

/**
 * The suite's **resolved membership** — what `Suite.caseCount` counts and, since §98, what the
 * case grid's `?suiteId=` filter selects.
 *
 * Three arms, unioned and deduped: filed here (`suiteId`), added here individually (`caseIds`),
 * and every live case carrying any of the suite's `tagRules`. The server's `SuiteMembership` is
 * the same union, and a mock that answered only the first arm would let a screen be built against
 * a count the product does not produce — §81's finding, and §87's.
 *
 * **THE COUNT AND THE FILTER ARE ONE FUNCTION HERE BECAUSE THEY WERE TWO ANSWERS BEFORE §98.**
 * The count has been this union since §96 while `?suiteId=` selected the *filed* arm alone — so a
 * suite whose whole definition is a tag rule showed a rail count of 12 over a grid of 0, with
 * nothing on either screen to say which was wrong. Invisible while suites were mostly folders
 * people filed into; unmissable now that a tag rule is the ordinary way to define one. Returning
 * ids rather than a number is what makes the two physically incapable of disagreeing.
 *
 * `SuiteView`'s own comment used to say the count is the suite's own while the SPA's tree rolled
 * the subtree up, and this mock answered the *subtree* count instead — so every parent folder
 * rendered its descendants twice, and Payments showed 40 for a project with 26 cases. Found by the
 * case-delete browser pass: deleting one case dropped that folder's count by two, which is the
 * only symptom that makes a double count visible rather than merely large. §98 removes the
 * rollup at both ends, so the two numbers can no longer be different in that direction.
 */
export function suiteMemberCaseIds(suiteId: string): Set<string> {
  const suite = db.suites.find((s) => s.id === suiteId);
  if (!suite) return new Set();
  const rules = suite.tagRules ?? [];
  const added = new Set(suite.caseIds ?? []);
  return new Set(
    db.cases
      .filter(
        (c) =>
          c.suiteId === suiteId ||
          added.has(c.id) ||
          // Unioned across the rules, never intersected: a suite claiming `smoke` and `payments`
          // holds every case carrying EITHER. The case grid's `tag` filter ANDs, which is the
          // opposite and deliberate — a collection widens, a list narrows.
          rules.some((tag) => c.tags.includes(tag)),
      )
      .map((c) => c.id),
  );
}

export function suiteOwnCaseCount(suiteId: string): number {
  return suiteMemberCaseIds(suiteId).size;
}

export function suiteList(): Suite[] {
  return db.suites.map((s) => ({ ...s, caseCount: suiteOwnCaseCount(s.id) }));
}
