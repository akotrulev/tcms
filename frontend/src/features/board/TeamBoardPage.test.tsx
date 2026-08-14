import { render, screen, waitFor, within } from '@testing-library/react'
import { SnackbarProvider } from 'notistack'
import { Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { Team, WorkItem } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { TeamBoardPage } from './TeamBoardPage'
import { drivingAFailedRead } from '../../test/renderFailures'

/**
 * §6l's team board (§84) — the third and last of its boards to get a screen.
 *
 * The failures this file exists to catch. Each typechecks perfectly, and each is invisible on the
 * project board where most of this code was written:
 *
 * 1. **The request is `teamId` and nothing else.** `teamId` with `mine=true` is a **400** — they
 *    select two different boards — so a hook that also sent the personal filter would produce an
 *    error page on a screen that has no filter control at all. A hook that sent a `projectId` would
 *    quietly render one project's slice under a title promising the team's work.
 * 2. **A 404 must read as "no such team", not as an empty board.** The server's 404 is
 *    **empty-bodied** and indistinguishable across its causes on purpose (§6t), so `describeError`
 *    would render "Not found — Not Found": the HTTP status text, which says nothing. This screen
 *    supplies the sentence the server deliberately does not, and it has to stay visibly distinct
 *    from `200 []`, which is a *reachable* board with nothing on it.
 * 3. **A card links into ITS OWN project.** `linkFor` has needed repair in two consecutive slices —
 *    §81 fixed which screen, §83 fixed whose project — and this is the second cross-project board,
 *    so it is the second screen that would expose the same class. Verified here rather than assumed
 *    fixed: all four of §81's link tests passed against §83's defect.
 * 4. **Every card says which project and which person.** Unlike My Work, the reader is looking at
 *    other people's work, so a card that names neither is unplaceable.
 * 5. **No affordance the screen cannot honour**, and no claim the response cannot support: the
 *    cards are bounded by the *reader's* project grants, so nothing here may present a total.
 */

const OLB = '11111111-1111-4111-8111-111111111111'
const MOB = '11111111-1111-4111-8111-111111111112'
const TEAM = '22222222-2222-4222-8222-222222222221'

const card = (partial: Partial<WorkItem> & Pick<WorkItem, 'id' | 'title'>): WorkItem => {
  // Typed on its own line and never `as WorkItem` — §79's repair, which §80 measured: a new
  // required field on the contract becomes one build error here rather than a page of runtime ones.
  const base: WorkItem = {
    id: partial.id,
    title: partial.title,
    derived: false,
    sourceType: null,
    sourceId: null,
    kind: 'TASK',
    description: null,
    status: 'TODO',
    priority: 'MEDIUM',
    dueDate: null,
    overdue: false,
    projectId: OLB,
    projectKey: 'OLB',
    linkedKey: null,
    allowedTransitions: ['IN_PROGRESS', 'IN_REVIEW', 'DONE'],
  }
  return { ...base, ...partial }
}

const CHECKOUT: Team = {
  id: TEAM,
  parentTeamId: null,
  name: 'Checkout Squad',
  description: null,
  leads: [],
  members: [],
  projects: [],
}

/**
 * Records every path requested, so a test can assert what was actually asked of the server.
 *
 * `board` may be a thrown error: the 404 arm is the substantive one on this screen, and it has to
 * be raised the way `api.get` raises it — an `ApiError` with **no detail body**, because that is
 * what the server sends and what makes the default copy useless.
 */
function mockApi(board: WorkItem[] | Error, teams: Team[] | Error = [CHECKOUT], paths: string[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    paths.push(path)
    if (path.startsWith('/teams')) {
      if (teams instanceof Error) throw teams
      return teams
    }
    if (board instanceof Error) throw board
    return board
  }) as unknown as typeof api.get)
  return paths
}

function renderPage() {
  return render(
    /* Through a `<Route>`, never bare — §69's trap: rendered bare, `useParams` gives `teamId: ''`
       and the query asks for a board nobody named, which several assertions here would survive. */
    <TestProviders client={createTestQueryClient()} route={`/teams/${TEAM}/board`}>
      <SnackbarProvider>
        <Routes>
          <Route path="/teams/:teamId/board" element={<TeamBoardPage />} />
        </Routes>
      </SnackbarProvider>
    </TestProviders>,
  )
}

/** The server's 404: a status and nothing else. `ApiError.detail` falls back to the status text. */
const teamNotFound = () => new ApiError(404, 'Not Found', null, null)

describe('What the team board asks the server for', () => {
  it('names the team and nothing else', async () => {
    const paths = mockApi([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')

    // Asserted as the whole query string rather than with `toContain('teamId=')`: a request that
    // also carried `mine=true` would pass a containment check and is a **400** — two boards at
    // once — and one that carried a `projectId` is a different, legal, narrower board.
    expect(paths).toContain(`/work-items?teamId=${TEAM}`)
    expect(paths.some((path) => path.includes('mine='))).toBe(false)
    expect(paths.some((path) => path.includes('projectId='))).toBe(false)
  })

  it('reads the team’s name from the org chart, which every member may read', async () => {
    mockApi([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')
    expect(screen.getByTestId('TeamBoard.Header').textContent).toContain('Checkout Squad')
  })

  it('still renders the board when the org chart fails, because the name is chrome', async () => {
    // The failed chart read is the subject: the board has to render without it.
    drivingAFailedRead()
    // The name comes from a second, independent read. Wiring it through a `DataRegion` — or
    // letting it gate the board — would mean a failed org-chart load blanking a board that
    // loaded perfectly well, which is the failure `DataRegion`'s own scoping rule exists to stop.
    mockApi([card({ id: '1', title: 'Anything' })], new Error('teams down'))
    renderPage()

    await screen.findByTestId('Board.Card')
    expect(screen.getByTestId('TeamBoard.Header').textContent).toContain('Team board')
  })
})

describe('The three answers this screen must keep apart', () => {
  it('reads a 404 as "no such team", with the way back', async () => {
    // The failed read is the subject: this is one of the three answers the screen keeps apart.
    drivingAFailedRead()
    // THE HEADLINE. The server's 404 carries no body, so the shared copy would say
    // "Not found — Not Found". A reader who followed a stale link needs to be told the team is
    // gone and where the list is; both are things the client knows and the response does not.
    mockApi(teamNotFound())
    renderPage()

    const missing = await screen.findByTestId('TeamBoard.Missing')
    expect(missing.textContent).toContain('No such team')
    expect(screen.getByTestId('TeamBoard.Missing.Teams').getAttribute('href')).toBe('/admin/teams')

    // And it is NOT the empty board: a team that does not exist and a team with nothing on it are
    // the two answers this screen exists to keep apart.
    expect(screen.queryByTestId('TeamBoard.Content.Empty')).toBeNull()
  })

  it('reads an empty board as reachable and empty, not as a missing team', async () => {
    // `200 []` is a team with no members OR members with no work this reader can see — deliberately
    // indistinguishable on the wire, so deliberately one sentence here. Rendering it as a failure
    // would tell somebody a team they can see does not exist.
    mockApi([])
    renderPage()

    const empty = await screen.findByTestId('TeamBoard.Content.Empty')
    expect(empty.textContent).toContain('Nothing on this team’s board')
    expect(screen.queryByTestId('TeamBoard.Missing')).toBeNull()
    expect(screen.queryByTestId('TeamBoard.Content.Error')).toBeNull()
  })

  it('surfaces an ordinary failure as failed, without unmounting the page chrome', async () => {
    // The failed read is the subject: this is one of the three answers the screen keeps apart.
    drivingAFailedRead()
    // A 500 or a dropped connection is neither of the above: it is retryable, and it must NOT
    // borrow the 404's sentence — "no such team" for a server that fell over is a lie that sends
    // the reader to look for a team that is still there.
    mockApi(new Error('network down'))
    renderPage()

    await screen.findByTestId('TeamBoard.Content.Error')
    expect(screen.queryByTestId('TeamBoard.Missing')).toBeNull()
    expect(screen.getByTestId('TeamBoard.Header')).toBeTruthy()
  })
})

describe('A board of other people’s work, spanning projects', () => {
  it('links a card into the project the CARD belongs to', async () => {
    // §83's repair, re-asserted on the second board that would expose it rather than assumed to
    // hold. This screen has no project of its own at all, so a `linkFor` that reached for a route
    // parameter would produce `/projects//cases/...` — and a board test that only ever renders
    // one project's cards cannot tell the difference.
    mockApi([
      card({
        id: 'review:r1:u3',
        title: 'Cart survives a page reload',
        derived: true,
        kind: 'REVIEW',
        sourceType: 'CASE',
        sourceId: 'case-20',
        linkedKey: 'TC-20',
        projectId: MOB,
        projectKey: 'MOB',
        allowedTransitions: [],
      }),
    ])
    renderPage()

    expect((await screen.findByTestId('Board.Card.LinkedKey')).getAttribute('href')).toBe(
      `/projects/${MOB}/cases/case-20`,
    )
  })

  it('stamps each card with its own project key', async () => {
    mockApi([
      card({ id: '1', title: 'Re-baseline the smoke pack', projectId: OLB, projectKey: 'OLB' }),
      card({ id: '2', title: 'Re-baseline the smoke pack', projectId: MOB, projectKey: 'MOB' }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('Board.Card')).toHaveLength(2))

    // Both, and by value: a single-card assertion would pass against a component that rendered the
    // first card's project on every card.
    expect(screen.getAllByTestId('Board.Card.Project').map((n) => n.textContent)).toEqual([
      'OLB',
      'MOB',
    ])
  })

  it('names the person each card is waiting on', async () => {
    // The field that distinguishes this board from the other two. On My Work every card is the
    // reader's, so an assignee is noise; here it is the primary fact — a team board that does not
    // say who owes what is a list of work with no owner.
    mockApi([
      card({
        id: '1',
        title: 'Re-baseline the checkout smoke pack',
        assignee: { id: 'u3', displayName: 'Priya Nair', email: 'priya@example.com', avatarUrl: null },
      }),
    ])
    renderPage()

    const cardNode = await screen.findByTestId('Board.Card')
    expect(within(cardNode).getByText(/Priya Nair/)).toBeTruthy()
  })
})

describe('The affordances and claims this screen deliberately lacks', () => {
  it('offers no personal filter, because that request is a 400', async () => {
    // `teamId` + `mine=true` selects two boards at once and the server refuses it. A chip that
    // produced that error would be `ui-rules` §4's render-then-reject — and the refusal is
    // *unobservable in the body* for a reader who is on the team, so it would look like a filter
    // that sometimes silently did nothing.
    mockApi([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')
    expect(screen.queryByTestId('Board.Filter.Mine')).toBeNull()
    expect(screen.queryByTestId('Board.Filter.Everyone')).toBeNull()
  })

  it('offers no New task, because a create needs a project this screen does not have', async () => {
    mockApi([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')
    expect(screen.queryByTestId('Board.NewTask')).toBeNull()
  })

  it('says the view is the reader’s rather than the team’s', async () => {
    // Not cosmetic. The cards are bounded by the reader's own project grants, so two people on the
    // same board legitimately see different sets — which means any total on this screen would be
    // authoritative-looking and wrong for everybody but its reader. The subtitle is what stops a
    // reader treating a partial board as the team's workload.
    mockApi([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')
    expect(screen.getByTestId('TeamBoard.Header').textContent).toContain(
      'different project access will see a different set',
    )
  })
})
