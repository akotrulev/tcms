import { render, screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Project, RepositoryDashboard } from '../../api/types'
import { healthLabels, lifecycleLabels } from '../../lib/labels'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aBudgetBurn, anIssueSummary, aProjectWorkflow } from '../../test/fixtures'
import { ProjectOverviewPage } from '../projects/ProjectOverviewPage'

/**
 * The dashboard names things the way the rest of the app does (§180).
 *
 * ~~Rendered through `DashboardPage`.~~ → **011 T7 moved that route to `ProjectOverviewPage`**,
 * which mounts the same regions as its quality half (D27). The assertions are unchanged: they are
 * about the words on a tile, and the tile is the same component reached through a different page.
 *
 * Every tile and every bar segment on this page used to render **words the server sent**, and six
 * of the ten health signals disagreed with `lib/labels.ts`: the tile *"Outdated — spec changed"*
 * drilled — through its own `filter`, which is the entire design of §6o — into a case list whose
 * chips read *"Linked spec changed"*. One navigation, two vocabularies, and no gate on either stack
 * could see it, because neither stack held both strings.
 *
 * These tests are the observer that was missing. They are written as **cross-surface** assertions
 * rather than against literals: each one names the entry in the catalogue that the grid, the filter
 * bar and the case detail also read, so a test that passed while the two disagreed is not
 * expressible here. The one literal is the server's old wording, asserted **absent** — that string
 * is the regression itself, and only naming it can fail on its return.
 *
 * The segment colour is the same finding one field over and it never had a test at all: the server
 * composed `var(--color-draft)` from the constant's name while `StatusChip.tsx` kept the identical
 * string in `lifecycleVar`.
 */

const PROJECT: Project = {
  id: 'p1',
  key: 'OLB',
  name: 'Acme Online Banking',
  archived: false,
  caseCount: 0,
  myRole: null,
}

const DASHBOARD: RepositoryDashboard = {
  generatedAt: '2026-08-01T09:00:00Z',
  lifecycle: [
    { key: 'IN_REVIEW', count: 4, severity: 'INFO', filter: { lifecycleStatus: ['IN_REVIEW'] } },
  ],
  health: [
    {
      key: 'OUTDATED_SPEC_CHANGED',
      count: 78,
      severity: 'ERROR',
      filter: { healthSignal: ['OUTDATED_SPEC_CHANGED'] },
    },
  ],
  healthComputedAt: '2026-08-01T08:00:00Z',
  byFeature: [
    {
      id: 'f1',
      label: 'Payments › Refunds',
      segments: [{ key: 'DRAFT', count: 9, filter: { lifecycleStatus: ['DRAFT'] } }],
    },
  ],
  byOwner: [],
  // 005 N3's third segmentation — by the outcome of each case's last execution. Empty
  // here: these files are about the tiles, not the breakdown (B1).
  byLastRun: [],
  automationCoverage: {
    automated: 0,
    toBeAutomated: 0,
    manual: 0,
    notAutomatable: 0,
    coveragePercent: 0,
  },
  recentActivity: [],
}

function renderDashboard(dashboard: RepositoryDashboard = DASHBOARD) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['project:read'])
    if (path.includes('/dashboard')) return dashboard
    // The overview's delivery half. Anchored arms, and the issue list answers one epic-less row so
    // the page renders its tables rather than three empty states — this file is about the tiles.
    if (path.startsWith('/issues?')) return path.includes('level=EPIC') ? [] : [anIssueSummary()]
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) return aProjectWorkflow({ projectId: 'p1' })
    // The release panel 013 T6 added below the delivery half. Empty, because this file is about
    // the tiles — but answered rather than left to fail, since a failed read renders an error
    // branch and an assertion pointed elsewhere would never see it.
    if (/^\/projects\/[^/]+\/releases$/.test(path)) return []
    // The Budget burn panel 019 T6 added to the same column. A project with no budget, so the
    // panel does not render at all — answered rather than left to fail, for the reason above.
    if (/^\/projects\/[^/]+\/budget-burn$/.test(path)) return aBudgetBurn()
    if (/^\/projects\/[^/]+$/.test(path)) return PROJECT
    throw new Error(`unmocked read: ${path}`)
  }) as never)

  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route="/projects/p1">
      <Routes>
        <Route path="/projects/:projectId" element={<ProjectOverviewPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Dashboard tiles and segments are named from this app’s own catalogue', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('labels a health tile with the words its own drill-down will show', async () => {
    renderDashboard()

    const tile = await screen.findByTestId('Dashboard.Health.Tile.OUTDATED_SPEC_CHANGED')

    // The assertion that matters: the tile reads whatever `healthLabels` says, which is what the
    // grid chip on every case behind this count reads. Naming the entry rather than the string is
    // what makes the two impossible to separate.
    expect(tile).toHaveTextContent(healthLabels.OUTDATED_SPEC_CHANGED)
    // And the server's old wording is gone. This is the defect, spelled out: a reader who clicked
    // "Outdated — spec changed" arrived at a list of cases labelled something else.
    expect(screen.queryByText(/Outdated — spec changed/)).not.toBeInTheDocument()
  })

  it('labels a lifecycle tile from the same map the case grid and the filter bar use', async () => {
    renderDashboard()

    expect(await screen.findByTestId('Dashboard.Lifecycle.Tile.IN_REVIEW')).toHaveTextContent(
      lifecycleLabels.IN_REVIEW,
    )
  })

  it('labels and colours a breakdown segment from its key, while the bar keeps the server’s words', async () => {
    renderDashboard()

    const segment = await screen.findByTestId('Dashboard.ByFeature.Segment.DRAFT')

    expect(segment).toHaveAttribute(
      'aria-label',
      `Payments › Refunds, ${lifecycleLabels.DRAFT}: 9 cases`,
    )
    expect(segment).toHaveStyle({ backgroundColor: 'var(--color-draft)' })
    // The bar's own label is data — a classification path only the server can resolve — so it is
    // rendered exactly as it arrives. That is the line this slice draws, and both sides of it are
    // on this one element's aria-label.
    expect(screen.getByText('Payments › Refunds')).toBeInTheDocument()
  })

  it('renders a tile whose key it cannot name, under the key, still drillable', async () => {
    // The property the server's `label` was defended for, kept without it. A signal added to the
    // API before this build knows about it degrades to a legible bug report rather than to a blank
    // tile — and `filter` is opaque, so the link works regardless.
    renderDashboard({
      ...DASHBOARD,
      health: [
        {
          key: 'SIGNAL_FROM_THE_FUTURE',
          count: 3,
          severity: 'ERROR',
          filter: { healthSignal: ['SIGNAL_FROM_THE_FUTURE'] },
        },
      ],
    })

    const tile = await screen.findByTestId('Dashboard.Health.Tile.SIGNAL_FROM_THE_FUTURE')

    expect(tile).toHaveTextContent('SIGNAL_FROM_THE_FUTURE')
    expect(tile).toHaveAttribute(
      'href',
      '/projects/p1/cases?healthSignal=SIGNAL_FROM_THE_FUTURE',
    )
  })
})

describe('every multi-colour breakdown carries a key', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('gives By feature and By owner the legend By last run already had', async () => {
    /*
      Renders audit 6.2. `By last run` passed `legend`; the two four-colour lifecycle stacks beside
      it passed none, so a reader saw amber, green and periwinkle with nothing naming them. Each
      segment does carry an `aria-label` and a `title` — and a hover-only key is not a legend: it
      does not exist for a touch reader at all, and rule 8's "a count is a link to its rows"
      assumes the reader knows which count they are about to follow.
    */
    renderDashboard()

    // ByFeature alone: this fixture's `byOwner` is empty, and a legend under an empty-state
    // sentence would be a key to nothing. The prop is passed to both; what is asserted is that a
    // figure WITH bars carries it.
    for (const figure of ['Dashboard.ByFeature']) {
      const panel = await screen.findByTestId(figure)
      for (const status of ['DRAFT', 'IN_REVIEW', 'APPROVED', 'DEPRECATED'] as const) {
        expect(within(panel).getAllByText(lifecycleLabels[status]).length).toBeGreaterThan(0)
      }
    }
  })
})
