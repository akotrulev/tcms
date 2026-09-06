import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { BoardColumnConfig, BoardIssueCard, BoardSummary } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { drivingAFailedRead } from '../../test/renderFailures'
import {
  aBoardConfig,
  aBoardCoverage,
  aBoardIssueCard,
  aBoardLane,
  aBoardSummary,
  aProject,
  aProjectWorkflow,
  aRoadmap,
  aStatusRef,
  anIssueSummary,
  anIssueType,
  aTimeRollup,
  boardContents,
  projectSettings,
} from '../../test/fixtures'
import { WorkTablePage } from './WorkTablePage'

/**
 * `work-table.html`, and the four failures a static render cannot show.
 *
 * 1. **A failed read must not look like an empty project.** An empty table and a failed one are
 *    the same picture, and only one of them is recoverable.
 * 2. **An empty project must offer the action that resolves it.** The mockups' own rule.
 * 3. **The filter survives a view switch** (D34) — a URL assertion, not a component one, because
 *    the filter lives in the query string precisely so that it can. Since 012 T8 that crosses two
 *    parameter *sets*: a `labelId` set on the Board reaches the Table's address untouched even
 *    though `GET /issues` has no such parameter (D-v).
 * 4. **Columns M1 has no data for are ABSENT from the picker** (D-f). A greyed Release column
 *    would teach that this issue has no release; the product cannot hold one at all.
 */

const READY = aStatusRef({ id: 'st-ready', name: 'Ready', kind: 'NOT_STARTED', position: 0 })
const REVIEW = aStatusRef({ id: 'st-review', name: 'In Review', kind: 'IN_PROGRESS', position: 1 })

const STORY = anIssueType({ id: 'ty-story', name: 'Story', level: 'STORY' })
const SPIKE = anIssueType({
  id: 'ty-spike',
  name: 'Spike',
  level: 'STORY',
  countsTowardCompletion: false,
})

/** Prints the current address, so a test can assert what a view switch did to the query string. */
function Address() {
  const location = useLocation()
  return <span data-testid="Address">{`${location.pathname}${location.search}`}</span>
}

function renderPage(route = '/projects/p1/work') {
  return render(
    <TestProviders client={createTestQueryClient()} route={route}>
      <Routes>
        <Route
          path="/projects/:projectId/work"
          element={
            <>
              <WorkTablePage />
              <Address />
            </>
          }
        />
      </Routes>
    </TestProviders>,
  )
}

/**
 * The board the double serves, so a test about the **strip** can name several without a second
 * copy of every other arm — which is what the first draft of the door-badge test did, and it drifted
 * from this one before it was even run.
 */
interface BoardDouble {
  summaries?: BoardSummary[]
  columns?: BoardColumnConfig[]
  cards?: BoardIssueCard[]
}

function mockReads(
  issues = [anIssueSummary({ key: 'ACME-118', title: 'Guest checkout' })],
  time: { settings?: unknown; rollups?: unknown; failRollups?: boolean } = {},
  board: BoardDouble = {},
) {
  const paths: string[] = []
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    paths.push(path)
    if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read', 'issue:create'])
    // Anchored, so `/projects/p1/workflow` is not caught by the project arm's prefix.
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) {
      return aProjectWorkflow({ statuses: [READY, REVIEW], types: [STORY, SPIKE] })
    }
    if (/^\/projects\/[^/]+\/members$/.test(path)) return []
    /*
      016 T5's two reads. `settings` gates the `Logged` column on D-g, and `/time/rollup/issues` is
      the column itself — one request for the rows on screen, because `IssueSummary` carries an
      estimate and no hours. Both anchored ahead of the project arm for the collision reason above.
    */
    if (/^\/projects\/[^/]+\/settings$/.test(path)) {
      return time.settings ?? projectSettings({ projectId: 'p1' })
    }
    if (path.startsWith('/time/rollup/issues')) {
      if (time.failRollups) throw new Error('the rollup read failed')
      return time.rollups ?? []
    }
    /*
      The board view's four reads (012 T7). They are here rather than in a second helper because the
      switcher renders one of two views under one header, so a test that switches views crosses both
      sets — and a double that answered only the table's would report the board as a failed region.

      `/boards/{id}` and `/boards/{id}/cards` are one segment apart and both anchored: the harness
      names that pair as exactly the prefix collision to avoid.
    */
    if (/^\/projects\/[^/]+\/boards\/coverage$/.test(path)) return aBoardCoverage()
    if (/^\/projects\/[^/]+\/boards$/.test(path)) {
      return board.summaries ?? [aBoardSummary({ id: 'b1', name: 'Team board' })]
    }
    if (/^\/projects\/[^/]+\/labels$/.test(path)) return []
    // The backlog view's own read (012 T8). Anchored for the same reason as the pair above.
    if (/^\/projects\/[^/]+\/backlog/.test(path)) return []
    /*
      The timeline's and the calendar's reads (013 T5). Both views are real now, so a double that
      answered only the first three would report the fifth view as a failed region — which is what
      `renderFailures` caught the moment the two placeholder panels were replaced.
    */
    if (/^\/projects\/[^/]+\/roadmap$/.test(path)) return aRoadmap()
    if (/^\/projects\/[^/]+\/releases$/.test(path)) return []
    if (/^\/boards\/[^/]+$/.test(path)) {
      return aBoardConfig(board.columns ? { id: 'b1', columns: board.columns } : { id: 'b1' })
    }
    if (/^\/boards\/[^/]+\/cards/.test(path)) {
      return board.cards
        ? boardContents({ lanes: [aBoardLane({ cards: board.cards })] })
        : boardContents()
    }
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: 'p1', name: 'Acme Storefront' })
    if (path.startsWith('/issues')) return issues
    throw new Error(`unmocked read: ${path}`)
  }) as never)
  return paths
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('The table', () => {
  it('renders a row per issue, keyed and linked to the issue’s own page', async () => {
    mockReads([
      anIssueSummary({ key: 'ACME-118', title: 'Guest checkout', status: REVIEW, type: STORY }),
    ])
    renderPage()

    const row = await screen.findByTestId('WorkTable.Row')
    expect(within(row).getByText('ACME-118')).toBeTruthy()
    // No project segment: a key is unique in the only scope RLS lets a caller see, and it is what
    // a person types into a commit message.
    expect(within(row).getByTestId('WorkTable.Row.Title')).toHaveAttribute('href', '/issues/ACME-118')
  })

  it('shows the project’s own status name rather than a code', async () => {
    // There is no status enum any more (D-c). A screen that printed `IN_PROGRESS` would be
    // inventing a vocabulary the project did not choose.
    mockReads([anIssueSummary({ status: REVIEW })])
    renderPage()

    expect(await screen.findByTestId('WorkTable.Row.Status')).toHaveTextContent('In Review')
  })
})

describe('The states no static render exercises', () => {
  it('renders the failed state through DataRegion rather than an empty table', async () => {
    // The work table must say the read failed — an empty table and a failed read look identical
    // to a reader, and only the second one is recoverable.
    drivingAFailedRead()
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read'])
      if (/^\/projects\/[^/]+\/workflow$/.test(path)) return aProjectWorkflow()
      if (/^\/projects\/[^/]+\/members$/.test(path)) return []
      if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: 'p1' })
      throw new Error('the issue read failed')
    }) as never)

    renderPage()

    expect(await screen.findByTestId('WorkTable.Content.Error')).toBeInTheDocument()
    expect(screen.queryByTestId('WorkTable.Table')).toBeNull()
  })

  it('offers a first action when the project has no issues at all', async () => {
    // An empty state carries the action that resolves it — the mockups' own rule, and the reason
    // `solo-overview.html`'s release panel is the template for this one.
    mockReads([])
    renderPage()

    const empty = await screen.findByTestId('WorkTable.Content.Empty')
    expect(within(empty).getByTestId('WorkTable.Empty.Add')).toHaveAttribute(
      'href',
      '/projects/p1/work/new',
    )
  })
})

describe('The view switcher (D34)', () => {
  it('keeps the filter across a view switch', async () => {
    /*
      D34, and it is a URL assertion rather than a component one: the filter lives in the query
      string precisely so two views can share it, and a filter held in component state would pass
      every rendering assertion and lose the value on the first switch.
    */
    mockReads()
    renderPage('/projects/p1/work?level=EPIC&statusId=st-ready')

    await userEvent.click(await screen.findByTestId('WorkTable.View.timeline'))

    await waitFor(() => {
      const address = screen.getByTestId('Address').textContent ?? ''
      expect(address).toContain('level=EPIC')
      expect(address).toContain('statusId=st-ready')
      expect(address).toContain('view=timeline')
    })
  })

  it('renders the roadmap and the calendar themselves now, rather than a panel deferring them', async () => {
    /*
      ~~A tab that vanishes and reappears between releases teaches that the product is unstable; one
      that says when it arrives teaches the roadmap.~~ → **both arrived** (013 T5), so the two
      `WorkTable.NotYet.*` panels are gone and every one of the five tabs renders its own view. The
      test kept its place in this file because the thing it guards is unchanged: the tab is in the
      switcher because D34 makes Work one entry, and no view may quietly fall back to the table.
    */
    mockReads()
    renderPage('/projects/p1/work?view=timeline')

    expect(await screen.findByTestId('Roadmap.Content.Empty')).toBeInTheDocument()
    expect(screen.queryByTestId('WorkTable.Table')).toBeNull()
    expect(screen.queryByTestId('WorkTable.NotYet.timeline')).toBeNull()

    await userEvent.click(screen.getByTestId('WorkTable.View.calendar'))
    expect(await screen.findByTestId('Calendar.Grid')).toBeInTheDocument()
    expect(screen.queryByTestId('WorkTable.Table')).toBeNull()
  })

  it('carries a filter set on the Board to the Table, in the URL and on the screen', async () => {
    /*
      D34's whole point and the M2 done-criterion — asserted through the address **as well as** the
      screen, because "shareable as a URL" is the half a test that only checked the chips would
      miss. `labelId` is the board's parameter and the Table has no picker for it; `assigneeId` is
      one both endpoints declare, so the Table both applies it and chips it.

      The address is the router's rather than `window.location`'s: `TestProviders` mounts a
      `MemoryRouter`, which never writes the browser's location, so `location.search` is the URL
      under test.
    */
    mockReads()
    renderPage('/projects/p1/work?view=board&labelId=lab-1&assigneeId=u1')

    expect(await screen.findByTestId('ProjectBoard.View')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('WorkTable.View.table'))

    await waitFor(() => expect(screen.getByTestId('WorkTable.Table')).toBeInTheDocument())
    const address = screen.getByTestId('Address').textContent ?? ''
    expect(address).toContain('labelId=lab-1')
    expect(address).toContain('assigneeId=u1')
    expect(screen.getByTestId('WorkTable.Filters.Applied.assignee:u1')).toBeInTheDocument()
  })

  it('chips an epic filter the Board set, which the table applies and cannot pick', async () => {
    // `GET /issues` takes `epicKey`, so this table IS narrowed — and until T8 nothing on screen
    // said so. A filter with no chip is one somebody stares at a short list over.
    mockReads()
    renderPage('/projects/p1/work?epicKey=ACME-1')

    expect(await screen.findByTestId('WorkTable.Filters.Applied.epic:ACME-1')).toHaveTextContent(
      'Epic · ACME-1',
    )
  })

  it('renders the backlog itself now, rather than a panel deferring it', async () => {
    // ~~"Backlog arrives in M2"~~ → it did, in 012 T8. Asserted through the backlog's own root
    // rather than its content: this test is about the switcher's wiring, and the backlog's
    // behaviour has a file of its own.
    mockReads()
    renderPage('/projects/p1/work?view=backlog')

    expect(await screen.findByTestId('Backlog.View')).toBeInTheDocument()
    expect(screen.queryByTestId('WorkTable.NotYet.backlog')).toBeNull()
    expect(screen.queryByTestId('WorkTable.Table')).toBeNull()
  })

  it('renders the board itself now, rather than a panel deferring it', async () => {
    /*
      ~~"leaves the board that exists today reachable from the tab that defers it"~~ → **superseded
      by 012 T7**, which built the board this tab was deferring. The old assertion was that the
      `M2` panel linked out to `/projects/p1/board`, and both halves of it are gone: the panel is
      gone because the view exists, and the link with it because the absence it covered is over.

      `/projects/:id/board` still works and is still a different board (D-a) — the *composite* one,
      over `GET /board`, with four card kinds. It is not this one's predecessor and this tab is no
      longer the way to it.

      Asserted through the board's own root rather than through its content: what this test is about
      is the **switcher's** wiring, and the board's behaviour has a file of its own.
    */
    mockReads()
    renderPage('/projects/p1/work?view=board')

    expect(await screen.findByTestId('ProjectBoard.View')).toBeInTheDocument()
    expect(screen.queryByTestId('WorkTable.NotYet.board')).toBeNull()
    expect(screen.queryByTestId('WorkTable.Table')).toBeNull()
  })

  it('lists the project’s boards as segments, in position order, and no fixed “Board” tab', async () => {
    /*
      021 D-f. The strip is **the project's boards** and then the four fixed views: a project with
      three boards gets three segments, and nothing here spells `PM`, `Dev` or `QA` — those are the
      names §2.4's seed happens to use. `?view=board` with no `board` means the first, which is what
      makes `boards.data[0]` and the board below agree without either naming the other.

      The order asserted is the SERVER's: `listBoards` orders by the `position` V13 added, and
      alphabetical — which is what it did before — would have put Dev before PM.
    */
    mockReads(undefined, {}, {
      summaries: [
        aBoardSummary({ id: 'b1', name: 'PM board' }),
        aBoardSummary({ id: 'b2', name: 'Dev board' }),
      ],
    })
    renderPage('/projects/p1/work?view=board')

    await screen.findByTestId('WorkTable.View.Board.PM board')
    const strip = screen.getByRole('navigation', { name: 'Work views' })
    expect(
      within(strip).getAllByRole('link').map((link) => link.textContent),
    ).toEqual(['PM board', 'Dev board', 'Backlog', 'Table', 'Timeline', 'Calendar'])
    expect(screen.getByTestId('WorkTable.View.Board.PM board')).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('badges the active board with what is waiting in its FIRST column, never a total', async () => {
    /*
      §8.3's door badge. The `4` on screen is not the board's card count — the second column holds
      one as well, and a segment reading `5` would be a number nobody can act on. The ACTIVE segment
      only: 021 T1 added no contract for the inactive boards' counts, deliberately, and T8 badges
      the rest.
    */
    mockReads(undefined, {}, {
      summaries: [aBoardSummary({ id: 'b1', name: 'Dev board' })],
      columns: [
        { id: 'c1', name: 'Ready', position: 0, wipLimit: null, statuses: [READY], defaultStatusId: READY.id },
        { id: 'c2', name: 'Doing', position: 1, wipLimit: null, statuses: [REVIEW], defaultStatusId: REVIEW.id },
      ],
      cards: [
        aBoardIssueCard({ key: 'A-1', columnId: 'c1', status: READY }),
        aBoardIssueCard({ key: 'A-2', columnId: 'c1', status: READY }),
        aBoardIssueCard({ key: 'A-3', columnId: 'c2', status: REVIEW }),
      ],
    })
    renderPage('/projects/p1/work?view=board')

    expect(
      await screen.findByTestId('WorkTable.View.Board.Dev board.Badge'),
    ).toHaveTextContent('2')
  })
})

describe('The column picker (D-f)', () => {
  it('offers only the columns this build has data for', async () => {
    /*
      Release, Logged and labels belong to M3, M6 and M2, and they are **absent rather than
      greyed** — the opposite of this product's usual disable-don't-hide rule, because of what the
      absence would say. A greyed permission teaches "this exists and you may ask for it"; an
      empty Release column teaches "this issue has no release", which is a statement about the
      data in a product that cannot hold releases at all.
    */
    mockReads()
    renderPage()

    await userEvent.click(await screen.findByTestId('WorkTable.Columns'))

    expect(await screen.findByTestId('WorkTable.Columns.status')).toBeInTheDocument()
    expect(screen.queryByTestId('WorkTable.Columns.release')).toBeNull()
    expect(screen.queryByTestId('WorkTable.Columns.labels')).toBeNull()
    // ~~and `logged`~~ → it landed in 016 T5, and it is offered here because this project's
    // fixture tracks time. The case where it is NOT offered is D-g's, asserted with the column
    // itself further down.
    expect(await screen.findByTestId('WorkTable.Columns.logged')).toBeInTheDocument()
  })

  it('ignores a column a later release named, rather than drawing an empty header', async () => {
    // The same rule arriving through a shared link instead of the picker: a `cols=release` from a
    // future build must not produce a header with no renderer behind it.
    mockReads()
    renderPage('/projects/p1/work?cols=summary,release')

    await screen.findByTestId('WorkTable.Table')
    expect(screen.getByTestId('WorkTable.Header.summary')).toBeInTheDocument()
    expect(screen.queryByTestId('WorkTable.Header.release')).toBeNull()
  })
})

/**
 * `work-table.html`'s `Logged` column and its footer (016 T5), and the three things about it that
 * a naive port gets wrong.
 *
 * 1. **A missing rollup row is a dash, never `0h`.** Zero says somebody looked and nobody had
 *    logged an hour; the rollup having no row for a key says nothing of the sort. This is the one
 *    the column would fail silently on, because both spellings render.
 * 2. **The footer says `approved`.** 016 D-d: pending hours are a different number and nothing in
 *    the product sums them, so a footer figure that did not name which it was would be the one
 *    total on the screen nobody could check.
 * 3. **`trackTime` off takes the column and the picker entry away**, rather than drawing a column
 *    of dashes (D-g).
 */
describe('The Logged column (016 M6)', () => {
  it('shows approved hours per row, and a dash where the rollup has no row at all', async () => {
    mockReads(
      [
        anIssueSummary({ key: 'ACME-118', title: 'Guest checkout', estimateHours: 16 }),
        anIssueSummary({ key: 'ACME-131', title: 'Saved card list', estimateHours: 3 }),
      ],
      { rollups: [aTimeRollup({ issueKey: 'ACME-118', estimateHours: 16, approvedHours: 14 })] },
    )
    renderPage()

    const cells = await screen.findAllByTestId('WorkTable.Row.Logged')
    expect(cells).toHaveLength(1)
    await waitFor(() => expect(cells[0]).toHaveTextContent('14h'))
    // ACME-131 has no row: the cell is the em dash the other absent columns use, not `0h`.
    expect(screen.queryByText('0h')).toBeNull()
  })

  it('totals the approved hours in the footer, and says that is what they are', async () => {
    mockReads(
      [
        anIssueSummary({ key: 'ACME-118', title: 'Guest checkout', estimateHours: 16 }),
        anIssueSummary({ key: 'ACME-121', title: 'One-page address', estimateHours: 12 }),
      ],
      {
        rollups: [
          aTimeRollup({ issueKey: 'ACME-118', approvedHours: 14 }),
          aTimeRollup({ issueKey: 'ACME-121', approvedHours: 9 }),
        ],
      },
    )
    renderPage()

    const foot = await screen.findByTestId('WorkTable.Foot')
    expect(foot).toHaveTextContent('Est 28h')
    // Awaited: the estimate total is on the issue list and lands with the rows, the logged total
    // is a second read that lands after them. A bare assertion here reads `Logged 0h approved`
    // and would have passed against a footer that never summed anything.
    await waitFor(() => expect(foot).toHaveTextContent('Logged 23h approved'))
  })

  it('drops the column, its picker entry and the footnote when the project does not track time', async () => {
    mockReads([anIssueSummary({ key: 'ACME-118', title: 'Guest checkout' })], {
      settings: projectSettings({ projectId: 'p1', trackTime: false }),
    })
    renderPage()

    await screen.findByTestId('WorkTable.Table')
    expect(screen.queryByTestId('WorkTable.Header.logged')).toBeNull()
    expect(screen.queryByTestId('WorkTable.Row.Logged')).toBeNull()
    expect(screen.getByTestId('WorkTable.MoneyBoundary')).toHaveTextContent(/Time tracking is off/)
  })

  it('keeps the table when the rollup read fails — an optional column cannot fail the page', async () => {
    drivingAFailedRead() // only the rollup 500s; the issue list answered
    mockReads([anIssueSummary({ key: 'ACME-118', title: 'Guest checkout' })], {
      failRollups: true,
    })
    renderPage()

    expect(await screen.findByTestId('WorkTable.Row.Title')).toHaveTextContent('Guest checkout')
    expect(screen.queryByTestId('WorkTable.Row.Logged')).toBeNull()
  })
})
