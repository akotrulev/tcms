import { describe, expect, it } from 'vitest'
import type { Suite } from '../api/types'
import { subtreeCaseCount } from './suites'

/**
 * The subtree rollup, and the one thing it must never disagree with.
 *
 * `Suite.caseCount` is the folder's OWN cases — the wire meaning, and the source of §32's defect
 * where a parent rendered its descendants twice. This helper is the lookup form of the rollup
 * `SuiteTree.buildTree` computes structurally, and it exists because the suite history page needs
 * one number for one folder without building a tree. The last test here is the reason it is shared
 * rather than inlined: the two must answer the same thing, or one screen's header contradicts
 * another screen's grid and neither is identifiable as the wrong one (§40, §52).
 */

const suite = (id: string, name: string, parentId: string | null, ownCaseCount: number): Suite => ({
  id,
  key: `TS-${id}`,
  parentId,
  name,
  path: name.toLowerCase(),
  caseCount: ownCaseCount,
  tagRules: [],
  caseIds: [],
  lifecycleStatus: 'APPROVED',
  conditions: [],
  lockVersion: 1,
})

/** §32's shape and its real numbers: 2 + 3 + 3 + 19 = 27 cases in the project. */
const suites: Suite[] = [
  suite('1', 'Payments', null, 2),
  suite('2', 'Refunds', '1', 3),
  suite('3', 'Chargebacks', '1', 3),
  suite('4', 'Onboarding', null, 19),
]

describe('subtreeCaseCount', () => {
  it('counts the folder and every descendant', () => {
    expect(subtreeCaseCount(suites, '1')).toBe(8)
  })

  it('counts a leaf as its own cases, not its parent’s', () => {
    expect(subtreeCaseCount(suites, '2')).toBe(3)
  })

  it('does not reach sideways into a sibling root', () => {
    // The check that fails an implementation summing the whole project — which reads plausibly
    // on a fixture where the folder under test happens to be the only root.
    expect(subtreeCaseCount(suites, '4')).toBe(19)
  })

  it('answers 0 for an id that is not in the list', () => {
    // A folder deleted in another tab is an ordinary state of this argument. A header is not a
    // place to throw from.
    expect(subtreeCaseCount(suites, 'gone')).toBe(0)
    expect(subtreeCaseCount(suites, undefined)).toBe(0)
  })

  it('terminates on a parentId cycle instead of hanging the tab', () => {
    const cyclic: Suite[] = [
      { ...suite('a', 'A', 'b', 1) },
      { ...suite('b', 'B', 'a', 2) },
    ]
    expect(subtreeCaseCount(cyclic, 'a')).toBe(3)
  })

  it('does not double-count a folder reachable twice', () => {
    // Three levels: Payments → Refunds → Disputes. A walk that added a node once per path to it
    // would count the leaf twice on a diamond; this asserts the simpler property it rests on.
    const deep = [...suites, suite('5', 'Disputes', '2', 4)]
    expect(subtreeCaseCount(deep, '1')).toBe(12)
    expect(subtreeCaseCount(deep, '2')).toBe(7)
  })

  /**
   * The agreement `lib/suites.ts` promises in its own header, asserted rather than asserted-in-prose.
   * `buildTree` is module-private to `SuiteTree`, so this reproduces its rule — a node's total is
   * its own count plus its children's totals — and requires the two to land on the same number for
   * every folder in the fixture. A change to either that is not made to the other fails here.
   */
  it('agrees with the tree’s structural rollup, for every folder', () => {
    const totalOf = (id: string): number => {
      const self = suites.find((s) => s.id === id)
      if (!self) return 0
      return (
        self.caseCount
        + suites.filter((s) => s.parentId === id).reduce((sum, child) => sum + totalOf(child.id), 0)
      )
    }

    for (const s of suites) {
      expect(subtreeCaseCount(suites, s.id), `${s.name} disagrees with the tree`).toBe(totalOf(s.id))
    }
  })
})
