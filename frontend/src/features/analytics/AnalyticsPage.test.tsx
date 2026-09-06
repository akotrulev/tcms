import { render, screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey } from '../../api/types'
import {
  aBurnUpReport,
  aCumulativeFlowReport,
  aCycleTimeReport,
  aReleaseSummary,
  aReportSufficiency,
} from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { AnalyticsPage } from './AnalyticsPage'

/**
 * `reports.html` — and the four claims that are the module's own definition of done.
 *
 * 1. **The burn-up distinguishes scope added after the release was created** — the chip is present
 *    when there is late scope and absent when there is none, because `+0 added` is a sentence about
 *    nothing.
 * 2. **Cycle time is per issue type**, one row each, and the panel says it measured from *created*
 *    rather than from a start this product does not record (020 D-c).
 * 3. **A too-little-data report renders its sentence and no figure.** Not an empty axis, not a flat
 *    line at zero — those read as *the team has done nothing* where the truth is *there is not
 *    enough here to say*.
 * 4. **The flow has four bands**, the fourth being the absence of a record rather than a status.
 *
 * The read arms are anchored on the whole segment: `/analytics/burn-up`, `/analytics/cycle-time`
 * and `/analytics/flow` answer three unrelated shapes, so a `startsWith` would serve a flow where a
 * burn-up was expected — the drifted-body failure, injected by the test double.
 */

// A whole `ReleaseSummary`, from the factory. A hand-written stub is what the contract
// conformance harness exists to refuse — a body the server could not send is a component
// one commit away from a TypeError.
const RELEASE = aReleaseSummary({ id: 'rel-1', version: '2.4' })

function mockReads(
  over: {
    burnUp?: ReturnType<typeof aBurnUpReport>
    cycleTime?: ReturnType<typeof aCycleTimeReport>
    flow?: ReturnType<typeof aCumulativeFlowReport>
  } = {},
  permissions: PermissionKey[] = ['report:view', 'issue:read'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (/^\/projects\/[^/]+\/releases(\?|$)/.test(path)) return [RELEASE]
    if (/^\/projects\/[^/]+\/analytics\/burn-up(\?|$)/.test(path)) {
      return over.burnUp ?? aBurnUpReport()
    }
    if (/^\/projects\/[^/]+\/analytics\/cycle-time(\?|$)/.test(path)) {
      return over.cycleTime ?? aCycleTimeReport()
    }
    if (/^\/projects\/[^/]+\/analytics\/flow(\?|$)/.test(path)) {
      return over.flow ?? aCumulativeFlowReport()
    }
    // The estimate-accuracy panel's two reads. Empty is a real state — a project with no epics —
    // and every assertion below is about the three figures beside it.
    if (path.startsWith('/issues')) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/projects/p1/analytics">
      <Routes>
        <Route path="/projects/:projectId/analytics" element={<AnalyticsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Delivery analytics', () => {
  it('names the scope that arrived after the release was created', async () => {
    mockReads()
    renderPage()

    const chip = await screen.findByTestId('burn-up-scope-added')
    expect(chip).toHaveTextContent('4')
    expect(chip).toHaveTextContent('added after the release was created')
  })

  it('says nothing about late scope when none arrived — never "+0 added"', async () => {
    mockReads({ burnUp: aBurnUpReport({ scopeAddedAfterCreation: 0 }) })
    renderPage()

    await screen.findByTestId('burn-up-figure')
    expect(screen.queryByTestId('burn-up-scope-added')).not.toBeInTheDocument()
  })

  it('reports cycle time per issue type, with each band’s population on the row', async () => {
    mockReads()
    renderPage()

    const figure = await screen.findByTestId('cycle-time-figure')
    for (const type of ['Task', 'Story', 'Bug', 'Spike']) {
      expect(within(figure).getByTestId(`cycle-time-${type}`)).toHaveTextContent(type)
    }
    expect(within(figure).getByTestId('cycle-time-Story')).toHaveTextContent('n=18')
  })

  it('says it measured from created, because nothing records when work started', async () => {
    mockReads()
    renderPage()

    await screen.findByTestId('cycle-time-figure')
    expect(screen.getByText(/created → done, by type/i)).toBeInTheDocument()
  })

  it('renders the server’s sentence and NO figure when there is too little data', async () => {
    const reason = 'No story-level issue is targeting this release yet.'
    mockReads({
      burnUp: aBurnUpReport({
        points: [],
        sufficiency: aReportSufficiency({ sufficient: false, reason, issuesConsidered: 0 }),
      }),
    })
    renderPage()

    expect(await screen.findByText(reason)).toBeInTheDocument()
    // The criterion is the absence, not the sentence: an axis with nothing on it is the flat line
    // this design refuses.
    expect(screen.queryByTestId('burn-up-figure')).not.toBeInTheDocument()
  })

  it('draws the flow as four bands, the fourth being the absence of a record', async () => {
    mockReads()
    renderPage()

    const flow = await screen.findByTestId('flow-figure')
    expect(within(flow).getByText('Not started')).toBeInTheDocument()
    expect(within(flow).getByText('In flight')).toBeInTheDocument()
    expect(within(flow).getByText('Done')).toBeInTheDocument()
    expect(within(flow).getByText('Before the current status')).toBeInTheDocument()
    expect(within(flow).getByTestId('flow-facet-beforeCurrentStatus')).toBeInTheDocument()
  })

  it('explains the fourth band on the screen rather than leaving it to be inferred', async () => {
    mockReads()
    renderPage()

    await screen.findByTestId('flow-figure')
    expect(
      screen.getByText(/status on that date is recorded nowhere/i),
    ).toBeInTheDocument()
  })

  it('says there is no velocity chart, where a reader looking for one would be', async () => {
    mockReads()
    renderPage()

    await screen.findByTestId('cycle-time-figure')
    expect(screen.getByText(/no velocity chart/i)).toBeInTheDocument()
  })
})
