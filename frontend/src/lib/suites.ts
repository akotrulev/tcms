import type { Suite } from '../api/types';

/**
 * How many live cases sit in one suite **and everything beneath it**.
 *
 * `Suite.caseCount` is the folder's *own* rows — the contract says so, because the tree sums a
 * node's count with its children's and a subtree count on the wire would be counted again at every
 * ancestor. Every screen that talks about a folder as a whole therefore has to do the rollup, and
 * this is the one place it is written.
 *
 * **Why it exists as a lookup at all**, when `SuiteTree.buildTree` already rolls the same numbers
 * up: that one computes them *structurally*, as it builds the nodes, and only for a caller that
 * wants the whole tree. The suite history page wants one number for one folder and has no tree —
 * and reaching for `suite.caseCount` there is what put "3 cases now" in the header of a page whose
 * grid was reporting runs over 47 (§52). Two numbers on one screen with nothing to identify the
 * wrong one is the defect §40 found on plan statistics, and the reason this is shared rather than
 * inlined. `suites.test.ts` asserts this and the tree's rollup agree over the same fixture.
 *
 * Walks `parentId`, not `path`: the contract states parentId is what the tree is built from, and
 * the two are independent — a reparent rewrites paths, and a consumer that parsed the path would
 * need to know the label-sanitizing rules to get back to a row.
 *
 * An unknown id answers 0 rather than throwing: a suite that has just been deleted in another tab
 * is an ordinary state of this argument, and a header is not a place to raise from.
 */
export function subtreeCaseCount(suites: Suite[], suiteId: string | undefined): number {
  if (!suiteId) return 0;

  const childrenOf = new Map<string, Suite[]>();
  for (const suite of suites) {
    if (!suite.parentId) continue;
    const bucket = childrenOf.get(suite.parentId);
    if (bucket) bucket.push(suite);
    else childrenOf.set(suite.parentId, [suite]);
  }

  const root = suites.find((suite) => suite.id === suiteId);
  if (!root) return 0;

  // Iterative, and visited-guarded: a cycle in parentId cannot be drawn as a tree, but it can
  // certainly be walked forever, and a header is a bad place to hang the tab.
  const seen = new Set<string>([root.id]);
  const stack: Suite[] = [root];
  let total = 0;
  while (stack.length > 0) {
    const node = stack.pop() as Suite;
    total += node.caseCount;
    for (const child of childrenOf.get(node.id) ?? []) {
      if (seen.has(child.id)) continue;
      seen.add(child.id);
      stack.push(child);
    }
  }
  return total;
}
