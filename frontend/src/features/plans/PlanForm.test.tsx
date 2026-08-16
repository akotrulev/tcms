import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, TestPlan } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aPlan, aProject, planStatistics, projectSettings } from '../../test/fixtures'
import { PlanStatisticsPage } from './PlanStatisticsPage'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * The plan detail's **form spine** (§97 phase 4) — the draft above the tabs, the banner that names
 * which of them is dirty, and the one Save.
 *
 * What is locked here:
 *
 *   1. **The banner names the tab.** The whole reason state lives above the tabs is that an edit
 *      made on a tab the reader has since left is otherwise invisible; a banner that said only
 *      "unsaved changes" would leave them hunting for it.
 *   2. **An edit survives a tab switch.** This is §93's unmounting decision reversed, and it is the
 *      one behaviour that cannot be checked by reading the code — the old screen unmounted the
 *      inactive tab and would pass every other assertion here.
 *   3. **Save is gated in BOTH directions.** `useCan` is fail-closed while pending, so
 *      `toBeDisabled()` passes against a component with no gate at all (§66, §80); the holder's
 *      `toBeEnabled()` is the barrier.
 *   4. **A successful save reseeds from the response**, so the banner goes away and the next edit
 *      is measured against what the server now holds — not against the plan as it was loaded.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

function planBody(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
    keySeq: 4,
    name: 'Release 4.4 regression',
    status: 'DRAFT',
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
    tags: [],
    sections: [],
    scopeText: null,
    outOfScopeText: null,
    currentVersionNo: 1,
    lockVersion: 7,
    archived: false,
    ...overrides,
  }
}

function mockReads(plan: TestPlan, permissions: PermissionKey[] = ['project:read', 'plan:edit']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes(`/plans/${PLAN}/statistics`)) return planStatistics({ planId: PLAN })
    if (path.includes(`/plans/${PLAN}`)) return plan
    // Before the project fallthrough, or `/projects/p1/artifacts` resolves to the PROJECT and the
    // table is handed an object where it expects rows — which surfaces as a region boundary and a
    // disabled-looking control three assertions later, not as a fixture error.
    // The plan LIST, for the register dialog's plan picker. A bare [] leaves the pre-selected
    // plan out of range of its own select — MUI says so on stderr and the control renders empty.
    if (path.endsWith('/plans')) return [aPlan({ id: PLAN, key: 'TP-4', name: plan.name })]
    if (path.includes('/artifacts') || path.includes('/doc-pages')) return []
    if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderPage(tab = 'scope') {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/plans/${PLAN}?tab=${tab}`}
    >
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <Routes>
          <Route path="/projects/:projectId/plans/:planId" element={<PlanStatisticsPage />} />
        </Routes>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

/** The prose box on the Scope tab, once the plan has loaded. */
const scopeBox = () => screen.findByTestId('PlanStats.ScopeText')

describe('the plan detail form spine', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows no banner until something is edited', async () => {
    mockReads(planBody())
    renderPage()
    await scopeBox()

    expect(screen.queryByTestId('PlanStats.Dirty')).not.toBeInTheDocument()
  })

  it('names the dirty tab in the banner', async () => {
    mockReads(planBody())
    renderPage()

    await userEvent.type(await scopeBox(), 'Payments')

    const banner = await screen.findByTestId('PlanStats.Dirty')
    expect(banner).toHaveTextContent('Unsaved changes in Scope')
    expect(banner).not.toHaveTextContent('Out of scope')
  })

  it('keeps the edit when the reader switches tabs', async () => {
    mockReads(planBody())
    renderPage()
    await userEvent.type(await scopeBox(), 'Payments')

    // Progress is the tab that used to unmount everything else, and is the one a reader is most
    // likely to click mid-edit — this page is the release report.
    await userEvent.click(screen.getByTestId('PlanStats.Tab.progress'))
    expect(await screen.findByTestId('PlanStats.Dirty')).toHaveTextContent('Scope')

    await userEvent.click(screen.getByTestId('PlanStats.Tab.scope'))
    expect(await scopeBox()).toHaveValue('Payments')
  })

  it('discards back to the plan as served', async () => {
    mockReads(planBody({ scopeText: 'Payments.' } as Partial<TestPlan>))
    renderPage()
    await userEvent.type(await scopeBox(), ' And refunds.')

    await userEvent.click(await screen.findByTestId('PlanStats.Discard'))

    expect(await scopeBox()).toHaveValue('Payments.')
    expect(screen.queryByTestId('PlanStats.Dirty')).not.toBeInTheDocument()
  })

  it('sends only the prose that changed, and reseeds from the response', async () => {
    mockReads(planBody())
    const patch = vi
      .spyOn(api, 'patch')
      .mockResolvedValue(planBody({ scopeText: 'Payments', lockVersion: 8 } as Partial<TestPlan>) as never)
    renderPage()

    await userEvent.type(await scopeBox(), 'Payments')
    await userEvent.click(await screen.findByTestId('PlanStats.Save'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = (patch.mock.calls[0] as unknown[])[1] as Record<string, unknown>
    expect(body.scopeText).toBe('Payments')
    // Absent, not []. The form did not touch the scope lists and the server reads an empty array as
    // "clear it" — a rename that emptied the plan's scope is the defect this asserts against.
    expect('suiteIds' in body).toBe(false)

    // Reseeded: the banner goes with the save, rather than persisting against a stale seed.
    await waitFor(() => expect(screen.queryByTestId('PlanStats.Dirty')).not.toBeInTheDocument())
    expect(await screen.findByTestId('PlanStats.SaveReport')).toHaveTextContent('Plan saved')
  })

  it('reports a failed save without clearing the draft', async () => {
    // The failed save is the subject: the draft surviving it is the assertion.
    drivingAFailedWrite()
    mockReads(planBody())
    vi.spyOn(api, 'patch').mockRejectedValue(new Error('server said no'))
    renderPage()

    await userEvent.type(await scopeBox(), 'Payments')
    await userEvent.click(await screen.findByTestId('PlanStats.Save'))

    expect(await screen.findByTestId('PlanStats.SaveReport')).toHaveTextContent(
      'The plan was not saved',
    )
    // Still dirty, still typed. A failed save that cleared the box would lose the work it failed to
    // write, which is the worst possible response to a transient 503.
    expect(screen.getByTestId('PlanStats.Dirty')).toBeInTheDocument()
    expect(await scopeBox()).toHaveValue('Payments')
  })

  it('enables Save for a holder of plan:edit', async () => {
    mockReads(planBody())
    renderPage()
    await userEvent.type(await scopeBox(), 'Payments')

    await waitFor(() => expect(screen.getByTestId('PlanStats.Save')).toBeEnabled())
  })

  it('disables Save for a caller without plan:edit', async () => {
    mockReads(planBody(), ['project:read'])
    renderPage()
    await userEvent.type(await scopeBox(), 'Payments')

    // The pair with the test above is what makes this non-vacuous: alone it passes against a page
    // with no gate at all, because `useCan` is fail-closed while the answer is pending.
    await waitFor(() => expect(screen.getByTestId('PlanStats.Save')).toBeDisabled())
  })

  /**
   * The Test artefacts tab writes OUTSIDE this Save (§97 phase 5) — its own row, its own key — so
   * the two assertions are a pair: the control is there, and pressing it must never make the plan's
   * banner appear. A tab named in `dirtyTabs` would be asking the author to press Save for
   * something already written.
   */
  it('offers Register on the artefacts tab without making the plan dirty', async () => {
    mockReads(planBody(), ['project:read', 'plan:edit', 'artifact:edit'])
    renderPage('artefacts')

    /*
      Re-queried inside `waitFor`, and holding the node from `findByTestId` instead is a trap that
      cost this test an hour: `PermissionGate`'s denied branch wraps the control in a tooltip span,
      so when the verdict lands React swaps one subtree for another and mints a NEW button. A
      captured reference goes on pointing at the detached, permanently-disabled one.
    */
    await waitFor(() => expect(screen.getByTestId('PlanStats.Artefacts.Register')).toBeEnabled())
    await userEvent.click(screen.getByTestId('PlanStats.Artefacts.Register'))

    // The dialog opens with THIS plan pre-selected — continuing what the reader was doing rather
    // than making them restate which cycle they are looking at.
    await screen.findByTestId('Artifacts.Dialog.Title')
    expect(screen.getByTestId('Artifacts.Dialog.Plan')).toHaveValue(PLAN)
    expect(screen.queryByTestId('PlanStats.Dirty')).not.toBeInTheDocument()
  })

  it('disables Register for a caller without artifact:edit', async () => {
    mockReads(planBody(), ['project:read', 'plan:edit'])
    renderPage('artefacts')

    // The pair with the test above: `useCan` is fail-closed while pending, so this alone passes
    // against a control with no gate at all (§66).
    await waitFor(() => expect(screen.getByTestId('PlanStats.Artefacts.Register')).toBeDisabled())
  })

  it('renders the Progress tab for a reader who can edit nothing', async () => {
    mockReads(planBody(), ['project:read'])
    renderPage('progress')

    // The page is a report first. A reader who came for the numbers must not be met by a disabled
    // editor — or by nothing at all, which is what gating the whole body on `plan:edit` would do.
    expect(await screen.findByTestId('PlanStats.AggregationNotice')).toBeInTheDocument()
    expect(screen.queryByTestId('PlanStats.ScopeText')).not.toBeInTheDocument()
  })
})
