import { render, screen, waitFor, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type {
  BudgetBurn,
  ClientRef,
  Issue,
  IssueSummary,
  PermissionKey,
  ReleaseReadiness,
  ReleaseSummary,
  RepositoryDashboard,
} from '../../api/types'
import { completionBases } from '../../lib/labels'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import {
  aBudgetBurn,
  aProject,
  aReadinessSummary,
  aReleaseBlocker,
  aReleaseReadiness,
  aReleaseStories,
  aReleaseSummary,
  anIssue,
  anIssueSummary,
  anIssueType,
  aStatusRef,
} from '../../test/fixtures'
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
 * 2. ~~**Budget and Team are not rendered at all.** The difference from the release panel is the
 *    whole rule: a release is a thing this project will have and has none yet, while a budget is a
 *    module that is not installed — and an empty-state card for one is an advertisement.~~ →
 *    **reversed 2026-09-05 (019 T6) for the budget half.** The assertion was correct when it was
 *    written and the module it waited for landed, so what is pinned now is the panel and its three
 *    readings: an hours ceiling draws `418h / 600h`, a MONEY ceiling draws the hours and the
 *    percentage and **never a currency** (019 D-h), and a project with **no** budget still draws no
 *    panel — which is the one part of the old assertion that survives, and is now 019 D-p rather
 *    than an absent module. Team (M7) is still absent and is still asserted.
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
 * 7. **013 T6:** the release panel and the two release tiles read real data; the empty state's copy
 *    is byte-identical to what M1 shipped, because a project with no releases is now a permanent
 *    ordinary state rather than an unbuilt module; and both tiles render an em dash **with a reason**
 *    rather than disappearing, because a tile that vanishes moves the three beside it.
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

/** `2.4`, due, with 21 of 27 stories done and 61 of 63 cases passing — the mockup's own numbers. */
const RELEASE = aReleaseSummary({
  id: 'rel-24',
  version: '2.4',
  targetDate: '2026-09-18',
  readiness: aReadinessSummary({
    verdict: 'NOT_READY',
    storiesDone: 21,
    storiesTotal: 27,
    linkedCases: 63,
    hasRun: true,
    casesPassed: 61,
    casesFailed: 2,
  }),
})

interface Reads {
  epics?: IssueSummary[]
  backlog?: IssueSummary[]
  all?: IssueSummary[]
  epic?: Issue
  dashboard?: RepositoryDashboard
  failEpics?: boolean
  releases?: ReleaseSummary[]
  readiness?: ReleaseReadiness
  failReadiness?: boolean
  // `PermissionKey[]` and not `string[]`: `heldPermissions` takes the union, so a bare `string[]`
  // does not compile. Fixed in plan 014 T4, having failed `npm run build` since 55cb4bc0 — T1
  // found it, verified it predated that task, and left it because T1 stages four files and this is
  // a fifth. It blocked every frontend gate in between.
  permissions?: PermissionKey[]
  /** 018 Mc — the client this project is for, or `null` for internal work (the default). */
  client?: ClientRef | null
  /**
   * 019 M8 — the project-scope burn. The default is a project with **no budget** (`basis: null`),
   * which is the common case and the one that draws no panel at all.
   */
  burn?: BudgetBurn
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
  releases = [],
  readiness = aReleaseReadiness(),
  failReadiness = false,
  permissions = ['project:read', 'issue:create'],
  client = null,
  burn = aBudgetBurn(),
}: Reads = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/dashboard')) return dashboard
    // Anchored, so the burn sub-resource is not caught by the `/projects/{id}` prefix below.
    if (/^\/projects\/[^/]+\/budget-burn$/.test(path)) return burn
    // Anchored ahead of `/releases/{id}` so the readiness sub-resource is not caught by the prefix —
    // the collision `/boards/{id}` vs `/boards/{id}/cards` cost 012 T7 the same half hour.
    if (/^\/releases\/[^/]+\/readiness$/.test(path)) {
      if (failReadiness) throw new Error('readiness unavailable')
      return readiness
    }
    if (/\/projects\/[^/]+\/releases$/.test(path)) return releases
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
      return aProject({ id: PROJECT_ID, key: 'ACME', name: 'Acme Storefront', caseCount: 154, client })
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

describe('A project with no releases — solo-overview.html\u2019s rendering, unchanged in copy', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('explains the release section rather than rendering an empty box', async () => {
    /*
      009's acceptance criterion, worded as a negative because the failure is a panel that renders
      with nothing in it and no explanation. **The copy is asserted verbatim on purpose (013 T6):**
      nothing is backfilled into `release` when M3 ships (D14), so this is the state every freshly
      provisioned project is in — permanently ordinary rather than temporarily unbuilt — and the
      sentence saying what a release IS remains the right sentence.
    */
    mockReads()
    renderPage()

    const empty = await screen.findByTestId('ProjectOverview.Release.Empty')
    expect(empty.textContent).toContain('No releases yet')
    // What a release IS, and what it buys — not merely that there are none.
    expect(empty.textContent).toContain('a version you ship')
    expect(empty.textContent).toContain('readiness is measured against it')
    // And the sentence that keeps the page honest.
    expect(screen.getByTestId('ProjectOverview.Release.Foot').textContent).toContain(
      'do not depend on each other',
    )
  })

  it('disables Create a release for a caller without release:manage, and says why', async () => {
    // §31 disable-don't-hide. The reason is now the CALLER'S KEY rather than the module's absence,
    // which is the one thing 013 T6 changed about this button.
    mockReads()
    renderPage()

    await screen.findByTestId('ProjectOverview.Release.Empty')
    await waitFor(() => {
      // Re-queried inside the waitFor: `PermissionGate` REMOUNTS its child when the verdict lands,
      // so a node captured before the answer is a detached element that stays disabled forever.
      expect(screen.getByTestId('ProjectOverview.Release.Create')).toBeDisabled()
    })
  })

  it('links Create a release for a caller who holds release:manage', async () => {
    mockReads({ permissions: ['project:read', 'issue:create', 'release:manage'] })
    renderPage()

    await screen.findByTestId('ProjectOverview.Release.Empty')
    await waitFor(() => {
      expect(screen.getByTestId('ProjectOverview.Release.Create')).toHaveAttribute(
        'href',
        `/projects/${PROJECT_ID}/releases/new`,
      )
    })
  })

  it('keeps both release tiles on screen with a reason, rather than dropping them', async () => {
    /*
      A tile that vanishes moves the three beside it, so the row would be a different shape on a
      project that has not typed a version yet. The em dash plus the `title` is the alternative, and
      it is the same disable-don't-hide argument applied to a number.
    */
    mockReads()
    renderPage()

    const complete = await screen.findByTestId('ProjectOverview.ReleaseComplete.None')
    expect(complete.textContent).toContain('no release planned on this project')
    expect(complete).toHaveAttribute('title', expect.stringContaining('Create one'))

    const target = screen.getByTestId('ProjectOverview.TargetDate.None')
    expect(target.textContent).toContain('To target date')
    expect(target.textContent).toContain('no release planned')
  })

  it('renders no budget card for a project that has no budget, and no team card at all', async () => {
    /*
      ~~M6 and M7, and the difference from the release panel is the whole rule: a release is a thing
      this project will have, so its absence is a STATE with a tile and an empty panel. A budget is
      a module that is not installed, and an empty-state card for one is an advertisement.~~ →
      **reversed by 019 T6.** The module exists. What is asserted here now is the OTHER reason the
      card is absent: `basis: null` is a project nobody set a ceiling on — the common case, and the
      state `billing.html`'s internal project is in — and it draws no panel, no empty state, no
      warning tone and no *set a budget* prompt (019 D-p). Team (M7) is still not built.
    */
    mockReads()
    renderPage()

    await screen.findByTestId('ProjectOverview.Release.Empty')

    expect(screen.queryByTestId('ProjectOverview.Budget')).toBeNull()
    expect(screen.queryByText(/Budget burn/i)).toBeNull()
    expect(screen.queryByText(/Staffing/i)).toBeNull()
  })

  it('draws the burn against an hours ceiling, and never a currency', async () => {
    mockReads({ burn: aBudgetBurn({ basis: 'HOURS', budgetHours: 600, approvedHours: 418, burnPercent: 70 }) })
    renderPage()

    const hours = await screen.findByTestId('ProjectOverview.Budget.Hours')
    expect(hours.textContent).toBe('418h / 600h')
    expect(screen.getByTestId('ProjectOverview.Budget.Meter.Percent').textContent).toBe('70%')
    expect(screen.getByTestId('ProjectOverview.Budget')).toHaveTextContent(
      /What an hour costs is Finance’s, and is not on this page/i,
    )
    expect(screen.queryByText(/€|CHF|\$/)).toBeNull()
  })

  it('draws a money ceiling as a percentage alone, and an unpriceable one as unknown', async () => {
    /*
      019 D-h: `BudgetBurn` has no amount and no currency member at all, so a MONEY budget reaches
      this page as a percentage and the hours behind it. And `burnPercent: null` is *unknown* — a
      money ceiling on a project whose client has no rate card cannot be priced, and a zero would
      read as *nothing spent* on a project that has spent a quarter.
    */
    mockReads({ burn: aBudgetBurn({ basis: 'MONEY', budgetHours: null, approvedHours: 418 }) })
    renderPage()

    const hours = await screen.findByTestId('ProjectOverview.Budget.Hours')
    expect(hours.textContent).toBe('418h')
    expect(screen.getByTestId('ProjectOverview.Budget.Meter.Unknown').textContent).toBe('unknown')
    expect(screen.queryByText(/€|CHF|\$/)).toBeNull()
  })

  it('draws an overrun past 100% rather than clamping it', async () => {
    // The meter is `BudgetPanel`'s, so 102% looks the same here as it does to Finance — a full bar
    // with a red tail. A `Math.min(percent, 100)` anywhere would hide the one project that matters.
    mockReads({ burn: aBudgetBurn({ basis: 'HOURS', budgetHours: 500, approvedHours: 512, burnPercent: 102.4 }) })
    renderPage()

    expect((await screen.findByTestId('ProjectOverview.Budget.Meter.Percent')).textContent).toBe('102%')
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

describe('The release panel and its two tiles, with a release in them (013 T6)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    /*
      The clock is pinned, and it has to be: `formatDueIn` counts whole days from *now*, so the
      target-date assertion below was true on the day it was written and one day less true every day
      after — it read "in 17 days" against a fixture dated 2026-09-18 and started failing on
      2026-09-02. A test whose result depends on the date it is run is not a test of this screen.
    */
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-09-01T09:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('names the next release, its badge and its target date', async () => {
    mockReads({ releases: [RELEASE] })
    renderPage()

    const body = await screen.findByTestId('ProjectOverview.Release.Body')
    expect(within(body).getByTestId('ProjectOverview.Release.Version').textContent).toBe('2.4')
    // The badge is the verdict's word, never the status's — `releaseBadge` owns that and D-e is why.
    expect(within(body).getByTestId('ProjectOverview.Release.Badge').textContent).toBe('Not ready')
    // `formatDueIn`, not `formatAge`: a future date read as "just now" is what T5 paid for.
    const target = within(body).getByTestId('ProjectOverview.Release.Target').textContent ?? ''
    expect(target).toContain('2026-09-18')
    expect(target).toContain('in 17 days')
    expect(target).not.toContain('just now')
  })

  it('is the earliest PLANNED target date and not the first row the server sent', async () => {
    // The rule lives in `features/releases/nextRelease.ts` with its own test; this asserts the
    // screen actually asks it, which is the half a pure test cannot see.
    mockReads({
      releases: [
        RELEASE,
        aReleaseSummary({ id: 'rel-25', version: '2.5', targetDate: '2026-08-01' }),
      ],
    })
    renderPage()

    const body = await screen.findByTestId('ProjectOverview.Release.Body')
    expect(within(body).getByTestId('ProjectOverview.Release.Version').textContent).toBe('2.5')
  })

  it('draws both meters from the readiness carried INLINE on the row', async () => {
    // `ReleaseSummary.readiness` is on the list payload, so the meters have an answer before the
    // second read lands and never flicker through a wrong one.
    mockReads({ releases: [RELEASE] })
    renderPage()

    const stories = await screen.findByTestId('ProjectOverview.Release.Stories')
    expect(stories.textContent).toContain('21 / 27')
    expect(screen.getByTestId('ProjectOverview.Release.Cases').textContent).toContain('61 / 63')
  })

  it('says "no run yet" instead of a meter when nothing has been executed (D-d)', async () => {
    /*
      The claim this whole module exists to keep apart. `hasRun: false` means `casesPassed` is NULL
      and not zero, and a bar drawn at zero says "nothing passed" where the truth is "nobody has
      looked". `aReadinessSummary`'s inert defaults are what make this reachable.
    */
    mockReads({
      releases: [aReleaseSummary({ id: 'rel-30', version: '3.0', targetDate: '2026-11-01' })],
    })
    renderPage()

    const noRun = await screen.findByTestId('ProjectOverview.Release.NoRun')
    expect(noRun.textContent).toContain('no run yet')
    expect(noRun.textContent).toContain('not a pass rate of zero')
    expect(screen.queryByTestId('ProjectOverview.Release.Cases')).toBeNull()
  })

  it('writes the red note from doneButFailing, naming the story and the case', async () => {
    mockReads({
      releases: [RELEASE],
      readiness: aReleaseReadiness({
        version: '2.4',
        verdict: 'NOT_READY',
        blockers: [
          aReleaseBlocker({
            key: 'ACME-118',
            title: 'Guest checkout',
            status: aStatusRef({ kind: 'DONE', name: 'Done' }),
            failedCaseKeys: ['TC-311'],
          }),
        ],
      }),
    })
    renderPage()

    const note = await screen.findByTestId('ProjectOverview.Release.Failing')
    expect(note.textContent).toContain('ACME-118')
    expect(note.textContent).toContain('TC-311')
    expect(note.textContent).toContain('is done but its case')
  })

  it('keeps the panel when the readiness read fails, and says which half is missing', async () => {
    /*
      Readiness is the panel's SECOND, dependent request and it is optional here: the version, the
      badge, the date and both meters come off the list payload. A failed-state block would have
      replaced a panel that is still telling the reader which version is next and when it is due.
    */
    drivingAFailedRead() // the readiness read, deliberately — this test is about degrading in place
    mockReads({ releases: [RELEASE], failReadiness: true })
    renderPage()

    const body = await screen.findByTestId('ProjectOverview.Release.Body')
    expect(within(body).getByTestId('ProjectOverview.Release.Version').textContent).toBe('2.4')
    await waitFor(() => {
      expect(
        screen.getByTestId('ProjectOverview.Release.ReadinessFailed').textContent,
      ).toContain('came with the release itself and are current')
    })
    // And no red note, because the blockers it is derived from never arrived.
    expect(screen.queryByTestId('ProjectOverview.Release.Failing')).toBeNull()
  })

  it('states the completion tile\u2019s basis, which is NOT the mockup\u2019s estimate', async () => {
    /*
      `project-overview.html` reads "by estimate · 214 of 306 h". `ReleaseReadiness` carries no
      estimate roll-up at all, so the tile is by story count AND SAYS SO — a percentage never changes
      meaning silently, and the caption is the whole of it.
    */
    mockReads({
      releases: [RELEASE],
      readiness: aReleaseReadiness({ stories: aReleaseStories({ done: 21, total: 27 }) }),
    })
    renderPage()

    const tile = await screen.findByTestId('ProjectOverview.ReleaseComplete.Tile')
    expect(tile.textContent).toContain('78%')
    expect(tile.textContent).toContain('2.4 complete')
    expect(tile.textContent).toContain('by story count · 21 of 27')
  })

  it('counts down to the target date, and says nothing when there is none', async () => {
    mockReads({ releases: [RELEASE] })
    renderPage()

    const tile = await screen.findByTestId('ProjectOverview.TargetDate.Tile')
    expect(tile.textContent).toContain('To target date')
    expect(tile.textContent).toContain('2.4 · 2026-09-18')
  })

  it('renders an em dash rather than 0% when the release counts no stories', async () => {
    // A release with nothing pointed at it yet. `percentOf` answers null over a zero denominator
    // rather than 0%, which would be a claim about progress on work nobody has scoped.
    mockReads({
      releases: [RELEASE],
      readiness: aReleaseReadiness({ stories: aReleaseStories({ done: 0, total: 0 }) }),
    })
    renderPage()

    /*
      `waitFor` and not `findByTestId`: the tiles are outside a `DataRegion`, so the FIRST paint has
      no releases yet and renders the same `.None` node with the other sentence. Finding the node is
      not the assertion — which sentence it settles on is.
    */
    await waitFor(() => {
      expect(screen.getByTestId('ProjectOverview.ReleaseComplete.None').textContent).toContain(
        'has no stories pointed at it yet',
      )
    })
    expect(screen.getByTestId('ProjectOverview.ReleaseComplete.None').textContent).not.toContain('0%')
  })

  it('never names a RELEASED version as next', async () => {
    mockReads({
      releases: [
        aReleaseSummary({ id: 'rel-23', version: '2.3', status: 'RELEASED', targetDate: '2026-07-01' }),
      ],
    })
    renderPage()

    // Back to the empty state, because "next" is what ships now and this one shipped.
    expect(await screen.findByTestId('ProjectOverview.Release.Empty')).toBeInTheDocument()
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

      ~~Today this holds because `Project` carries no client field at all (clients are module Mc),
      so the assertion is here to make Mc's arrival a deliberate decision.~~ → **Mc arrived
      (018 T5), and the decision was made here rather than discovered.** `Project.client` exists
      now and is `null` on this fixture, which is the common case rather than an edge: a project
      with no client is internal work and nothing in the product marks it as missing one (scope D1
      rule 1). The chip is rendered only when the field is non-null, and the test below is the other
      half — a conditional proven only in its false arm is a conditional proven off.
    */
    mockReads()
    renderPage()

    await screen.findByTestId('ProjectOverview.Epics.Row')

    expect(screen.queryByText(/^Client:/)).toBeNull()
    expect(screen.queryByText(/attach a client/i)).toBeNull()
    expect(screen.queryByText(/None \(internal\)/i)).toBeNull()
  })

  it('renders the client chip once the project HAS one — project-overview.html', async () => {
    /*
      The positive arm of the assertion above, and the reason both live here rather than in
      `features/clients/ClientAbsence.test.tsx` with the module's three other surfaces: this screen
      makes thirteen reads behind the mock above, and a second copy of it over there would be
      thirteen more ways for a client assertion to fail for a reason that has nothing to do with
      clients.
    */
    mockReads({ client: { id: 'cl-acme', name: 'Acme Retail' } })
    renderPage()

    expect(await screen.findByTestId('ProjectOverview.Client')).toHaveTextContent(
      'Client: Acme Retail',
    )
    // And still no nag beside it: the chip states a fact, it does not invite anything.
    expect(screen.queryByText(/attach a client/i)).toBeNull()
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
