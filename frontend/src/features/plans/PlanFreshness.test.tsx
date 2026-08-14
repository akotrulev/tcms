import { render, screen } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestPlan } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, noCounts, planStatistics, projectSettings } from '../../test/fixtures'
import { PlanStatisticsPage } from './PlanStatisticsPage'

/**
 * **Two ages on one screen, and they must not read as one signal** (§101).
 *
 * `stale` says nothing has been *executed* against this plan in fourteen days. `computedAt` says how
 * old the *sums* are — since §101 they come from the `plan_snapshot` rollup under `LATEST` and
 * `LATEST_PER_CONFIG`, so they can lag by up to one scheduler tick. A plan executed an hour ago and
 * rolled up ten minutes ago is fresh by one measure and behind by the other.
 *
 * Plan §6p: *"a plan page showing 94% from three weeks ago, presented as current, is the most
 * dangerous screen in the product"*. Collapsing the two into one line — or styling the second like
 * the first — would put two different claims behind one signal, which is §65's
 * two-contradictory-percentages defect with the contradiction hidden.
 *
 * **Both directions are asserted**, per §66's rule and §76's mutation that failed to redden: a test
 * that only checks the null case passes against a page that never renders the line at all.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

/**
 * The COMPLETE plan body, copied from `PlanLifecycle.test.tsx` rather than trimmed to what this
 * test reads — and its own comment says why: the fields §97 phase 2 made required on `TestPlan`
 * went missing silently behind `as unknown as TestPlan`, and the page then crashed on mount
 * reading them. Trimming this fixture cost this test three red runs before the cast was the
 * suspect.
 *
 * **The cast is gone as of §196** — the declared return type now binds, so a field added to
 * `TestPlan` is a build error on this line instead of a mount-time crash. `keySeq` is what it
 * would have caught: §165 put it on the contract and this fixture never gained it.
 */
function planBody(): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
    keySeq: 4,
    name: 'Release 4.4 regression',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 4,
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
    lockVersion: 1,
  }
}

function mockReads(computedAt: string | null, stale = false) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['project:read'])
    if (path.includes('/statistics')) {
      return planStatistics({
        planId: PLAN,
        planKey: 'TP-4',
        planName: 'Release 4.4 regression',
        counts: noCounts({ passed: 3, failed: 1, total: 4 }),
        coveragePercent: 100,
        // Non-null, because the freshness caption above `computedAt` only renders when there is a
        // latest execution — without it this test would be asserting the presence of one line while
        // the other was silently absent for an unrelated reason.
        latestExecutionAt: '2026-08-06T11:00:00Z',
        stale,
        computedAt,
      })
    }
    if (path.includes(`/plans/${PLAN}`)) return planBody()
    if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
    if (path.startsWith('/projects/')) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

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

describe('plan statistics — the age of the arithmetic', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('states the rollup instant when the figures came from one', () => {
    mockReads('2026-08-06T11:45:00Z')
    renderPage()

    return screen.findByTestId('PlanStats.ComputedAt').then((line) => {
      expect(line.textContent).toContain('Figures as of')
      expect(line.textContent).toContain('updated every 15 minutes')
    })
  })

  it('says "Computed now" when the server computed live — the other direction', () => {
    // Null is what WORST / BEST / ALL always answer, and what any plan answers before its first
    // rollup tick. Asserting it is what stops the line from being a decoration that only ever
    // renders one way — and asserting the ABSENCE of the cadence sentence is the barrier, because
    // "updated every 15 minutes" beside a live figure is a false claim about a number that is not.
    mockReads(null)
    renderPage()

    return screen.findByTestId('PlanStats.ComputedAt').then((line) => {
      expect(line.textContent).toContain('Computed now')
      expect(line.textContent).not.toContain('updated every 15 minutes')
    })
  })

  it('keeps the two ages on separate lines, so neither can be read as the other', () => {
    // The defect this test exists for: a plan whose executions are two weeks old but whose rollup
    // ran a minute ago is stale by one measure and current by the other. One line would have to
    // pick, and whichever it picked would be wrong half the time.
    mockReads('2026-08-06T11:59:00Z', true)
    renderPage()

    return screen.findByTestId('PlanStats.Freshness').then((executions) => {
      expect(executions.textContent).toContain('nothing has been executed')

      const arithmetic = screen.getByTestId('PlanStats.ComputedAt')
      expect(arithmetic).not.toBe(executions)
      expect(arithmetic.textContent).toContain('Figures as of')
      expect(arithmetic.textContent).not.toContain('nothing has been executed')
    })
  })
})
