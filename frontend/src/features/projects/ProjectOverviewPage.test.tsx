import { render, screen, waitFor, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Issue, IssueSummary, RepositoryDashboard } from '../../api/types'
import { completionBases } from '../../lib/labels'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, anIssue, anIssueSummary, anIssueType } from '../../test/fixtures'
import { drivingAFailedRead } from '../../test/renderFailures'
import { ProjectOverviewPage } from './ProjectOverviewPage'

/**
 * `project-overview.html` — and `solo-overview.html`, which is the same screen and is asserted here
 * as the acceptance criterion it is (011 T7 step 5).
 *
 * What this file pins that no gate can see:
 *
 * 1. **A section M1 has no data for explains itself.** The release panel renders the empty state,
 *    with what a release IS in it. Worded as a negative because the failure is a panel that renders
 *    with nothing in it and no explanation.
 * 2. **Budget and Team are not rendered at all.** The difference from the release panel is the whole
 *    rule: a release is a thing this project will have and has none yet, while a budget is a module
 *    that is not installed — and an empty-state card for one is an advertisement.
 * 3. **No client chip and no client prompt.** `solo-overview.html`'s own note: the Clients sidebar
 *    entry staying is "the only trace". An empty `Client: —` chip is exactly what a naive port
 *    produces, and today the criterion holds structurally because `Project` carries no client at
 *    all — so this is the assertion that makes Mc's addition a decision rather than an accident.
 * 4. **The quick-tasks framing is a filter over the same list**, not a second endpoint: a project
 *    with no epics is a project whose whole backlog is quick tasks.
 * 5. **Every epic's completion carries its basis**, and an epic whose completion did not arrive gets
 *    no meter rather than a zero.
 * 6. **A failed epics read does not read as a project with no epics**, and the rest of the page
 *    still renders — the sections do not depend on each other.
 */

const PROJECT_ID = 'p1'

const DASHBOARD: RepositoryDashboard = {
  generatedAt: '2026-08-01T09:00:00Z',
  lifecycle: [],
  health: [],
  healthComputedAt: '2026-08-01T08:00:00Z',
  byFeature: [],
  byOwner: [],
  byLastRun: [
    {
      id: 'f1',
      label: 'Payments',
      segments: [
        { key: 'PASSED', count: 48, filter: {} },
        { key: 'FAILED', count: 2, filter: {} },
      ],
    },
  ],
  automationCoverage: {
    automated: 0,
    toBeAutomated: 0,
    manual: 0,
    notAutomatable: 0,
    coveragePercent: 0,
  },
  recentActivity: [],
}

const EPIC_TYPE = anIssueType({ id: 'ty-epic', name: 'Epic', level: 'EPIC', countsTowardCompletion: false })

const EPIC = anIssueSummary({ key: 'ACME-101', title: 'Checkout rebuild', type: EPIC_TYPE })

const BACKLOG = [
  anIssueSummary({ key: 'ACME-201', title: 'Remember delivery address', estimateHours: 8 }),
  anIssueSummary({ key: 'ACME-204', title: 'Currency symbol wrong in Swedish' }),
]

interface Reads {
  epics?: IssueSummary[]
  backlog?: IssueSummary[]
  all?: IssueSummary[]
  epic?: Issue
  dashboard?: RepositoryDashboard
  failEpics?: boolean
}

function mockReads({
  epics = [EPIC],
  backlog = BACKLOG,
  all = [EPIC, ...BACKLOG],
  epic = anIssue({
    key: 'ACME-101',
    title: 'Checkout rebuild',
    type: EPIC_TYPE,
    completion: {
      percent: 70,
      basis: 'ESTIMATE',
      done: 12,
      total: 17,
      excludedSpikes: 1,
      estimatedHoursDone: 214,
      estimatedHoursTotal: 306,
    },
  }),
  dashboard = DASHBOARD,
  failEpics = false,
}: Reads = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['project:read', 'issue:create'])
    if (path.includes('/dashboard')) return dashboard
    if (path.startsWith('/issues/')) return epic
    if (path.startsWith('/issues?')) {
      if (path.includes('level=EPIC')) {
        if (failEpics) throw new Error('epics unavailable')
        return epics
      }
      if (path.includes('unparented=true')) return backlog
      return all
    }
    if (/^\/projects\/[^/]+$/.test(path)) {
      return aProject({ id: PROJECT_ID, key: 'ACME', name: 'Acme Storefront', caseCount: 154 })
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={`/projects/${PROJECT_ID}`}>
      <Routes>
        <Route path="/projects/:projectId" element={<ProjectOverviewPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('The delivery half', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists each epic with its completion and the basis of it', async () => {
    mockReads()
    renderPage()

    const row = await screen.findByTestId('ProjectOverview.Epics.Row')
    expect(within(row).getByText('Checkout rebuild')).toBeTruthy()

    const meter = await screen.findByTestId('ProjectOverview.Epics.Row.Completion')
    expect(within(meter).getByTestId('ProjectOverview.Epics.Row.Completion.Percent').textContent).toBe('70%')
    /*
      The basis, and it is not decoration: an epic weighted by hours and one counted by heads are
      different measurements (D8). Cross-surface rather than literal — `completionBases` is what the
      epic's own page reads, so a screen with its own two words is not expressible here.
    */
    expect(
      within(meter).getByTestId('ProjectOverview.Epics.Row.Completion.Basis').textContent,
    ).toBe(completionBases.ESTIMATE)
  })

  it('renders no meter for an epic whose completion did not arrive', async () => {
    // `Issue.completion` is nullable on the wire. A meter reading 0% where the server sent nothing
    // is a percentage of nothing, which is the one thing D8 forbids outright.
    mockReads({ epic: anIssue({ key: 'ACME-101', type: EPIC_TYPE }) })
    renderPage()

    await screen.findByTestId('ProjectOverview.Epics.Row.NoCompletion')
    expect(screen.queryByTestId('ProjectOverview.Epics.Row.Completion')).toBeNull()
  })

  it('counts the open issues and names how many are overdue', async () => {
    mockReads({
      all: [
        anIssueSummary({ key: 'ACME-1', overdue: true, dueDate: '2026-08-01' }),
        anIssueSummary({ key: 'ACME-2' }),
        anIssueSummary({
          key: 'ACME-3',
          status: { id: 's6', name: 'Done', kind: 'DONE', position: 5 },
        }),
      ],
    })
    renderPage()

    const tile = await screen.findByTestId('ProjectOverview.OpenIssues.Tile')
    // Two, not three: a DONE-kind issue is not open. The kind is what decides it, never the name —
    // a project may call its done column anything.
    expect(tile.textContent).toContain('2')
    expect(tile.textContent).toContain('1 overdue')
  })

  it('states what the pass rate was counted over', async () => {
    /*
      There is no project-wide latest-result-per-case count on the contract, so this tile sums
      `byLastRun` — which holds only cases carrying a Feature. A percentage whose denominator
      silently excludes part of the project, beside a case count that does not, is the
      two-contradictory-numbers failure this repo has shipped once. The caption is the mitigation.
    */
    mockReads()
    renderPage()

    const tile = await screen.findByTestId('ProjectOverview.TestsPassing.Tile')
    expect(tile.textContent).toContain('96%')
    expect(tile.textContent).toContain('48 of 50')
    expect(tile.textContent).toContain('cases with a feature')
  })

  it('says nothing has run rather than reporting a pass rate of zero', async () => {
    mockReads({ dashboard: { ...DASHBOARD, byLastRun: [] } })
    renderPage()

    const none = await screen.findByTestId('ProjectOverview.TestsPassing.None')
    expect(none.textContent).toContain('not a pass rate of zero')
    expect(screen.queryByTestId('ProjectOverview.TestsPassing.Tile')).toBeNull()
  })
})

describe('The sections M1 has no data for', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('explains the release section rather than rendering an empty box', async () => {
    // 009's acceptance criterion, worded as a negative because the failure is a panel that renders
    // with nothing in it and no explanation.
    mockReads()
    renderPage()

    const empty = await screen.findByTestId('ProjectOverview.Release.Empty')
    expect(empty.textContent).toContain('No releases yet')
    // What a release IS, and what it buys — not merely that there are none.
    expect(empty.textContent).toContain('a version you ship')
    expect(empty.textContent).toContain('readiness is measured against it')
    // The action is rendered and says why it cannot be used yet: a link to a route that does not
    // exist would be render-then-reject with the reject left to the reader.
    expect(screen.getByTestId('ProjectOverview.Release.Create')).toBeDisabled()
    // And the sentence that keeps the page honest in the meantime.
    expect(screen.getByTestId('ProjectOverview.Release.Foot').textContent).toContain(
      'do not depend on each other',
    )
  })

  it('renders no budget card and no team card at all', async () => {
    /*
      M6 and M7. The difference from the release panel is the whole rule: a release is a thing this
      project will have and has none YET, so its absence is a state. A budget is a module that is
      not installed, and an empty-state card for one is an advertisement rather than an explanation.
    */
    mockReads()
    renderPage()

    await screen.findByTestId('ProjectOverview.Release.Empty')

    expect(screen.queryByText(/Budget burn/i)).toBeNull()
    expect(screen.queryByText(/Approved hours/i)).toBeNull()
    expect(screen.queryByText(/Staffing/i)).toBeNull()
    // Nor the two KPI tiles that are a release's numbers.
    expect(screen.queryByText(/To target date/i)).toBeNull()
    expect(screen.queryByText(/complete$/i)).toBeNull()
  })

  it('renders the test-health panel, which M1 owns rather than defers', async () => {
    /*
      009's M1 says this page lands "without its release and test-health panels". Wrong in the cheap
      direction: `DashboardPage` already drew these numbers against endpoints that exist, and D27
      says the overview reuses those regions — so the panel cost a move, not a build.
    */
    mockReads()
    renderPage()

    const passed = await screen.findByTestId('ProjectOverview.TestHealth.PASSED')
    expect(passed.textContent).toContain('48')
    expect(screen.getByTestId('ProjectOverview.TestHealth.FAILED').textContent).toContain('2')
  })
})

describe('solo-overview.html is this screen, for a company with no clients', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders no client chip and no client prompt when the company has no clients', async () => {
    /*
      solo-overview.html. Nothing nags a company that has none — an empty "Client: —" chip is exactly
      the nagging the mockup rules out, and it is what a naive port produces.

      Today this holds because `Project` carries no client field at all (clients are module Mc), so
      the assertion is here to make Mc's arrival a deliberate decision: a chip added then will fail
      this test on every project that has no client, which is the moment to decide rather than to
      discover.
    */
    mockReads()
    renderPage()

    await screen.findByTestId('ProjectOverview.Epics.Row')

    expect(screen.queryByText(/^Client:/)).toBeNull()
    expect(screen.queryByText(/attach a client/i)).toBeNull()
    expect(screen.queryByText(/None \(internal\)/i)).toBeNull()
  })

  it('renders the quick-tasks panel for a project whose issues are all parentless', async () => {
    // The solo case's shape: no epic, no story, no ceremony. It is a filter over the same list, not
    // a second endpoint — the epics list arriving empty is what decides the framing.
    mockReads({ epics: [], all: BACKLOG })
    renderPage()

    /*
      Waited for rather than read once: the heading is "Unscheduled backlog" until the epics read
      ARRIVES empty, which is the whole of the next test — so a synchronous read here would assert
      the in-flight state and pass on a page that never switches.
    */
    await waitFor(() =>
      expect(screen.getByTestId('ProjectOverview.Backlog.Title').textContent).toBe('Quick tasks'),
    )
    expect(screen.getByText('no epic, no story, no ceremony')).toBeTruthy()
    // Same rows, same request.
    await waitFor(() => expect(screen.getAllByTestId('ProjectOverview.Backlog.Row')).toHaveLength(2))
  })

  it('calls the same rows the unscheduled backlog on a project that has epics', async () => {
    mockReads()
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('ProjectOverview.Backlog.Row')).toHaveLength(2))
    expect(screen.getByTestId('ProjectOverview.Backlog.Title').textContent).toBe(
      'Unscheduled backlog',
    )
  })

  it('does not flash the quick-tasks framing while the epics read is in flight', async () => {
    /*
      "Solo until proven otherwise" is wrong the other way: `data` is undefined while the read is in
      flight, and treating that as "no epics" would show the quick-tasks heading on every load of
      every project that has them. Only an epics list that ARRIVED and is empty means solo.
    */
    mockReads()
    renderPage()

    // Before anything resolves, the heading must be the one for a project that has epics.
    expect(screen.getByTestId('ProjectOverview.Backlog.Title').textContent).toBe(
      'Unscheduled backlog',
    )
  })
})

describe('When a section fails', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the overview through DataRegion when the epics read fails', async () => {
    // A project overview whose epics failed must not read as a project with no epics — the sections
    // do not depend on each other, so the rest of the page still renders.
    drivingAFailedRead()
    mockReads({ failEpics: true })
    renderPage()

    await screen.findByTestId('ProjectOverview.Epics.Region.Error')
    // Not the empty state: "no epics yet" about a project whose epics could not be read is the
    // reassuring-but-false screen design language §7 exists to prevent.
    expect(screen.queryByTestId('ProjectOverview.Epics.Region.Empty')).toBeNull()
    // And the sections beside it are unaffected.
    expect(await screen.findByTestId('ProjectOverview.Release.Empty')).toBeTruthy()
    await waitFor(() => expect(screen.getAllByTestId('ProjectOverview.Backlog.Row')).toHaveLength(2))
  })
})
