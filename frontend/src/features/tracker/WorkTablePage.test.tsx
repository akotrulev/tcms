import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { drivingAFailedRead } from '../../test/renderFailures'
import {
  aBoardConfig,
  aBoardSummary,
  aProject,
  aProjectWorkflow,
  aStatusRef,
  anIssueSummary,
  anIssueType,
  boardContents,
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

function mockReads(issues = [anIssueSummary({ key: 'ACME-118', title: 'Guest checkout' })]) {
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
      The board view's four reads (012 T7). They are here rather than in a second helper because the
      switcher renders one of two views under one header, so a test that switches views crosses both
      sets — and a double that answered only the table's would report the board as a failed region.

      `/boards/{id}` and `/boards/{id}/cards` are one segment apart and both anchored: the harness
      names that pair as exactly the prefix collision to avoid.
    */
    if (/^\/projects\/[^/]+\/boards$/.test(path)) return [aBoardSummary({ id: 'b1' })]
    if (/^\/projects\/[^/]+\/labels$/.test(path)) return []
    // The backlog view's own read (012 T8). Anchored for the same reason as the pair above.
    if (/^\/projects\/[^/]+\/backlog/.test(path)) return []
    if (/^\/boards\/[^/]+$/.test(path)) return aBoardConfig({ id: 'b1' })
    if (/^\/boards\/[^/]+\/cards/.test(path)) return boardContents()
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

  it('names the module a view it does not build arrives in, rather than hiding the tab', async () => {
    // A tab that vanishes and reappears between releases teaches that the product is unstable;
    // one that says when it arrives teaches the roadmap. The tab is in the switcher because D34
    // makes Work one entry.
    mockReads()
    renderPage('/projects/p1/work?view=timeline')

    const panel = await screen.findByTestId('WorkTable.NotYet.timeline')
    expect(panel).toHaveTextContent('M3')
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
    expect(screen.queryByTestId('WorkTable.Columns.logged')).toBeNull()
    expect(screen.queryByTestId('WorkTable.Columns.labels')).toBeNull()
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
