import { describe, expect, it } from 'vitest'
import type { TestExecutionSummary } from '../../api/types'
import { orderedQueue } from './queueOrder'

/**
 * Two properties, and the first one is a bug this project had already named without checking
 * whether it had it: `compareReleaseVersions` in `lib/format.ts` says a lexicographic sort is
 * "the same class of bug as ordering by case key", and `RunDetailPage`'s tiebreaker was exactly
 * that. It does not show on any fixture set whose keys stay under ten.
 */

const execution = (caseKey: string, id = caseKey): TestExecutionSummary =>
  ({
    id,
    caseId: `case-${id}`,
    caseKey,
    caseTitle: caseKey,
    caseVersionNo: 1,
    status: null,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
  })

const keys = (rows: TestExecutionSummary[]) => orderedQueue(rows).map((r) => r.caseKey)

describe('orderedQueue', () => {
  it('orders case keys numerically, not lexicographically', () => {
    const rows = [execution('TC-10'), execution('TC-9'), execution('TC-100'), execution('TC-1')]

    expect(keys(rows)).toEqual(['TC-1', 'TC-9', 'TC-10', 'TC-100'])
  })

  /**
   * The reason the rail does NOT sort by what still needs doing: under a needs-doing rank the row
   * the tester is standing on jumps away the instant they record a result. Order here depends on
   * the key alone, so marking a case cannot move it.
   */
  it('does not depend on what has been executed', () => {
    const untouched = [execution('TC-1'), execution('TC-2'), execution('TC-3')]
    const marked = [
      { ...execution('TC-1'), status: { id: 's', name: 'Passed', kind: 'PASSED' } },
      execution('TC-2'),
      { ...execution('TC-3'), status: { id: 'f', name: 'Failed', kind: 'FAILED' } },
    ] as TestExecutionSummary[]

    expect(keys(marked)).toEqual(keys(untouched))
  })

  it('does not mutate the array it is given — it belongs to the query cache', () => {
    const rows = [execution('TC-2'), execution('TC-1')]

    orderedQueue(rows)

    expect(rows.map((r) => r.caseKey)).toEqual(['TC-2', 'TC-1'])
  })

  it('is a total order even if two rows somehow share a case key', () => {
    const rows = [execution('TC-1', 'b'), execution('TC-1', 'a')]

    expect(orderedQueue(rows).map((r) => r.id)).toEqual(['a', 'b'])
  })
})
