import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { IssueSummary, ReleaseSummary } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import {
  aProjectWorkflow,
  aReleaseSummary,
  anIssueSummary,
  aStatusRef,
} from '../../test/fixtures'
import { CalendarView } from './CalendarView'

/**
 * `work-calendar.html`, and its two requirements.
 *
 * 1. **Two of the mockup's five event kinds are drawn, and the footer names the other three** (D-n).
 *    "Coming soon" names nothing a reader can plan around, so the footer says which module each
 *    absent kind arrives with — and says out loud that test runs are *not* coming, because a run has
 *    three candidate dates and this module does not choose between them.
 * 2. **`overdue` is the server's flag, never a client comparison.** A screen that recomputed it from
 *    `dueDate < today` would disagree with the work table the moment the two disagreed about a
 *    timezone.
 */

const PROJECT = 'p1'
/** Pinned so the grid is the same month every run. `?month=` is in the URL, not in state. */
const MONTH = '2026-09'

function mockReads(issues: IssueSummary[], releases: ReleaseSummary[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read', 'release:read'], {}, PROJECT)
    if (path.startsWith('/issues')) return issues
    if (/^\/projects\/[^/]+\/releases$/.test(path)) return releases
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) return aProjectWorkflow({ projectId: PROJECT })
    if (/^\/projects\/[^/]+\/members$/.test(path)) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderView(search = `?view=calendar&month=${MONTH}`) {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/work${search}`}>
      <Routes>
        <Route path="/projects/:projectId/work" element={<CalendarView projectId={PROJECT} />} />
      </Routes>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the month grid', () => {
  it('draws due dates and release targets on their own days', async () => {
    mockReads(
      [
        anIssueSummary({ key: 'ACME-118', title: 'Guest checkout', dueDate: '2026-09-04' }),
        anIssueSummary({
          key: 'ACME-131',
          title: 'Saved card list',
          dueDate: '2026-08-31',
          overdue: true,
          status: aStatusRef({ name: 'Ready', kind: 'NOT_STARTED' }),
        }),
      ],
      [aReleaseSummary({ id: 'r-24', version: '2.4', targetDate: '2026-09-18' })],
    )
    renderView()

    await screen.findByTestId('Calendar.Grid')

    expect(screen.getByTestId('Calendar.Month')).toHaveTextContent('September 2026')
    expect(screen.getByTestId('Calendar.Due.ACME-118')).toHaveTextContent('ACME-118 due')
    expect(screen.getByTestId('Calendar.Target.2.4')).toHaveTextContent('2.4 target')

    // The event lands in the cell for its own date, not the first cell of the month.
    expect(
      within(screen.getByTestId('Calendar.Day.2026-09-04')).getByTestId('Calendar.Due.ACME-118'),
    ).toBeInTheDocument()
  })

  it('says `overdue` from the server’s flag, and draws the trailing days of the previous month', async () => {
    mockReads(
      [
        anIssueSummary({ key: 'ACME-131', dueDate: '2026-08-31', overdue: true }),
      ],
      [],
    )
    renderView()

    await screen.findByTestId('Calendar.Grid')

    // Aug 31 2026 is a Monday, so it is the first cell of a Monday-first September grid — which is
    // also what proves the leading days of the neighbouring month are drawn at all.
    const cell = screen.getByTestId('Calendar.Day.2026-08-31')
    expect(within(cell).getByTestId('Calendar.Due.ACME-131')).toHaveTextContent('ACME-131 overdue')
  })

  it('always draws 42 cells, so the grid does not change height between months', async () => {
    mockReads([], [])
    renderView()

    const grid = await screen.findByTestId('Calendar.Grid')
    // Seven day-of-week heads plus six weeks of cells. A five-row month followed by a six-row one
    // would move everything below it.
    expect(within(grid).getAllByTestId(/^Calendar\.Day\./)).toHaveLength(42)
  })
})

describe('the month is in the URL and is not a filter', () => {
  it('steps forward and back without touching the filters', async () => {
    mockReads([], [])
    const user = userEvent.setup()
    renderView(`?view=calendar&month=${MONTH}&labelId=lab1`)

    await screen.findByTestId('Calendar.Grid')
    await user.click(screen.getByTestId('Calendar.Next'))
    expect(screen.getByTestId('Calendar.Month')).toHaveTextContent('October 2026')

    await user.click(screen.getByTestId('Calendar.Previous'))
    await user.click(screen.getByTestId('Calendar.Previous'))
    expect(screen.getByTestId('Calendar.Month')).toHaveTextContent('August 2026')
  })
})

describe('what is not drawn', () => {
  it('names the three absent kinds and the module each arrives with', async () => {
    mockReads([], [])
    renderView()

    await screen.findByTestId('Calendar.Grid')

    expect(screen.getByText(/Test runs are not/)).toBeInTheDocument()
    expect(screen.getByText(/absence module \(M7\)/)).toBeInTheDocument()
    expect(screen.getByText(/time tracking\s*\(M6\)/)).toBeInTheDocument()
  })
})

describe('a failed read', () => {
  it('keeps the month controls and the filter strip above the failed region', async () => {
    const { drivingAFailedRead } = await import('../../test/renderFailures')
    // The subject: `GET /issues` answers 500 while the releases load.
    drivingAFailedRead()

    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read'], {}, PROJECT)
      if (path.startsWith('/issues')) throw new Error('issues are down')
      if (/^\/projects\/[^/]+\/releases$/.test(path)) return []
      if (/^\/projects\/[^/]+\/workflow$/.test(path)) return aProjectWorkflow({ projectId: PROJECT })
      if (/^\/projects\/[^/]+\/members$/.test(path)) return []
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    renderView()

    await screen.findByTestId('Calendar.Content.Error')
    // `DataRegion`'s scoping rule: a failed read must not unmount the controls a reader would use
    // to change the query or move off the month.
    expect(screen.getByTestId('Calendar.Next')).toBeInTheDocument()
    expect(screen.getByTestId('Calendar.Filters')).toBeInTheDocument()
  })
})
