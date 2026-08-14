import { QueryClientProvider } from '@tanstack/react-query'
import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestPlan } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { blankCaseRow, draftFromPlan, type PlanDraft } from './planDraft'
import { usePlanSave } from './usePlanSave'

/**
 * The plan detail's one Save — **two endpoints, one order, and a report per step**.
 *
 * Driven through the hook rather than the screen because phase 4 ships the spine and phase 6 ships
 * the control that fills `newCases`: the ordering and the partial-failure arms are the whole of
 * what this phase decides, and a test that waited for a UI to exist would be a test written after
 * the code it checks.
 *
 * What is locked here:
 *
 *   1. **Cases first, plan second.** Reversed, a failure between the two leaves the plan naming
 *      rows the project does not have — the server refuses the whole `PATCH`, so the author loses
 *      both halves instead of one. Asserted as an *order*, not as two counts.
 *   2. **Every row carries its own idempotency key.** One key for the loop — which is what
 *      `useCreateCase` would have given — makes the server read rows 2…N as replays of row 1 and
 *      answer with the first case each time: five titles typed, one case created, and nothing on
 *      screen that looks wrong.
 *   3. **A failure at step 2 reports what step 1 created.** Those cases are loose in the project;
 *      an author told only "could not save" has no way to find them.
 *   4. **A failure inside step 1 stops there** and reports the rows that got through, rather than
 *      pressing on and leaving a partially-created batch nobody enumerated.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

function planBody(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
    keySeq: 4,
    name: 'Release 4.4',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 0,
    passRate: null,
    coverage: null,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    suiteIds: [],
    caseIds: [],
    tags: [],
    sections: [],
    scopeText: null,
    outOfScopeText: null,
    currentVersionNo: 1,
    lockVersion: 7,
    ...overrides,
  }
}

function draftWithRows(plan: TestPlan, ...titles: string[]): PlanDraft {
  return {
    ...draftFromPlan(plan),
    newCases: [...titles.map((title) => ({ ...blankCaseRow(), title })), blankCaseRow()],
  }
}

function wrapper({ children }: { children: ReactNode }) {
  const client = createTestQueryClient()
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

function saveHook() {
  return renderHook(() => usePlanSave(PROJECT, PLAN), { wrapper }).result
}

describe('usePlanSave', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('creates the cases before it patches the plan, and sends their ids in the patch', async () => {
    const calls: string[] = []
    const post = vi.spyOn(api, 'post').mockImplementation((async (_path: string, body: unknown) => {
      calls.push('post')
      const title = (body as { title: string }).title
      return { id: `case-${title}`, key: `TC-${title}` }
    }) as never)
    const patch = vi.spyOn(api, 'patch').mockImplementation((async () => {
      calls.push('patch')
      return planBody()
    }) as never)

    const plan = planBody()
    const result = saveHook()
    const outcome = await result.current.save(draftWithRows(plan, 'a', 'b'), plan)

    // The order, not the counts: two posts and a patch in any arrangement would satisfy
    // `toHaveBeenCalledTimes` and would still be the defect.
    expect(calls).toEqual(['post', 'post', 'patch'])
    expect(outcome.kind).toBe('saved')
    expect(post).toHaveBeenCalledTimes(2)

    const body = (patch.mock.calls[0] as unknown[])[1] as Record<string, unknown>
    expect(body.caseIds).toEqual(['case-a', 'case-b'])
  })

  it('gives every row its own idempotency key', async () => {
    const post = vi.spyOn(api, 'post').mockImplementation((async (_p: string, body: unknown) => ({
      id: 'c',
      key: `TC-${(body as { title: string }).title}`,
    })) as never)
    vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)

    const plan = planBody()
    await saveHook().current.save(draftWithRows(plan, 'a', 'b'), plan)

    const keyOf = (call: number) =>
      ((post.mock.calls[call] as unknown[])[2] as { idempotencyKey: string }).idempotencyKey
    expect(keyOf(0)).toBeTruthy()
    // The assertion the whole hook exists for. Equal keys here is the arrangement in which the
    // server answers the second POST with the FIRST case, and the plan comes back holding one case
    // where the author typed two.
    expect(keyOf(0)).not.toBe(keyOf(1))
  })

  it('sends the same key again on a retry, so a second Save creates nothing twice', async () => {
    const post = vi
      .spyOn(api, 'post')
      .mockImplementation((async () => ({ id: 'case-1', key: 'TC-1' })) as never)
    vi.spyOn(api, 'patch')
      .mockRejectedValueOnce(new Error('the plan step fell over'))
      .mockResolvedValue(planBody() as never)

    const plan = planBody()
    const draft = draftWithRows(plan, 'a')
    const result = saveHook()

    const first = await result.current.save(draft, plan)
    expect(first.kind).toBe('planFailed')
    // The draft is untouched on failure — the author's rows, and their keys, are still what they
    // were — so pressing Save again re-sends them.
    const second = await result.current.save(draft, plan)
    expect(second.kind).toBe('saved')

    const keyOf = (call: number) =>
      ((post.mock.calls[call] as unknown[])[2] as { idempotencyKey: string }).idempotencyKey
    expect(keyOf(1)).toBe(keyOf(0))
  })

  it('reports the cases it created when the plan step fails', async () => {
    vi.spyOn(api, 'post').mockImplementation((async (_p: string, body: unknown) => ({
      id: 'case-x',
      key: `TC-${(body as { title: string }).title}`,
    })) as never)
    vi.spyOn(api, 'patch').mockRejectedValue(new Error('nope'))

    const plan = planBody()
    const outcome = await saveHook().current.save(draftWithRows(plan, 'a', 'b'), plan)

    expect(outcome.kind).toBe('planFailed')
    // Named, because they are in the project and not in the plan, and the author cannot see them
    // from this screen at all.
    expect(outcome.created.map((row) => row.key)).toEqual(['TC-a', 'TC-b'])
  })

  it('stops at the row that failed and names it', async () => {
    vi.spyOn(api, 'post').mockImplementation((async (_p: string, body: unknown) => {
      const title = (body as { title: string }).title
      if (title === 'b') throw new Error('refused')
      return { id: `case-${title}`, key: `TC-${title}` }
    }) as never)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)

    const plan = planBody()
    const outcome = await saveHook().current.save(draftWithRows(plan, 'a', 'b', 'c'), plan)

    expect(outcome.kind).toBe('casesFailed')
    expect(outcome.created.map((row) => row.key)).toEqual(['TC-a'])
    if (outcome.kind === 'casesFailed') expect(outcome.failedTitle).toBe('b')
    // And the plan is NOT patched: half a batch of cases put into scope is a checklist shorter than
    // the one the author asked for, reported against a denominator nobody chose.
    expect(patch).not.toHaveBeenCalled()
  })

  it('never posts the trailing blank row', async () => {
    const post = vi.spyOn(api, 'post')
    vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)

    const plan = planBody()
    const draft: PlanDraft = { ...draftFromPlan(plan), newCases: [blankCaseRow()] }
    await saveHook().current.save(draft, plan)

    expect(post).not.toHaveBeenCalled()
  })

  it('sends the lockVersion the draft was seeded from as If-Match', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)

    const plan = planBody({ lockVersion: 7 } as Partial<TestPlan>)
    await saveHook().current.save({ ...draftFromPlan(plan), scopeText: 'Payments.' }, plan)

    const options = (patch.mock.calls[0] as unknown[])[2] as { headers: Record<string, string> }
    expect(options.headers['If-Match']).toBe('7')
  })
})
