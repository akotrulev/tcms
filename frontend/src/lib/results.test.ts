import { describe, expect, it } from 'vitest'
import type { CaseStatus, ResultCounts, StepResult, TestExecutionSummary } from '../api/types'
import { criterionState, executedCount, passRate, runFailures, stepOutcome, sumCounts } from './results'

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

/**
 * Exit-criterion state — three values, and the reason the third one exists.
 *
 * The plan statistics page classified criteria with `!criterion.met`, which reads `null` as
 * unmet. `null` means the SERVER COULD NOT EVALUATE IT: `open_critical` needs the M4 issue
 * tracker, `cases_approved` the review workflow. So a plan that was perfectly shippable showed
 * red crosses and "3 unmet" against gates nobody had checked.
 *
 * It survived every gate and every browser pass for one reason: **the mock always supplied a
 * boolean**, so the null branch was unreachable until the live endpoint landed. That is the §45
 * shape — a defect that only becomes possible when a slice makes a state reachable — and it is
 * why this is pinned in a unit test rather than trusted to the next browser pass.
 */
describe('criterionState', () => {
  it('distinguishes met, unmet and never-evaluated', () => {
    expect(criterionState({ met: true })).toBe('MET')
    expect(criterionState({ met: false })).toBe('UNMET')
    expect(criterionState({ met: null })).toBe('NOT_EVALUATED')
  })

  it('treats an omitted met exactly as null — both say "we did not look"', () => {
    expect(criterionState({})).toBe('NOT_EVALUATED')
    expect(criterionState({ met: undefined })).toBe('NOT_EVALUATED')
  })

  /** The defect, stated as the predicate that caused it. */
  it('does NOT let an unevaluated criterion count towards the unmet total', () => {
    const criteria = [{ met: true }, { met: false }, { met: null }, {}]

    const unmet = criteria.filter((c) => criterionState(c) === 'UNMET')
    const naive = criteria.filter((c) => !c.met)

    expect(unmet).toHaveLength(1)
    expect(naive).toHaveLength(3)
  })
})

/**
 * ------------------------------------------------------------------ failures ---
 *
 * The two distinctions §105's summary rests on. Both are invisible on a happy fixture and both
 * have a sibling defect already recorded in this repo.
 */

/*
  Declared return types and no `as` anywhere — §79's rule, which paid for itself in §80: a
  required field added to one of these contract types must be ONE build error here, not eleven
  green types and eleven red runtime assertions.
*/
const status = (kind: CaseStatus['kind']): CaseStatus => ({
  id: `st-${kind}`,
  name: kind,
  kind,
  color: '#000000',
  countsAsPass: kind === 'PASSED',
  isFinal: true,
})

const execution = (
  caseKey: string,
  kind: 'PASSED' | 'FAILED' | 'BLOCKED' | 'SKIPPED' | null,
): TestExecutionSummary => ({
  id: `exec-${caseKey}`,
  caseId: `case-${caseKey}`,
  caseKey,
  caseTitle: `The ${caseKey} case`,
  caseVersionNo: 1,
  status: kind === null ? null : status(kind),
  assignee: null,
  executedBy: null,
  executedAt: null,
  durationMs: null,
  defects: [],
  quarantined: false,
})

const statuses: CaseStatus[] = [status('PASSED'), status('FAILED')]

const step = (position: number, statusId: string | null): StepResult => ({
  stepId: `step-${position}`,
  position,
  keyword: null,
  action: `Step ${position}`,
  testData: null,
  expectedResult: null,
  statusId,
  comment: null,
  evidence: [],
})

describe('runFailures', () => {
  it('does NOT count a blocked case as a failed one', () => {
    // The defect this exists to stop: "needs attention" is a workload question and "what failed"
    // is a product question, and merging them reports breakage where nobody tested.
    const { failed, blocked } = runFailures([
      execution('TC-1', 'FAILED'),
      execution('TC-2', 'BLOCKED'),
      execution('TC-3', 'BLOCKED'),
    ])

    expect(failed.map((e) => e.caseKey)).toEqual(['TC-1'])
    expect(blocked.map((e) => e.caseKey)).toEqual(['TC-2', 'TC-3'])
  })

  it('drops passed, skipped and unexecuted cases from both lists', () => {
    // A skipped case is a decision, not a finding; an unexecuted one is not an answer at all.
    const { failed, blocked } = runFailures([
      execution('TC-1', 'PASSED'),
      execution('TC-2', 'SKIPPED'),
      execution('TC-3', null),
    ])

    expect(failed).toHaveLength(0)
    expect(blocked).toHaveLength(0)
  })

  it('orders by case key numerically, so the report reads the same twice', () => {
    // `localeCompare` puts TC-10 ahead of TC-9 — §100's finding, invisible under ten cases.
    const { failed } = runFailures([
      execution('TC-10', 'FAILED'),
      execution('TC-9', 'FAILED'),
      execution('TC-2', 'FAILED'),
    ])

    expect(failed.map((e) => e.caseKey)).toEqual(['TC-2', 'TC-9', 'TC-10'])
  })
})

describe('stepOutcome', () => {
  it('reports failed, unmarked and total as three separate numbers', () => {
    const outcome = stepOutcome(
      [step(0, 'st-PASSED'), step(1, 'st-FAILED'), step(2, null), step(3, null)],
      statuses,
    )

    expect(outcome.failed.map((s) => s.position)).toEqual([1])
    expect(outcome.unmarkedCount).toBe(2)
    expect(outcome.totalCount).toBe(4)
  })

  /** The defect, stated as the inference a two-number summary invites. */
  it('never lets an unmarked step be read as a passed one', () => {
    const steps = [step(0, 'st-FAILED'), step(1, null), step(2, null)]
    const outcome = stepOutcome(steps, statuses)

    // The naive reading: "1 of 3 failed" implies two passed. It is 1 failed, 0 passed, 2 unknown.
    const impliedPasses = outcome.totalCount - outcome.failed.length
    expect(impliedPasses).toBe(2)
    expect(outcome.unmarkedCount).toBe(2)
    // So the panel must state the unmarked count; this asserts it is available to state.
    expect(outcome.unmarkedCount).toBeGreaterThan(0)
  })

  it('counts a step whose status the vocabulary does not know as neither failed nor unmarked', () => {
    // Somebody recorded something this client cannot interpret. Guessing either way is worse than
    // leaving it out of both — and it is NOT the tester's unfinished work, which is what
    // `unmarkedCount` means.
    const outcome = stepOutcome([step(0, 'st-FROM-ANOTHER-PROJECT')], statuses)

    expect(outcome.failed).toHaveLength(0)
    expect(outcome.unmarkedCount).toBe(0)
    expect(outcome.totalCount).toBe(1)
  })

  it('is empty and non-vacuous on a case with no structured steps', () => {
    // `[].every()` proposing a verdict from no evidence is `suggestedVerdict`'s recorded defect;
    // here the guard is that totalCount is 0, so the panel can say "no structured steps" rather
    // than "0 of 0 failed".
    const outcome = stepOutcome([], statuses)

    expect(outcome.totalCount).toBe(0)
    expect(outcome.failed).toHaveLength(0)
    expect(outcome.unmarkedCount).toBe(0)
  })

  it('returns failed steps in position order regardless of the order they arrive in', () => {
    const outcome = stepOutcome([step(5, 'st-FAILED'), step(1, 'st-FAILED')], statuses)

    expect(outcome.failed.map((s) => s.position)).toEqual([1, 5])
  })
})
