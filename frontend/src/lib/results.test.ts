import { describe, expect, it } from 'vitest'
import type { ResultCounts } from '../api/types'
import { executedCount, passRate, sumCounts } from './results'

/**
 * Count arithmetic. Every number on the dashboard, the run detail, the plan statistics screen
 * and the case grid's facet chips is derived here, and §32 is the reason this file has its own
 * test rather than being treated as too simple to break: a suite tree rendered every parent's
 * descendants **twice**, so a project with 27 cases showed a folder holding 40. It typechecked,
 * it linted, and it survived every browser pass until a single delete moved a count by two.
 *
 * A wrong count in a test management system is not cosmetic. It is the number a release
 * decision is made on.
 */

const counts = (partial: Partial<ResultCounts>): ResultCounts => ({
  passed: 0,
  failed: 0,
  blocked: 0,
  skipped: 0,
  notRun: 0,
  total: 0,
  ...partial,
})

describe('executedCount', () => {
  it('counts every result state and excludes notRun', () => {
    expect(
      executedCount(counts({ passed: 10, failed: 3, blocked: 2, skipped: 1, notRun: 84, total: 100 })),
    ).toBe(16)
  })

  it('is zero on an untouched run, not equal to its total', () => {
    // The state that makes the two denominators differ most, and the one a naive
    // `passed / total` renders as a catastrophic 0%.
    expect(executedCount(counts({ notRun: 100, total: 100 }))).toBe(0)
  })

  /*
    `notRun` is excluded on purpose (the module says so). This asserts the *reason*: moving a
    case from notRun into any executed bucket must raise `executedCount` by exactly one, and
    counting notRun would make it constant.
  */
  it.each(['passed', 'failed', 'blocked', 'skipped'] as const)(
    'rises by one when a case moves from notRun to %s',
    (bucket) => {
      const before = counts({ notRun: 10, total: 10 })
      const after = counts({ notRun: 9, [bucket]: 1, total: 10 })
      expect(executedCount(after) - executedCount(before)).toBe(1)
    },
  )
})

describe('passRate — the two legitimate denominators', () => {
  const run = counts({ passed: 41, failed: 4, blocked: 1, skipped: 0, notRun: 51, total: 97 })

  it('gives a different, correct answer per denominator', () => {
    // 41/46 = 89.13…% executed; 41/97 = 42.27…% of everything in scope.
    expect(passRate(run, 'EXECUTED')).toBeCloseTo(89.13, 2)
    expect(passRate(run, 'TOTAL')).toBeCloseTo(42.27, 2)
  })

  it('returns null rather than 0% when nothing has been executed', () => {
    // §5's Phase 2 defect in miniature: a screen showing 0% on an unstarted run reads as total
    // failure. Unknown must stay unknown all the way to the formatter.
    expect(passRate(counts({ notRun: 20, total: 20 }), 'EXECUTED')).toBeNull()
  })

  it('returns null for a TOTAL rate over an empty scope', () => {
    expect(passRate(counts({}), 'TOTAL')).toBeNull()
  })

  it('reports 100% only when every executed case passed', () => {
    expect(passRate(counts({ passed: 5, notRun: 5, total: 10 }), 'EXECUTED')).toBe(100)
    expect(passRate(counts({ passed: 5, notRun: 5, total: 10 }), 'TOTAL')).toBe(50)
  })

  it('counts blocked and skipped against the rate, never as passes', () => {
    const withBlocked = counts({ passed: 9, blocked: 1, total: 10 })
    expect(passRate(withBlocked, 'EXECUTED')).toBe(90)
    const withSkipped = counts({ passed: 9, skipped: 1, total: 10 })
    expect(passRate(withSkipped, 'EXECUTED')).toBe(90)
  })
})

describe('sumCounts — the rollup', () => {
  it('sums every field independently', () => {
    const total = sumCounts([
      counts({ passed: 1, failed: 2, blocked: 3, skipped: 4, notRun: 5, total: 15 }),
      counts({ passed: 10, failed: 20, blocked: 30, skipped: 40, notRun: 50, total: 150 }),
    ])
    expect(total).toEqual({
      passed: 11,
      failed: 22,
      blocked: 33,
      skipped: 44,
      notRun: 55,
      total: 165,
    })
  })

  it('returns an all-zero shape for an empty rollup rather than undefined', () => {
    // A parent suite with no children still needs a renderable ResultCounts.
    expect(sumCounts([])).toEqual({ passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 0 })
  })

  it('does not mutate its inputs', () => {
    const child = counts({ passed: 3, total: 3 })
    sumCounts([child, child])
    expect(child).toEqual(counts({ passed: 3, total: 3 }))
  })

  /**
   * **The §32 defect class, as a property.** A rollup is only correct if every leaf is counted
   * exactly once. The suite tree's bug was double-counting: the parent's own count already
   * contained its descendants, and the tree added the descendants again.
   *
   * Stated as arithmetic: summing a set of leaves must equal the sum of any partition of that
   * set into groups. If a member is counted twice — in a group *and* at the top — the two sides
   * diverge, and this assertion is what notices.
   */
  it('is associative, so a two-level rollup equals a flat one over the same leaves', () => {
    const leaves = [
      counts({ passed: 12, failed: 1, notRun: 6, total: 19 }),
      counts({ passed: 2, blocked: 1, total: 3 }),
      counts({ passed: 3, total: 3 }),
      counts({ skipped: 2, total: 2 }),
    ]
    const flat = sumCounts(leaves)
    const grouped = sumCounts([sumCounts(leaves.slice(0, 2)), sumCounts(leaves.slice(2))])

    expect(grouped).toEqual(flat)
    // And the totals reconcile to the project's own figure: 19 + 3 + 3 + 2 = 27, the exact
    // reconciliation §32 used to prove the double-count was gone.
    expect(flat.total).toBe(27)
  })

  it('sums a leaf added at the parent level exactly once', () => {
    // The shape of the defect: `parentOwn` are the parent's OWN cases (the wire meaning of
    // `Suite.caseCount`), and the children are separate. Rolling up must produce own + children,
    // never own-already-including-children + children.
    const parentOwn = counts({ passed: 4, total: 4 })
    const children = [counts({ passed: 6, total: 6 }), counts({ passed: 8, total: 8 })]
    expect(sumCounts([parentOwn, ...children]).total).toBe(18)
  })

  /**
   * The averaging trap, stated as the module doc states it: a rollup percentage must be
   * computed from summed counts, never by averaging children's percentages.
   */
  it('produces a weighted rate, not the average of the children rates', () => {
    const tiny = counts({ passed: 2, total: 2 }) // 100%
    const large = counts({ passed: 100, failed: 100, total: 200 }) // 50%

    const rolled = passRate(sumCounts([tiny, large]), 'EXECUTED')
    expect(rolled).toBeCloseTo(50.4950, 3) // 102 / 202

    const averagedPercentages = (100 + 50) / 2
    expect(averagedPercentages).toBe(75)
    expect(rolled).not.toBeCloseTo(averagedPercentages, 1)
  })
})
