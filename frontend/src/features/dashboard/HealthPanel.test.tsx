import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { RepositoryDashboard } from '../../api/types'
import { severityVar } from '../../lib/statusColors'
import { HealthPanel } from './DashboardPage'

/**
 * "Needs attention" has three states and only one of them was reachable through the fixture.
 *
 * An empty tile list is a **claim that nothing is wrong**, and it is only true if somebody
 * looked. `healthComputedAt: null` is what separates the two, and both branches are asserted here
 * because the fixture can never show them (§46: no test written before a state is reachable
 * can fail on it).
 *
 * ~~`case_health` has no writer, so on a live server nobody has.~~ → **superseded 2026-08-09
 * (§156): it has three, and the hourly recompute is on by default.** The branch this file called
 * unreachable is the one every deployment lands in, and it said "Nothing needs attention" while
 * five of the ten signals had no computation behind them at all. The copy is narrower now, and
 * the last test here is what holds it there.
 */

const dashboard = (partial: Partial<RepositoryDashboard>): RepositoryDashboard =>
  ({
    generatedAt: '2026-08-01T09:00:00Z',
    lifecycle: [],
    health: [],
    healthComputedAt: null,
    byFeature: [],
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
    ...partial,
  })

function renderPanel(data: RepositoryDashboard) {
  return render(
      <MemoryRouter>
        <HealthPanel data={data} casesPath="/projects/p/cases" />
      </MemoryRouter>,
  )
}

describe('HealthPanel — the difference between clean and unchecked', () => {
  it('says nothing has been checked when health has never been computed', () => {
    // The live state of every deployment today. The wording has to make the emptiness mean
    // "unknown", because the reader's default reading of an empty panel is "fine".
    renderPanel(dashboard({ health: [], healthComputedAt: null }))

    expect(screen.getByTestId('Dashboard.Health.NotComputed')).toBeInTheDocument()
    expect(screen.queryByTestId('Dashboard.Health.Clear')).not.toBeInTheDocument()
  })

  it('says nothing needs attention only when something actually checked', () => {
    renderPanel(dashboard({ health: [], healthComputedAt: '2026-08-01T08:00:00Z' }))

    expect(screen.getByTestId('Dashboard.Health.Clear')).toBeInTheDocument()
    expect(screen.queryByTestId('Dashboard.Health.NotComputed')).not.toBeInTheDocument()
  })

  it('renders the tiles in the order the server sent them, and does not rank them again', () => {
    // §184, and this test used to assert the opposite. It passed a WARNING ahead of an ERROR and
    // checked the panel swapped them — so it was pinning `severityRank`, the panel's own copy of a
    // ranking the server already applies, and it would have stayed green against a server that
    // returned its tiles in enum order. It was also the only assertion anywhere that a tile list
    // is ordered at all: `DashboardEndpointTest`'s fixture writes no `case_health` row, so the
    // server's own sort had no observer until `HealthTileOrderTest` arrived with this deletion.
    //
    // §179 took this decision at the case grid — the ranking is a property of the answer, a
    // client-side sort is the second copy of it — and this is the other screen that shows a health
    // severity. The fixture below is therefore in an order the server CANNOT produce (a WARNING
    // ahead of an ERROR), because that is the only input an unsorted panel and a sorted one
    // disagree about. Read it as "the panel is transparent", not as "the panel is wrong".
    renderPanel(
      dashboard({
        healthComputedAt: '2026-08-01T08:00:00Z',
        health: [
          {
            key: 'STALE_APPROVAL',
            count: 31,
            severity: 'WARNING',
            filter: { healthSignal: ['STALE_APPROVAL'] },
          },
          {
            key: 'OUTDATED_SPEC_CHANGED',
            count: 78,
            severity: 'ERROR',
            filter: { healthSignal: ['OUTDATED_SPEC_CHANGED'] },
          },
        ],
      }),
    )

    const tiles = screen.getAllByTestId(/^Dashboard\.Health\.Tile\./)
    expect(tiles.map((tile) => tile.getAttribute('data-testid'))).toEqual([
      'Dashboard.Health.Tile.STALE_APPROVAL',
      'Dashboard.Health.Tile.OUTDATED_SPEC_CHANGED',
    ])
  })

  it('every tile links to the cases behind its own count', () => {
    // §6o's rule: a count that cannot be drilled into is decoration. The filter travels as query
    // parameters, so a tile carrying a key the case list does not bind would still render a
    // perfectly plausible link to a wider set.
    renderPanel(
      dashboard({
        healthComputedAt: '2026-08-01T08:00:00Z',
        health: [
          {
            key: 'UNLINKED',
            count: 156,
            severity: 'INFO',
            filter: { healthSignal: ['UNLINKED'] },
          },
        ],
      }),
    )

    expect(screen.getByTestId('Dashboard.Health.Tile.UNLINKED')).toHaveAttribute(
      'href',
      '/projects/p/cases?healthSignal=UNLINKED',
    )
  })

  it('takes a tile border from the same table the case chip takes its colour from', () => {
    // §183. There were two four-key severity→colour maps — this panel's private `severityColor`
    // and `StatusChip.tsx`'s — agreeing on three values and disagreeing on INFO: blue here, amber
    // on the chip. One severity, two colours, so the same signal read as information on the
    // dashboard and as a warning in the grid one navigation later.
    //
    // Nothing could see it. No test in either suite had ever asserted a colour on a tile, and the
    // maps were in different directories with different names. That is `SEVERE_SIGNALS` (§179) and
    // `DashboardBreakdown.color` (§180) a third time, which is why the assertion is against the
    // shared table rather than against a literal — a literal here would be the fourth copy.
    renderPanel(
      dashboard({
        healthComputedAt: '2026-08-01T08:00:00Z',
        health: [
          { key: 'UNLINKED', count: 156, severity: 'INFO', filter: { healthSignal: ['UNLINKED'] } },
        ],
      }),
    )

    const tile = screen.getByTestId('Dashboard.Health.Tile.UNLINKED').firstElementChild!

    expect(getComputedStyle(tile).borderLeftColor).toBe(severityVar.INFO)
  })

  it('claims only that no signal was raised, never that nothing is wrong', () => {
    // §156. Five of the ten signals have no computation behind them, and the server has no way to
    // say which — so an empty tile list means "none of the checks that run raised anything", and
    // the panel must not upgrade that into an all-clear. The assertion is on the WORDS rather than
    // the testid because the testid is unchanged and the defect was entirely in the sentence.
    renderPanel(dashboard({ health: [], healthComputedAt: '2026-08-01T08:00:00Z' }))

    const clear = screen.getByTestId('Dashboard.Health.Clear')
    expect(clear).toHaveTextContent(/no health signal was raised/i)
    expect(clear).not.toHaveTextContent(/nothing needs attention/i)
  })
})
