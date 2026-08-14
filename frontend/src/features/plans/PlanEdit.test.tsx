import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { PermissionKey, PlanStatus, TestPlan } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, planStatistics, projectSettings } from '../../test/fixtures'
import { PlanStatisticsPage } from './PlanStatisticsPage'

/**
 * The plan editor — **the affordance behind two columns that had no writer at all until §74**.
 *
 * **§97 phase 5 moved it out of a dialog and onto the Plan tab, and this file moved with it rather
 * than being deleted.** Every assertion below is about the *payload*, and the payload is decided by
 * `planDraft` now instead of by a dialog's submit handler — so deleting the file with
 * `PlanEditDialog` would have retired six checks that still describe live behaviour, on the excuse
 * that the control they drive has a different name.
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **A plan-field edit sends NEITHER scope list.** The server reads an absent list as *leave it
 *      alone* and `[]` as *clear it*, so a form that spread its whole draft on every save would
 *      delete the half of the scope it cannot show. The dialog omitted `caseIds` by hand; the form
 *      omits both, by computation, because the Plan tab is not the tab that edits scope.
 *   2. **`met` and `actual` never travel back.** They are the server's own computed verdict; a form
 *      that round-tripped them would be asserting that a release gate had been met.
 *   3. **The threshold is sent as a number**, from a text input that has to hold "" while it is
 *      being typed — the coercion `pass_rate >= 0` is the gate that is always met and reads on
 *      screen like one somebody meant.
 *   4. **A derived label is not resubmitted as an authored one.** The server writes
 *      "pass_rate >= 95" when no label was given, so echoing it back would freeze that text over a
 *      threshold the next edit changes.
 *   5. **A blank name disables Save with its reason**, rather than buying a 400 for a field the
 *      client already knew was empty.
 *   6. **A field error lands on the control it names** — through the form's `fieldErrors`, which is
 *      what replaces the dialog's `applyServerError`.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

function planBody(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
    keySeq: 4,
    name: 'Release 4.4 regression',
    status: 'DRAFT' as PlanStatus,
    milestoneName: null,
    milestoneDate: null,
    caseCount: 12,
    passRate: null,
    coverage: null,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    suiteIds: ['suite-1'],
    caseIds: ['case-9'],
    // Required on `TestPlan` since §97 phase 2 — see PlanLifecycle.test.tsx's note on the cast.
    tags: [],
    sections: [],
    scopeText: null,
    outOfScopeText: null,
    currentVersionNo: 1,
    lockVersion: 7,
    ...overrides,
  }
}

function mockReads(plan: TestPlan, permissions: PermissionKey[] = ['project:read', 'plan:edit']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/statistics')) {
      return planStatistics({ planId: PLAN, planKey: 'TP-4', planName: plan.name })
    }
    if (path.includes(`/plans/${PLAN}`)) return plan
    if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
    if (path.startsWith('/projects/')) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

/*
  The plan detail registers unsaved work since §97 phase 4 (its draft lives above the tabs), and
  `useUnsavedWork` throws without the session-recovery context by design — stubbed here the way
  `CaseEditorPage.test.tsx` and `DocPageEditorPage.test.tsx` stub it.
*/
const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/plans/${PLAN}?tab=plan`}
    >
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <Routes>
          <Route path="/projects/:projectId/plans/:planId" element={<PlanStatisticsPage />} />
        </Routes>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

/** The name box, once the plan has loaded — the tab's first control. */
const nameBox = () => screen.findByTestId('PlanStats.Plan.Name')

/**
 * Presses Save, **after waiting for the permission answer to land**. Without the wait every press
 * here would hit a button that is disabled for the pending reason rather than an enabled one (§66).
 */
async function save() {
  await waitFor(() => expect(screen.getByTestId('PlanStats.Save')).toBeEnabled())
  await userEvent.click(screen.getByTestId('PlanStats.Save'))
}

/** The body of the single PATCH the test drove. */
function patchedBody(patch: ReturnType<typeof vi.spyOn>) {
  expect(patch).toHaveBeenCalledTimes(1)
  return (patch.mock.calls[0] as unknown[])[1] as Record<string, unknown>
}

describe('the Plan tab', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('seeds its fields from the plan it was served', async () => {
    mockReads(planBody({ milestoneName: 'GA', description: 'The quarterly pass.' } as Partial<TestPlan>))
    renderPage()

    expect(await nameBox()).toHaveValue('Release 4.4 regression')
    expect(screen.getByTestId('PlanStats.Plan.Milestone')).toHaveValue('GA')
    expect(screen.getByTestId('PlanStats.Plan.Description')).toHaveValue('The quarterly pass.')
  })

  it('sends NEITHER suiteIds nor caseIds when only the plan fields changed', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    await userEvent.type(await nameBox(), ' (final)')
    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patchedBody(patch)
    expect(body.name).toBe('Release 4.4 regression (final)')
    // Absent, NOT []. The plan carries a suite and a case this tab cannot show, and the difference
    // between "leave them alone" and "remove them" is the whole of the server's three-state rule —
    // the defect a whole-draft payload would ship silently.
    expect('caseIds' in body).toBe(false)
    expect('suiteIds' in body).toBe(false)
  })

  it('sends the lockVersion it read as If-Match, never in the body', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    await userEvent.type(await nameBox(), '!')
    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const options = (patch.mock.calls[0] as unknown[])[2] as { headers: Record<string, string> }
    expect(options.headers['If-Match']).toBe('7')
    expect('lockVersion' in patchedBody(patch)).toBe(false)
  })

  it('sends a criterion as metric/op/number, and never met or actual', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    await userEvent.click(await screen.findByTestId('PlanStats.Plan.Exit.Add'))
    await userEvent.type(screen.getByTestId('PlanStats.Plan.Exit.Value.0'), '95')
    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const criteria = patchedBody(patch).exitCriteria as Record<string, unknown>[]
    expect(criteria).toHaveLength(1)
    expect(criteria[0].metric).toBe('pass_rate')
    expect(criteria[0].op).toBe('>=')
    // A NUMBER, not the "95" the text input holds: the server refuses a non-finite threshold, and
    // coercing an empty box to 0 would author "pass_rate >= 0" — met by every release ever run.
    expect(criteria[0].value).toBe(95)
    expect('met' in criteria[0]).toBe(false)
    expect('actual' in criteria[0]).toBe(false)
  })

  it('drops a criterion row the user added and left blank', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    // The added row is what makes the tab dirty, so Save is reachable with nothing typed in it.
    await userEvent.click(await screen.findByTestId('PlanStats.Plan.Exit.Add'))
    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    expect(patchedBody(patch).exitCriteria).toEqual([])
  })

  it('does not resubmit a SERVER-DERIVED label as an authored one', async () => {
    mockReads(
      planBody({
        exitCriteria: [
          // Exactly what the server derives when no label was authored — see PlanCriteria.parse.
          { metric: 'pass_rate', op: '>=', value: 95, label: 'pass_rate >= 95', met: null, actual: null },
        ],
      } as Partial<TestPlan>),
    )
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    expect(await screen.findByTestId('PlanStats.Plan.Exit.Label.0')).toHaveValue('')
    await userEvent.type(screen.getByTestId('PlanStats.Plan.Exit.Value.0'), '6')
    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const criteria = patchedBody(patch).exitCriteria as Record<string, unknown>[]
    // Null, so the server derives it again from whatever the threshold now is. Stored, the old
    // text would survive the next change of `value` and read as a contradiction.
    expect(criteria[0].label).toBeNull()
  })

  it('refuses to save a plan whose name has been cleared, and says which tab', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    await userEvent.clear(await nameBox())

    // Disabled with the reason on the banner (§31), rather than a 400 for something the client
    // already knew. The pair below is what keeps this non-vacuous: typing a name re-enables it.
    await waitFor(() => expect(screen.getByTestId('PlanStats.Blocked')).toBeInTheDocument())
    expect(screen.getByTestId('PlanStats.Save')).toBeDisabled()

    await userEvent.type(screen.getByTestId('PlanStats.Plan.Name'), 'Release 4.5')
    await waitFor(() => expect(screen.getByTestId('PlanStats.Save')).toBeEnabled())
    expect(patch).not.toHaveBeenCalled()
  })

  it('does not count a whitespace-only name as a name', async () => {
    mockReads(planBody())
    renderPage()

    await userEvent.clear(await nameBox())
    await userEvent.type(screen.getByTestId('PlanStats.Plan.Name'), '   ')

    // §94's trim defect, third home in this slice: a name of three spaces is not a name, and the
    // server would answer 400 for a refusal the client is certain about.
    await waitFor(() => expect(screen.getByTestId('PlanStats.Save')).toBeDisabled())
  })

  it('renders a field error on the control it names', async () => {
    mockReads(planBody())
    vi.spyOn(api, 'patch').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', { name: ['That name is already taken.'] }, null, null, 'Validation failed'),
    )
    renderPage()

    await userEvent.type(await nameBox(), '!')
    await save()

    expect(await screen.findByText('That name is already taken.')).toBeInTheDocument()
  })

  it('puts a refusal with no text field of its own beside the list it is about', async () => {
    mockReads(planBody())
    vi.spyOn(api, 'patch').mockRejectedValue(
      new ApiError(
        400,
        'Check the highlighted fields.',
        { exitCriteria: ["'pass_rte' is not a metric a plan can be gated on."] },
        null,
        null,
        'Validation failed',
      ),
    )
    renderPage()

    await userEvent.type(await nameBox(), '!')
    await save()

    // `exitCriteria` has no TextField to land on, and a refusal the user cannot see is the failure
    // this whole error path exists to prevent.
    const alert = await screen.findByTestId('PlanStats.Plan.Exit.Error')
    expect(within(alert).getByText(/is not a metric/)).toBeInTheDocument()
  })

  /*
    'disables Edit on a CLOSED plan' lived here. §97 (M7) removed CLOSED, so no plan is frozen
    against editing and there is no disabled-with-reason to assert. What still protects a sign-off
    is the server's edit-after-approval rule, which demotes rather than refuses — a different
    behaviour, tested on the backend where it is decided.

    'disables Edit for a caller without plan:edit' went with the Edit button in phase 5. The gate it
    asserted is now the Save button's, and `PlanForm.test.tsx` drives it in both directions.
  */
})
