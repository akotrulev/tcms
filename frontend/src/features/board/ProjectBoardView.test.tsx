import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { BoardColumnConfig, BoardContents, IssueStatusRef } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import {
  aBoardConfig,
  aBoardIssueCard,
  aBoardLane,
  aBoardSummary,
  aCardMoveResult,
  aCompletion,
  anIssueLabel,
  boardContents,
} from '../../test/fixtures'
import { ProjectBoardView } from './ProjectBoardView'

/**
 * `work-board.html` — and the five things a static render cannot show.
 *
 * 1. **A WIP breach is reported and the card stays put** (story B3). The mockup states the rule on
 *    the screen — *"The drop was accepted; the breach is the column's to show, not the card's to
 *    refuse"* — and a test asserting only the warning would pass on a board that refused the drop,
 *    which is the opposite behaviour.
 * 2. **A column at exactly its limit is not in breach.** The middle of the three WIP states, and the
 *    one an off-by-one turns into a permanent warning on every team that works to its limit.
 * 3. **A collapsed lane keeps its percentage** — *"Nothing is hidden from the counts, only from the
 *    screen"*.
 * 4. **The board is operable from the keyboard alone**, through the `⋮ Move to…` menu. Driven
 *    through the menu rather than a simulated `@dnd-kit` keyboard drag: the menu is the path a real
 *    keyboard user takes, and a jsdom drag would assert `@dnd-kit`'s behaviour instead of this
 *    screen's.
 * 5. **The "No epic" lane is last and is not an error**, and an epic whose lane header is already on
 *    screen does not also appear as a card in it (D-r, answered by this task).
 */

const BACKLOG: IssueStatusRef = { id: 'st-backlog', name: 'Backlog', kind: 'NOT_STARTED', position: 0 }
const READY: IssueStatusRef = { id: 'st-ready', name: 'Ready', kind: 'NOT_STARTED', position: 1 }
const PROGRESS: IssueStatusRef = { id: 'st-prog', name: 'In Progress', kind: 'IN_PROGRESS', position: 2 }
const DONE: IssueStatusRef = { id: 'st-done', name: 'Done', kind: 'DONE', position: 3 }

/**
 * A configured column — `board_column`'s shape, which is **not** `cardMove.ts`'s `BoardColumn`.
 *
 * Typed on its own line and spread over, never `as BoardColumnConfig`: that cast turns a new required
 * field on the contract into a runtime failure spread across every test in the file instead of one
 * compile error here.
 */
const column = (
  id: string,
  name: string,
  position: number,
  statuses: IssueStatusRef[],
  wipLimit: number | null = null,
): BoardColumnConfig => {
  const base: BoardColumnConfig = {
    id,
    name,
    position,
    wipLimit,
    statuses,
    defaultStatusId: statuses[0].id,
  }
  return base
}

const COLUMNS = [
  column('col-backlog', 'Backlog', 0, [BACKLOG]),
  column('col-ready', 'Ready', 1, [READY]),
  column('col-prog', 'In Progress', 2, [PROGRESS], 3),
  column('col-done', 'Done', 3, [DONE]),
]

function renderView(contents: BoardContents, columns = COLUMNS, route = '/projects/p1/work?view=board') {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['issue:read', 'issue:edit', 'board:configure'])
    }
    // Anchored, so `/boards/b1/cards` is not caught by the configuration arm's prefix — the two
    // paths are one segment apart and this is exactly the collision the harness warns about.
    if (/^\/boards\/[^/]+$/.test(path)) return aBoardConfig({ id: 'b1', columns })
    if (/^\/boards\/[^/]+\/cards/.test(path)) return contents
    if (/^\/projects\/[^/]+\/boards$/.test(path)) {
      return [aBoardSummary({ id: 'b1', name: 'Team board', swimlaneBy: contents.swimlaneBy })]
    }
    if (/^\/projects\/[^/]+\/labels$/.test(path)) return [anIssueLabel({ id: 'lab-1', name: 'checkout' })]
    if (/^\/projects\/[^/]+\/members$/.test(path)) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)

  return render(
    <TestProviders client={createTestQueryClient()} route={route}>
      <Routes>
        <Route path="/projects/:projectId/work" element={<ProjectBoardView projectId="p1" />} />
      </Routes>
    </TestProviders>,
  )
}

const cellIn = (column: string, lane = 'none') =>
  screen.getByTestId(`ProjectBoard.Lane.${lane}.Cell.${column}`)

const headerOf = (column: string) => screen.getByTestId(`ProjectBoard.Column.${column}`)

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the WIP limit', () => {
  it('shows a breach in the header and leaves the card in the column', async () => {
    /*
      Story B3: "the drop is accepted and the column header shows the breach". Four cards sit in a
      three-limit column and the server has reported the breach, which is the state a drop leaves
      behind — the drop itself is not replayed here because the move is not optimistic, so what is on
      screen after one is whatever the refetch returned.
    */
    renderView(
      boardContents({
        lanes: [
          aBoardLane({
            cards: [1, 2, 3, 4].map((n) =>
              aBoardIssueCard({
                key: `PRJ-${n}`,
                title: `Card ${n}`,
                columnId: 'col-prog',
                status: PROGRESS,
                allowedTransitions: [DONE],
              }),
            ),
          }),
        ],
        breaches: [{ columnId: 'col-prog', limit: 3, count: 4 }],
      }),
    )

    expect(await screen.findByTestId('ProjectBoard.Column.In Progress')).toBeInTheDocument()

    expect(within(headerOf('In Progress')).getByText(/4 \/ 3/)).toBeInTheDocument()
    expect(within(headerOf('In Progress')).getByText('over the limit')).toBeInTheDocument()
    // The other half, and the half that makes this test about B3 rather than about a warning: the
    // card is still in the column it was dropped into.
    expect(within(cellIn('In Progress')).getByText('Card 4')).toBeInTheDocument()
  })

  it('does not warn a column that is exactly at its limit', async () => {
    /*
      The middle of the three states. The comparison is the server's — presence in `breaches` — so
      what this pins is that the screen renders no warning of its own: an off-by-one here would put a
      permanent ⚠ on any team that actually works to its limit, and a warning that is always on is
      one nobody reads.
    */
    renderView(
      boardContents({
        lanes: [
          aBoardLane({
            cards: [1, 2, 3].map((n) =>
              aBoardIssueCard({ key: `PRJ-${n}`, columnId: 'col-prog', status: PROGRESS }),
            ),
          }),
        ],
        breaches: [],
      }),
    )

    expect(await screen.findByTestId('ProjectBoard.Column.In Progress')).toBeInTheDocument()

    expect(within(headerOf('In Progress')).getByText('3 / 3')).toBeInTheDocument()
    expect(within(headerOf('In Progress')).queryByText('over the limit')).not.toBeInTheDocument()
  })

  it('renders an em dash where a column has no limit at all', async () => {
    // Null is "no limit" and is not zero — the distinction `serverColumnsOf` holds and a `||`
    // collapses. A `0 / 0` here would read as a column nothing may enter.
    renderView(boardContents({ lanes: [aBoardLane({ cards: [] })] }))

    expect(await screen.findByTestId('ProjectBoard.Column.Backlog')).toBeInTheDocument()
    expect(within(headerOf('Backlog')).getByTestId('ProjectBoard.Column.Backlog.Wip')).toHaveTextContent(
      '—',
    )
  })
})

describe('swimlanes', () => {
  const EPIC_LANES = boardContents({
    swimlaneBy: 'EPIC',
    lanes: [
      aBoardLane({
        key: 'PRJ-101',
        label: 'Checkout rebuild',
        completion: aCompletion({ percent: 70, basis: 'ESTIMATE', done: 7, total: 10 }),
        cards: [
          aBoardIssueCard({
            key: 'PRJ-201',
            title: 'Remember delivery address',
            columnId: 'col-backlog',
            status: BACKLOG,
            epicKey: 'PRJ-101',
            allowedTransitions: [READY],
          }),
        ],
      }),
      aBoardLane({
        key: null,
        label: 'No epic',
        completion: aCompletion({ percent: 40, basis: 'COUNT', done: 4, total: 10 }),
        cards: [
          aBoardIssueCard({
            key: 'PRJ-300',
            title: 'A stray bug',
            columnId: 'col-ready',
            status: READY,
            allowedTransitions: [PROGRESS],
          }),
        ],
      }),
    ],
  })

  it('keeps a collapsed lane’s percentage on screen', async () => {
    // The mockup states the rule: "Nothing is hidden from the counts — only from the screen." The
    // percentage is the lane's own `completion`, computed by the server over every card in it, so a
    // collapsed lane and an expanded one cannot disagree.
    renderView(EPIC_LANES)
    const user = userEvent.setup()

    expect(await screen.findByText('Remember delivery address')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Collapse the Checkout rebuild swimlane' }))

    expect(screen.queryByText('Remember delivery address')).not.toBeInTheDocument()
    expect(screen.getByTestId('ProjectBoard.Lane.PRJ-101.Percent')).toHaveTextContent('70%')
  })

  it('names the completion basis, so a percentage never changes meaning silently', async () => {
    // D8: an epic weighted by hours and one counted by heads are different measurements, and
    // `IssueCompletion.basis` is on the wire so the page can say which it got rather than infer it.
    renderView(EPIC_LANES)

    expect(await screen.findByTestId('ProjectBoard.Lane.PRJ-101.Basis')).toHaveTextContent(
      'by estimate',
    )
    expect(screen.getByTestId('ProjectBoard.Lane.none.Basis')).toHaveTextContent('by count · 4 of 10')
  })

  it('puts the no-epic lane last and does not style it as a problem', async () => {
    // The mockup: "a story without an epic is a normal story, not an error." The order is the
    // server's, so what this pins is that the screen does not re-sort it to the front.
    renderView(EPIC_LANES)

    await screen.findByText('Remember delivery address')

    const lanes = screen.getAllByRole('region', { name: /lane/i })
    expect(lanes.at(-1)).toHaveTextContent('No epic')
    expect(screen.getByTestId('ProjectBoard.Lane.none')).not.toHaveTextContent(/warning|error/i)
  })

  it('does not draw an epic twice when its own lane header is already on screen', async () => {
    /*
      D-r, answered by this task. An epic reaches the wire as a card — `allowedTransitions` being
      "empty for an Epic" has no meaning otherwise — and under EPIC grouping it has no epic above it,
      so the server files it in the catch-all lane. Drawing it there as well as drawing it as the
      band's own header states the same work twice in one view.
    */
    renderView({
      ...EPIC_LANES,
      lanes: [
        EPIC_LANES.lanes[0],
        {
          ...EPIC_LANES.lanes[1],
          cards: [
            ...EPIC_LANES.lanes[1].cards,
            aBoardIssueCard({
              key: 'PRJ-101',
              title: 'Checkout rebuild',
              typeName: 'Epic',
              typeLevel: 'EPIC',
              countsTowardCompletion: false,
              columnId: 'col-prog',
              status: PROGRESS,
              allowedTransitions: [],
            }),
          ],
        },
      ],
    })

    await screen.findByText('A stray bug')

    // The lane header is the epic's one appearance; the card is gone from the catch-all cell.
    expect(screen.getByTestId('ProjectBoard.Lane.PRJ-101.Link')).toHaveTextContent('Checkout rebuild')
    expect(
      within(cellIn('In Progress', 'none')).queryByText('Checkout rebuild'),
    ).not.toBeInTheDocument()
  })

  it('keeps an epic with no lane of its own visible, rather than deleting it from the board', async () => {
    // The other half of the same rule, and the reason the condition is presence of the lane rather
    // than the axis alone: an epic with no stories has no lane, so suppressing it unconditionally
    // would remove work from the board entirely.
    renderView(
      boardContents({
        swimlaneBy: 'EPIC',
        lanes: [
          aBoardLane({
            key: null,
            label: 'No epic',
            cards: [
              aBoardIssueCard({
                key: 'PRJ-900',
                title: 'An epic nobody has broken down',
                typeName: 'Epic',
                typeLevel: 'EPIC',
                countsTowardCompletion: false,
                columnId: 'col-backlog',
                status: BACKLOG,
                allowedTransitions: [],
              }),
            ],
          }),
        ],
      }),
    )

    expect(await screen.findByText('An epic nobody has broken down')).toBeInTheDocument()
  })
})

describe('the card face', () => {
  it('marks a card that is out of the percentage, so the lane’s meter stays explainable', async () => {
    // A Spike is on the board and out of the completion. A card that silently did not count makes
    // the roll-up above it unexplainable — the reader counts the cards and the meter counts fewer.
    renderView(
      boardContents({
        lanes: [
          aBoardLane({
            cards: [
              aBoardIssueCard({
                key: 'PRJ-209',
                title: 'Can we drop the legacy cart cookie?',
                typeName: 'Spike',
                countsTowardCompletion: false,
                columnId: 'col-backlog',
                status: BACKLOG,
              }),
            ],
          }),
        ],
      }),
    )

    const card = await screen.findByTestId('ProjectBoard.Lane.none.Cell.Backlog.Card.PRJ-209')
    expect(within(card).getByText('not in %')).toBeInTheDocument()
  })

  it('carries B3’s whole face — labels, sub-task progress, the estimate and the assignee', async () => {
    renderView(
      boardContents({
        lanes: [
          aBoardLane({
            cards: [
              aBoardIssueCard({
                key: 'PRJ-121',
                title: 'One-page address entry',
                columnId: 'col-prog',
                status: PROGRESS,
                labels: [anIssueLabel({ id: 'lab-1', name: 'checkout' })],
                subtaskDone: 3,
                subtaskTotal: 5,
                estimateHours: 12,
                dueDate: '2026-08-28',
                overdue: true,
                assignee: {
                  id: 'u1',
                  displayName: 'Dana Petrov',
                  email: 'dana@acme.test',
                },
              }),
            ],
          }),
        ],
        breaches: [],
      }),
    )

    const card = await screen.findByTestId('ProjectBoard.Lane.none.Cell.In Progress.Card.PRJ-121')
    expect(within(card).getByText('checkout')).toBeInTheDocument()
    expect(within(card).getByText('3/5 sub-tasks')).toBeInTheDocument()
    expect(within(card).getByText('12h')).toBeInTheDocument()
    // The overdue date is a word plus a date, never a colour alone — and the name is on the avatar
    // for a screen reader rather than only in its initials.
    expect(within(card).getByTestId(/Due$/)).toHaveTextContent('overdue 2026-08-28')
    expect(within(card).getByText('Dana Petrov')).toBeInTheDocument()
  })

  it('draws none of the four things the mockup shows and this build has no field for', async () => {
    /*
      D-f. The `AI` tag (`issue.agent_eligible`, M5), the `PR #482` badge (M5's forge adapter) and
      logged hours (M6) are absent, and so is the mockup's `TC-311 failed` result badge. The mockup
      draws the finished product; drawing any of them now needs a field nothing writes.
    */
    renderView(
      boardContents({
        lanes: [
          aBoardLane({
            cards: [
              aBoardIssueCard({ key: 'PRJ-124', title: 'Address validation', columnId: 'col-prog', status: PROGRESS }),
            ],
          }),
        ],
      }),
    )

    await screen.findByText('Address validation')

    expect(screen.queryByText('AI')).not.toBeInTheDocument()
    expect(screen.queryByText(/^PR #/)).not.toBeInTheDocument()
    expect(screen.queryByText(/logged/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/AI-eligible/)).not.toBeInTheDocument()
  })
})

describe('keyboard parity with the pointer', () => {
  it('moves a card between columns with the keyboard alone', async () => {
    /*
      B3's non-functional requirement — *"Every board action is reachable from the keyboard,
      including moving a card between columns"* — and WCAG 2.2 Dragging Movements (2.5.7).

      Tested through the MENU rather than through a simulated `@dnd-kit` keyboard drag: the menu is
      the path a real keyboard user takes, and a jsdom drag would assert `@dnd-kit`'s behaviour
      instead of this screen's. The Tab step from the card's drag handle is what proves the trigger is
      in the tab order; everything after it is keys only.

      Radix skips a disabled item in the roving order, so opening focuses the first LEGAL column —
      Ready — and one ArrowDown reaches In Progress. That is also the assertion that the disabled
      entries are not merely styled: a focus that landed on Backlog would fail here.
    */
    renderView(
      boardContents({
        lanes: [
          aBoardLane({
            cards: [
              aBoardIssueCard({
                key: 'PRJ-201',
                title: 'Remember delivery address',
                columnId: 'col-backlog',
                status: BACKLOG,
                allowedTransitions: [READY, PROGRESS],
              }),
            ],
          }),
        ],
      }),
    )
    const post = vi.spyOn(api, 'post').mockResolvedValue(aCardMoveResult() as never)
    const user = userEvent.setup()

    const card = await screen.findByTestId('ProjectBoard.Lane.none.Cell.Backlog.Card.PRJ-201')
    within(card).getByTestId(/DragHandle$/).focus()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Move PRJ-201' })).toHaveFocus()

    await user.keyboard('{Enter}')
    await user.keyboard('{ArrowDown}{Enter}')

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/boards/b1/cards/PRJ-201/move',
        { toColumnId: 'col-prog' },
        expect.anything(),
      ),
    )
  })

  it('tells the reader on the board that dragging is never the only way', async () => {
    // The mockup says it in prose ON the board, and names the second audience the WCAG criterion
    // does not: this is also what a touch user gets. A keyboard path nobody can discover is one
    // nobody uses, which is why the sentence is part of the screen rather than of the docs.
    renderView(boardContents({ lanes: [aBoardLane({ cards: [] })] }))

    const note = await screen.findByTestId('ProjectBoard.KeyboardNote')
    expect(note).toHaveTextContent('Dragging is never the only way')
    expect(note).toHaveTextContent(/touch user/)
  })
})

describe('the filter', () => {
  it('reads the board’s own parameter set out of the URL and offers each applied one for removal', async () => {
    /*
      D-v: `BoardFilters` is not `IssueFilters`. A `level` arriving from the Table view gets a chip
      but no picker — a reader who narrowed there and switched here must be able to see and remove
      it, or they stare at an empty board with no way off it (D34).
    */
    renderView(
      boardContents({
        lanes: [aBoardLane({ cards: [aBoardIssueCard({ key: 'PRJ-1', columnId: 'col-backlog', status: BACKLOG })] })] }),
      COLUMNS,
      '/projects/p1/work?view=board&labelId=lab-1&level=STORY',
    )

    /*
      Waited for rather than read once: a chip falls back to the raw id until the label list lands, so
      the first render legitimately says `Label · lab-1`. Asserting on that first paint would pin the
      fallback and pass on a screen that never resolves the name.
    */
    await waitFor(() =>
      expect(screen.getByTestId('ProjectBoard.Filters.Applied.label:lab-1')).toHaveTextContent(
        'Label · checkout',
      ),
    )
    expect(screen.getByTestId('ProjectBoard.Filters.Applied.level:STORY')).toHaveTextContent(
      'Level · Story level',
    )
    // Trello's semantics, rendered rather than assumed: OR within a category, AND across them.
    expect(screen.getByTestId('ProjectBoard.Filters.Summary')).toHaveTextContent(
      /any of the values within a filter, and every filter together/,
    )
  })

  it('sends the filter to the cards endpoint and not to the configuration one', async () => {
    // The two reads have two keys and two lifetimes (D-h/D-u). A filter reaching `/boards/{id}` would
    // re-fetch the column layout on every chip and relayout the board under the reader's hand.
    /*
      One card, deliberately: a filtered board with none of them is the `no-filter-match` state, which
      replaces the columns with the way back off it — so a column header would be absent for a reason
      that has nothing to do with what this test is about.
    */
    renderView(
      boardContents({
        lanes: [
          aBoardLane({
            cards: [aBoardIssueCard({ key: 'PRJ-1', columnId: 'col-backlog', status: BACKLOG })],
          }),
        ],
      }),
      COLUMNS,
      '/projects/p1/work?view=board&labelId=lab-1&assigneeId=u1',
    )

    await screen.findByTestId('ProjectBoard.Column.Backlog')

    const paths = vi.mocked(api.get).mock.calls.map(([path]) => path as string)
    expect(paths).toContain('/boards/b1/cards?labelId=lab-1&assigneeId=u1')
    expect(paths).toContain('/boards/b1')
  })
})

describe('the board’s own controls', () => {
  it('changes the grouping by rewriting the board, carrying its columns back unchanged', async () => {
    /*
      Grouping is the board's `swimlaneBy` (B3: "the board reloads"), so it is a `PATCH` and it costs
      `board:configure`. `BoardInput.columns` is a **replacement**, so the round-trip has to be
      faithful: every column keeps its id, which is what makes this a grouping change rather than a
      column rebuild.
    */
    renderView(boardContents({ lanes: [aBoardLane({ cards: [] })] }))
    const patch = vi
      .spyOn(api, 'patch')
      .mockResolvedValue(aBoardConfig({ id: 'b1', columns: COLUMNS, swimlaneBy: 'EPIC' }) as never)
    const user = userEvent.setup()

    /*
      **Re-queried inside `waitFor`, not held across it.** `PermissionGate` renders its denied branch
      inside a `<span role="none">` anchor and drops the anchor when the verdict lands, which remounts
      the control — so a node captured before the verdict is a detached element that stays disabled
      forever. `ArchiveProject.test.tsx` carries the same comment for the same five wasted minutes.
    */
    await waitFor(() => expect(screen.getByLabelText('Group by')).toBeEnabled())
    await user.selectOptions(screen.getByLabelText('Group by'), 'EPIC')

    await waitFor(() =>
      expect(patch).toHaveBeenCalledWith(
        '/boards/b1',
        expect.objectContaining({
          name: 'Team board',
          swimlaneBy: 'EPIC',
          columns: COLUMNS.map((entry) => ({
            id: entry.id,
            name: entry.name,
            wipLimit: entry.wipLimit ?? null,
            statusIds: entry.statuses.map((status) => status.id),
            defaultStatusId: entry.defaultStatusId,
          })),
        }),
        expect.anything(),
      ),
    )
  })

  it('links Configure boards at the settings screen 012 T9 landed', async () => {
    /*
      ~~It was disabled with *"arrives with the board settings screen"* on it, because the route did
      not exist and a link to a 404 is worse than a control that explains itself.~~ → T9 built the
      route, so the sentence became false and the assertion had to move with it. The `href` is what
      is asserted rather than the absence of `disabled`: a `<span>` styled as a link would satisfy
      the weaker claim, and in-app navigation has to be a real `<Link>`.
    */
    renderView(boardContents({ lanes: [aBoardLane({ cards: [] })] }))

    const configure = await screen.findByTestId('ProjectBoard.Configure')
    expect(configure).toHaveAttribute('href', '/projects/p1/settings/boards')
  })
})

describe('the three reads fail separately', () => {
  it('keeps the picker and the filter strip when the cards read fails', async () => {
    /*
      `DataRegion`'s rule, and the reason there are three of them here rather than one: a failed cards
      read must not unmount the controls the reader needs in order to change the query and retry.
    */
    const { drivingAFailedRead } = await import('../../test/renderFailures')
    // The subject: the cards endpoint answers 500 while the board and its configuration load.
    drivingAFailedRead()

    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read'])
      if (/^\/boards\/[^/]+$/.test(path)) return aBoardConfig({ id: 'b1', columns: COLUMNS })
      if (/^\/boards\/[^/]+\/cards/.test(path)) throw new Error('cards are down')
      if (/^\/projects\/[^/]+\/boards$/.test(path)) return [aBoardSummary({ id: 'b1' })]
      if (/^\/projects\/[^/]+\/labels$/.test(path)) return []
      if (/^\/projects\/[^/]+\/members$/.test(path)) return []
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    render(
      <TestProviders client={createTestQueryClient()} route="/projects/p1/work?view=board">
        <Routes>
          <Route path="/projects/:projectId/work" element={<ProjectBoardView projectId="p1" />} />
        </Routes>
      </TestProviders>,
    )

    expect(await screen.findByTestId('ProjectBoard.Content.Error')).toBeInTheDocument()
    expect(screen.getByTestId('ProjectBoard.Picker')).toBeInTheDocument()
    expect(screen.getByTestId('ProjectBoard.Filters')).toBeInTheDocument()
  })
})
