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
 * The cases filed **directly** in this suite — what `Suite.caseCount` means on the wire.
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
  return db.cases.filter((c) => c.suiteId === suiteId).length;
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
