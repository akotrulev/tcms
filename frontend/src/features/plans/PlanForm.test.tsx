import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestPlan } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { PlanStatisticsPage } from './PlanStatisticsPage'

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
    ...overrides,
  } as unknown as TestPlan
}

function mockReads(plan: TestPlan, permissions: string[] = ['project:read', 'plan:edit']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes(`/plans/${PLAN}/statistics`)) {
      return {
        counts: { total: 0, passed: 0, failed: 0, blocked: 0, skipped: 0, retest: 0, notRun: 0 },
        coveragePercent: 0,
        stale: false,
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
    if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }
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

  it('renders the Progress tab for a reader who can edit nothing', async () => {
    mockReads(planBody(), ['project:read'])
    renderPage('progress')

    // The page is a report first. A reader who came for the numbers must not be met by a disabled
    // editor — or by nothing at all, which is what gating the whole body on `plan:edit` would do.
    expect(await screen.findByTestId('PlanStats.AggregationNotice')).toBeInTheDocument()
    expect(screen.queryByTestId('PlanStats.ScopeText')).not.toBeInTheDocument()
  })
})
