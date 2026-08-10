import { ThemeProvider } from '@mui/material/styles'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { RepositoryDashboard } from '../../api/types'
import { theme } from '../../app/theme'
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
    automationCoverage: {
      automated: 0,
      toBeAutomated: 0,
      manual: 0,
      notAutomatable: 0,
      coveragePercent: 0,
    },
    recentActivity: [],
    ...partial,
  }) as RepositoryDashboard

function renderPanel(data: RepositoryDashboard) {
  return render(
    <ThemeProvider theme={theme}>
      <MemoryRouter>
        <HealthPanel data={data} casesPath="/projects/p/cases" />
      </MemoryRouter>
    </ThemeProvider>,
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

  it('renders the tiles when signals were raised, worst first', () => {
    // The server already sorts by severity; the panel sorts again so the order is a property of
    // the screen rather than of one server version. WARNING is passed in first on purpose — a
    // panel that rendered the response's order would put it above the ERROR.
    renderPanel(
      dashboard({
        healthComputedAt: '2026-08-01T08:00:00Z',
        health: [
          {
            key: 'STALE_APPROVAL',
            label: 'Stale approval',
            count: 31,
            severity: 'WARNING',
            filter: { healthSignal: ['STALE_APPROVAL'] },
          },
          {
            key: 'OUTDATED_SPEC_CHANGED',
            label: 'Outdated — spec changed',
            count: 78,
            severity: 'ERROR',
            filter: { healthSignal: ['OUTDATED_SPEC_CHANGED'] },
          },
        ],
      }),
    )

    const tiles = screen.getAllByTestId(/^Dashboard\.Health\.Tile\./)
    expect(tiles.map((tile) => tile.getAttribute('data-testid'))).toEqual([
      'Dashboard.Health.Tile.OUTDATED_SPEC_CHANGED',
      'Dashboard.Health.Tile.STALE_APPROVAL',
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
            label: 'Unlinked',
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
