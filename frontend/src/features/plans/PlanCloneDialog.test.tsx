import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { TestPlan } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { aPlan } from '../../test/fixtures'
import { PlanCloneDialog } from './PlanCloneDialog'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * Cloning a plan (§129 phase 3).
 *
 * **The assertion that matters is the path**, and it looks trivial until you know what the other
 * spelling costs: `POST /plans` with the copied fields is the *obvious* implementation, it
 * type-checks, it answers 201, and it silently drops the sections, the tag arm, both prose fields
 * and both criteria lists — because `TestPlanInput` carries six fields and a plan detail is twelve.
 * A test asserting only "a plan was created" passes against that version.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

/**
 * The source plan, spelled out and **not cast** — §79's rule, applied here by §196.
 *
 * Three fields is all this dialog reads, and the other nineteen were absent behind an `as TestPlan`
 * that told the compiler otherwise. The dialog is the *last* screen where that is harmless: the
 * moment it names something about the plan it is copying — as `CaseCloneDialog` began doing in
 * §195 — the fixture answers `undefined` where the server always sends a value.
 */
function sourcePlan(over: Partial<TestPlan> = {}): TestPlan {
  return {
    id: 'plan-1',
    key: 'TP-3',
    keySeq: 3,
    name: 'Regression 4.3',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 0,
    passRate: null,
    coverage: null,
    // Zero of zero — a plan with no exit criteria, which is the ordinary state (B1 / 005 S5).
    exitCriteriaMet: 0,
    exitCriteriaTotal: 0,
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
    lockVersion: 1,
    archived: false,
    ...over,
  }
}

function renderDialog(onCloned = vi.fn()) {
  render(
    <TestProviders client={createTestQueryClient()}>
      <PlanCloneDialog
        projectId={PROJECT}
        plan={sourcePlan()}
        open
        onClose={() => {}}
        onCloned={onCloned}
      />
    </TestProviders>,
  )
  return onCloned
}

describe('PlanCloneDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('suggests the name and posts to the clone endpoint, not to the create', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(aPlan({ id: 'plan-2', key: 'TP-4' }) as never)
    const onCloned = renderDialog()

    expect(await screen.findByTestId('PlanStats.Clone.Name')).toHaveValue('Copy of Regression 4.3')

    const name = screen.getByTestId('PlanStats.Clone.Name')
    await userEvent.clear(name)
    await userEvent.type(name, 'Regression 4.4')
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [path, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/projects/${PROJECT}/plans/plan-1/clone`)
    // The name and NOTHING else: everything a plan consists of is read from the source server-side,
    // and a body carrying a copied scope would be this client's resolution of it, at this instant.
    expect(body).toEqual({ name: 'Regression 4.4' })
    await waitFor(() => expect(onCloned).toHaveBeenCalled())
  })

  it('refuses an empty name without asking the server', async () => {
    const post = vi.spyOn(api, 'post')
    renderDialog()

    await userEvent.clear(await screen.findByTestId('PlanStats.Clone.Name'))
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))

    expect(await screen.findByText('Enter a name for the copy.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('lands a name error on the field and a 403 in the banner', async () => {
    // The two refused writes are the subject: this asserts where each one lands.
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValueOnce(
      new ApiError(400, 'Check the highlighted fields.', { name: ['That name is already used.'] }),
    )
    renderDialog()

    await screen.findByTestId('PlanStats.Clone.Name')
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))
    expect(await screen.findByText('That name is already used.')).toBeInTheDocument()
    expect(screen.queryByTestId('PlanStats.Clone.Error')).not.toBeInTheDocument()

    vi.spyOn(api, 'post').mockRejectedValueOnce(new ApiError(403, 'You cannot create plans here.'))
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))
    expect(await screen.findByTestId('PlanStats.Clone.Error')).toBeInTheDocument()
  })

  it('says the copy starts as a draft — the one fact that surprises a reader', async () => {
    renderDialog()
    expect(await screen.findByText(/starts as a draft/i)).toBeInTheDocument()
  })
})
