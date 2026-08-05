/**
 * The suite tree, and the two case counts that are NOT the same number.
 *
 * Suite case counts are derived, never stored — a stored count drifts the moment a case
 * moves suite, and the drift is invisible until someone reconciles by hand.
 */
import type { Suite } from '../../api/types';
import { db } from './store';

/**
 * Every live case in this suite's **subtree**.
 *
 * What "is this folder empty" means — the question the delete asks, where a case three levels
 * down is still a reason to refuse.
 */
export function suiteCaseCount(suiteId: string): number {
  const descendants = descendantSuiteIds(suiteId);
  // `suiteId` is nullable on the wire — an unfiled case belongs to no folder, so it is in no
  // subtree and can never keep one from being deleted.
  return db.cases.filter((c) => c.suiteId !== null && descendants.has(c.suiteId)).length;
}

/**
 * The suite's **resolved membership** — what `Suite.caseCount` means on the wire since §96.
 *
 * Three arms, unioned and deduped: filed here (`suiteId`), added here individually (`caseIds`),
 * and every live case carrying any of the suite's `tagRules`. The server's `SuiteMembership` is
 * the same union, and a mock that answered only the first arm would let a screen be built against
 * a count the product does not produce — §81's finding, and §87's.
 *
 * `SuiteView`'s own comment says it: the count is the suite's own, and the SPA's tree rolls the
 * subtree up. This mock answered the subtree count instead, so every parent folder rendered its
 * descendants twice — Payments showed 40 for a project with 26 cases, because the tree added the
 * children to a number that already contained them.
 *
 * Found by the case-delete browser pass: deleting one case dropped that folder's count by two,
 * which is the only symptom that makes the double count visible rather than merely large.
 */
export function suiteOwnCaseCount(suiteId: string): number {
  const suite = db.suites.find((s) => s.id === suiteId);
  if (!suite) return 0;
  const rules = suite.tagRules ?? [];
  const added = new Set(suite.caseIds ?? []);
  return db.cases.filter(
    (c) =>
      c.suiteId === suiteId ||
      added.has(c.id) ||
      // Unioned across the rules, never intersected: a suite claiming `smoke` and `payments`
      // holds every case carrying EITHER. The case grid's `tag` filter ANDs, which is the
      // opposite and deliberate — a collection widens, a list narrows.
      rules.some((tag) => c.tags.includes(tag)),
  ).length;
}

export function descendantSuiteIds(suiteId: string): Set<string> {
  const out = new Set<string>([suiteId]);
  let added = true;
  while (added) {
    added = false;
    for (const s of db.suites) {
      if (s.parentId && out.has(s.parentId) && !out.has(s.id)) {
        out.add(s.id);
        added = true;
      }
    }
  }
  return out;
}

export function suiteTree(): Suite[] {
  return db.suites.map((s) => ({ ...s, caseCount: suiteOwnCaseCount(s.id) }));
}
