import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { PlanStatus, TestPlan } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { PlanStatisticsPage } from './PlanStatisticsPage'

/**
 * The plan editor — **the affordance behind two columns that had no writer at all until §74**.
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **The payload omits `caseIds` and sends `suiteIds`.** The server reads an absent list as
 *      *leave it alone* and `[]` as *clear it*, so a dialog that spread an empty array for the half
 *      of the scope it cannot show would silently delete it on the first rename. There is no other
 *      check for this: the request succeeds either way and the loss is only visible in the
 *      database.
 *   2. **`met` and `actual` never travel back.** They are the server's own computed verdict; a form
 *      that round-tripped them would be asserting that a release gate had been met.
 *   3. **The threshold is sent as a number**, from a text input that has to hold "" while it is
 *      being typed — the coercion `pass_rate >= 0` is the gate that is always met and reads on
 *      screen like one somebody meant.
 *   4. **A derived label is not resubmitted as an authored one.** The server writes
 *      "pass_rate >= 95" when no label was given, so echoing it back would freeze that text over a
 *      threshold the next edit changes.
 *   5. **Edit is disabled on a CLOSED plan**, with the reason on the control rather than as a 409
 *      after the user has retyped the form.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

function planBody(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
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
  } as unknown as TestPlan
}

function mockReads(plan: TestPlan, permissions: string[] = ['project:read', 'plan:edit']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/statistics')) {
      return {
        planId: PLAN,
        planKey: 'TP-4',
        planName: plan.name,
        aggregationPolicy: 'LATEST',
        counts: { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 0 },
        coveragePercent: 0,
        byConfiguration: [],
        bySuite: [],
        blockers: [],
        notRun: [],
        trend: [],
        exitCriteria: [],
        latestExecutionAt: null,
      }
    }
    if (path.includes(`/plans/${PLAN}`)) return plan
    if (path.includes('/suites')) {
      return [
        { id: 'suite-1', key: 'TS-1', name: 'Payments', caseCount: 12, parentId: null },
        { id: 'suite-2', key: 'TS-2', name: 'Login', caseCount: 3, parentId: null },
      ]
    }
    if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }
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
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/plans/${PLAN}`}>
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <Routes>
          <Route path="/projects/:projectId/plans/:planId" element={<PlanStatisticsPage />} />
        </Routes>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

/**
 * Wait until the permission answer has landed — **without this every "disabled" assertion below
 * passes for the wrong reason**, because `useCan` is fail-closed while pending and a control that
 * has not been told anything yet is indistinguishable from one that was refused (§66).
 */
async function openEditor() {
  await waitFor(() => expect(screen.getByTestId('PlanStats.Edit')).toBeEnabled())
  await userEvent.click(screen.getByTestId('PlanStats.Edit'))
  await screen.findByTestId('Plans.Edit')
}

/** The body of the single PATCH the test drove. */
function patchedBody(patch: ReturnType<typeof vi.spyOn>) {
  expect(patch).toHaveBeenCalledTimes(1)
  return (patch.mock.calls[0] as unknown[])[1] as Record<string, unknown>
}

describe('PlanEditDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('seeds the form from the plan it was served, including the scope checkboxes', async () => {
    mockReads(planBody())
    renderPage()
    await openEditor()

    expect(screen.getByTestId('Plans.Edit.Name')).toHaveValue('Release 4.4 regression')
    // The field the detail endpoint did not serve before this slice — `caseCount` is an expansion
    // and could never have told the picker which folder was chosen.
    expect(screen.getByTestId('Plans.Edit.Scope.TS-1')).toBeChecked()
    expect(screen.getByTestId('Plans.Edit.Scope.TS-2')).not.toBeChecked()
  })

  it('sends suiteIds and OMITS caseIds, so a rename cannot empty half the scope', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()
    await openEditor()

    await userEvent.click(screen.getByTestId('Plans.Edit.Scope.TS-2'))
    await userEvent.click(screen.getByTestId('Plans.Edit.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patchedBody(patch)
    expect(body.suiteIds).toEqual(['suite-1', 'suite-2'])
    // Absent, NOT []. The plan carries `caseIds: ['case-9']` and this dialog cannot show it, so
    // sending an empty array would delete it — the difference between "leave them alone" and
    // "remove them" is the whole of the server's three-state rule.
    expect('caseIds' in body).toBe(false)
  })

  it('sends the lockVersion it read as If-Match, never in the body', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()
    await openEditor()
    await userEvent.click(screen.getByTestId('Plans.Edit.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const options = (patch.mock.calls[0] as unknown[])[2] as { headers: Record<string, string> }
    expect(options.headers['If-Match']).toBe('7')
    expect('lockVersion' in patchedBody(patch)).toBe(false)
  })

  it('sends a criterion as metric/op/number, and never met or actual', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()
    await openEditor()

    await userEvent.click(screen.getByTestId('Plans.Edit.Exit.Add'))
    await userEvent.type(screen.getByTestId('Plans.Edit.Exit.Value.0'), '95')
    await userEvent.click(screen.getByTestId('Plans.Edit.Submit'))

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
    await openEditor()

    await userEvent.click(screen.getByTestId('Plans.Edit.Exit.Add'))
    await userEvent.click(screen.getByTestId('Plans.Edit.Submit'))

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
    await openEditor()

    expect(screen.getByTestId('Plans.Edit.Exit.Label.0')).toHaveValue('')
    await userEvent.click(screen.getByTestId('Plans.Edit.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const criteria = patchedBody(patch).exitCriteria as Record<string, unknown>[]
    // Null, so the server derives it again from whatever the threshold now is. Stored, the old
    // text would survive the next change of `value` and read as a contradiction.
    expect(criteria[0].label).toBeNull()
  })

  it('renders a field error on the control it names', async () => {
    mockReads(planBody())
    vi.spyOn(api, 'patch').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', { name: ['Enter a plan name.'] }, null, null, 'Validation failed'),
    )
    renderPage()
    await openEditor()
    await userEvent.click(screen.getByTestId('Plans.Edit.Submit'))

    expect(await screen.findByText('Enter a plan name.')).toBeInTheDocument()
  })

  it('puts a refusal with no field of its own in the form-level alert', async () => {
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
    await openEditor()
    await userEvent.click(screen.getByTestId('Plans.Edit.Submit'))

    // `exitCriteria` has no TextField to land on, and a refusal the user cannot see is the failure
    // this whole error path exists to prevent.
    const alert = await screen.findByTestId('Plans.Edit.Error')
    expect(within(alert).getByText(/is not a metric/)).toBeInTheDocument()
  })

  /*
    'disables Edit on a CLOSED plan, and explains why on the control' lived here. §97 (M7) removed
    CLOSED, so no plan is frozen against editing and there is no disabled-with-reason to assert.
    What still protects a sign-off is the server's edit-after-approval rule, which demotes rather
    than refuses — a different behaviour, tested on the backend where it is decided.
  */

  it('disables Edit for a caller without plan:edit', async () => {
    mockReads(planBody(), ['project:read'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('PlanStats.Edit')).toBeDisabled())
    // The same barrier as above, from the other side: a control the caller DOES hold must become
    // enabled, or "disabled" above is just the pending state.
    mockReads(planBody(), ['project:read', 'plan:edit'])
  })
})
