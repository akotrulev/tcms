import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { BoardConfig, IssueStatusRef, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import {
  aBoardConfig,
  aBoardSummary,
  aProject,
  aProjectWorkflow,
  aStatusRef,
  anIssueLabel,
} from '../../test/fixtures'
import { BoardSettingsPage } from './BoardSettingsPage'

/**
 * `settings-boards.html`, and the two things on it that are requirements rather than layout.
 *
 * 1. **The coverage check is the screen's point.** The mockup's footer says what it is for — *"A
 *    status claimed twice, or not at all, is what this check exists to catch"* — and the point of
 *    rendering it at all is that a person sees the invariant FAIL, by status name, before the save
 *    is attempted. `ui-rules`: never render an action the backend will always reject. A screen that
 *    let the save go and printed the 422 would typecheck, lint and pass every test that only asked
 *    whether the table rendered.
 * 2. **Review R13 on screen.** A status added to the workflow after a board exists is claimed
 *    server-side inside the workflow write (012 D-g), in the column holding the default status of
 *    the same kind. That is a database property; this asserts the SCREEN shows it where it landed
 *    and lets it be moved, because an auto-claim nobody can see is one nobody can correct.
 *
 * And two §31 controls whose whole behaviour is a disabled attribute plus a sentence: the last
 * board's Delete, which matches the server's 422 rather than discovering it, and the read-only
 * rendering for a caller without `board:configure`.
 */

const PROJECT = 'p1'

const BACKLOG = aStatusRef({ id: 's-backlog', name: 'Backlog', kind: 'NOT_STARTED', position: 0 })
const PROGRESS = aStatusRef({ id: 's-prog', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 })
const REVIEW = aStatusRef({ id: 's-review', name: 'In Review', kind: 'IN_PROGRESS', position: 2 })
const BLOCKED = aStatusRef({ id: 's-blocked', name: 'Blocked', kind: 'IN_PROGRESS', position: 3 })
const DONE = aStatusRef({ id: 's-done', name: 'Done', kind: 'DONE', position: 4 })

const STATUSES: IssueStatusRef[] = [BACKLOG, PROGRESS, REVIEW, BLOCKED, DONE]

/** The team board — one status per column, which is the seeded shape. */
function teamBoard(statuses: readonly IssueStatusRef[] = STATUSES): BoardConfig {
  return aBoardConfig({
    id: 'b-team',
    name: 'Team board',
    columns: statuses.map((status, position) => ({
      id: `t-${status.id}`,
      name: status.name,
      position,
      wipLimit: null,
      statuses: [status],
      defaultStatusId: status.id,
    })),
  })
}

/** The stakeholder board — three folded columns, which is where the default-status control lives. */
function stakeholderBoard(): BoardConfig {
  return aBoardConfig({
    id: 'b-stake',
    name: 'Stakeholder board',
    columns: [
      {
        id: 'k-not-started',
        name: 'Not started',
        position: 0,
        wipLimit: null,
        statuses: [BACKLOG],
        defaultStatusId: BACKLOG.id,
      },
      {
        id: 'k-in-flight',
        name: 'In flight',
        position: 1,
        wipLimit: null,
        statuses: [PROGRESS, REVIEW, BLOCKED],
        defaultStatusId: PROGRESS.id,
      },
      {
        id: 'k-done',
        name: 'Done',
        position: 2,
        wipLimit: null,
        statuses: [DONE],
        defaultStatusId: DONE.id,
      },
    ],
  })
}

function mockReads(
  boards: BoardConfig[],
  statuses: readonly IssueStatusRef[] = STATUSES,
  permissions: PermissionKey[] = ['board:read', 'board:configure'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) {
      return aProjectWorkflow({ projectId: PROJECT, statuses: [...statuses] })
    }
    if (/^\/projects\/[^/]+\/boards$/.test(path)) {
      return boards.map((board) =>
        aBoardSummary({ id: board.id, name: board.name, columnCount: board.columns.length }),
      )
    }
    if (/^\/projects\/[^/]+\/labels$/.test(path)) {
      return [anIssueLabel({ id: 'lab-1', name: 'checkout', color: 'label-3' })]
    }
    // Anchored, so `/boards/{id}/cards` could never be served the configuration — the collision the
    // harness warns about, and the two paths are one segment apart.
    const board = /^\/boards\/([^/]+)$/.exec(path)
    if (board) return boards.find((each) => each.id === board[1])
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/settings/boards`}>
      <Routes>
        <Route path="/projects/:projectId/settings/boards" element={<BoardSettingsPage />} />
      </Routes>
    </TestProviders>,
  )
}

/** One board's panel, so a control named the same on two boards is still unambiguous. */
function panel(name: string): HTMLElement {
  return screen.getByTestId(`BoardSettings.Board.${name}`)
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the boards, and what each column claims', () => {
  it('draws every board with its columns and their claimed statuses', async () => {
    mockReads([teamBoard(), stakeholderBoard()])
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Team board')

    // The folded column is the stakeholder board's whole point: three statuses under one head.
    const inFlight = 'BoardSettings.Board.Stakeholder board.Column.In flight'
    /*
      By testid, not by text: a folded column also renders the default-status select, whose
      `<option>`s carry the same three words. `getByText` matches both and throws — which is the
      column doing exactly what it should, so the query is what moves.
    */
    expect(screen.getByTestId(`${inFlight}.Status.In Progress`)).toBeInTheDocument()
    expect(screen.getByTestId(`${inFlight}.Status.In Review`)).toBeInTheDocument()
    expect(screen.getByTestId(`${inFlight}.Status.Blocked`)).toBeInTheDocument()
  })

  it('offers a default status only on a column claiming more than one', async () => {
    /*
      The mockup shows the control on the stakeholder board and not on the team board, and puts the
      reason in the team board's footer. On a one-status column the select has exactly one legal
      value, so rendering it would teach a reader that a choice exists and then refuse to let them
      make one.
    */
    mockReads([teamBoard(), stakeholderBoard()])
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Stakeholder board')

    expect(
      screen.getByTestId('BoardSettings.Board.Stakeholder board.Column.In flight.Default'),
    ).toBeInTheDocument()
    expect(
      screen.queryByTestId('BoardSettings.Board.Team board.Column.In Progress.Default'),
    ).not.toBeInTheDocument()

    expect(
      within(panel('Team board')).getByText(/One status per column here/),
    ).toBeInTheDocument()
    expect(
      within(panel('Stakeholder board')).getByText(/takes that column’s default status/),
    ).toBeInTheDocument()
  })
})

describe('the coverage check', () => {
  it('shows a newly added workflow status in its auto-claimed column, and lets it move', async () => {
    /*
      009 review R13, and the module's fifth done-criterion. The claim happens server-side inside the
      workflow write (D-g) — this asserts the screen SHOWS it where it landed and that it can be
      corrected from there, because an auto-claim nobody can see is one nobody can move.
    */
    const AWAITING = aStatusRef({
      id: 's-awaiting',
      name: 'Awaiting QA',
      kind: 'IN_PROGRESS',
      position: 5,
    })
    const claimed = teamBoard()
    claimed.columns = claimed.columns.map((column) =>
      column.name === 'In Progress'
        ? { ...column, statuses: [PROGRESS, AWAITING], defaultStatusId: PROGRESS.id }
        : column,
    )

    mockReads([claimed], [...STATUSES, AWAITING])
    const user = userEvent.setup()
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Team board')
    const board = panel('Team board')

    expect(
      screen.getByTestId('BoardSettings.Board.Team board.Column.In Progress.Status.Awaiting QA'),
    ).toBeInTheDocument()

    await user.click(within(board).getByRole('button', { name: 'Move Awaiting QA' }))
    await user.click(await screen.findByRole('menuitem', { name: 'In Review' }))

    expect(
      screen.getByTestId('BoardSettings.Board.Team board.Column.In Review.Status.Awaiting QA'),
    ).toBeInTheDocument()
    expect(
      screen.queryByTestId(
        'BoardSettings.Board.Team board.Column.In Progress.Status.Awaiting QA',
      ),
    ).not.toBeInTheDocument()
  })

  it('shows the check failing, by status name, before the save is attempted', async () => {
    mockReads([stakeholderBoard()])
    const user = userEvent.setup()
    const put = vi.spyOn(api, 'patch')
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Stakeholder board')
    const board = panel('Stakeholder board')

    // The green footer first: the invariant is holding, and the table is where that is visible.
    expect(screen.getByTestId('BoardSettings.Coverage.Holds')).toBeInTheDocument()

    await user.click(
      within(board).getByRole('button', { name: 'Remove Blocked from In flight' }),
    )

    // The full sentence is the board panel's, once. The coverage footer carries the clause and the
    // board's name instead — two registers, one derivation, and never the same words twice.
    expect(screen.getByText(/No column claims: Blocked/)).toBeInTheDocument()
    expect(
      within(screen.getByTestId('BoardSettings.Coverage.Problems')).getByText(/on no column/),
    ).toBeInTheDocument()
    expect(within(board).getByRole('button', { name: 'Save Stakeholder board' })).toBeDisabled()
    expect(screen.queryByTestId('BoardSettings.Coverage.Holds')).not.toBeInTheDocument()
    // The cell says so too, which is the half a banner cannot: WHICH board is missing it.
    expect(
      screen.getByTestId('BoardSettings.Coverage.Cell.Stakeholder board.Blocked'),
    ).toHaveTextContent('no column')
    expect(put).not.toHaveBeenCalled()
  })

  it('saves the whole column list once the check holds again', async () => {
    mockReads([stakeholderBoard()])
    const user = userEvent.setup()
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(stakeholderBoard() as never)
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Stakeholder board')
    const board = panel('Stakeholder board')

    await user.click(
      within(board).getByRole('button', { name: 'Remove Blocked from In flight' }),
    )
    await user.click(within(board).getByRole('button', { name: 'Move Blocked' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Done' }))

    const save = within(board).getByRole('button', { name: 'Save Stakeholder board' })
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(save)

    /*
      The whole array, every column carried back with its id — `columns` is a REPLACEMENT and a
      column whose id is absent is deleted, so a body naming only the two that changed would delete
      the third and put its status on no column at all.
    */
    await waitFor(() =>
      expect(patch).toHaveBeenCalledWith(
        '/boards/b-stake',
        expect.objectContaining({
          name: 'Stakeholder board',
          columns: [
            expect.objectContaining({ id: 'k-not-started', statusIds: [BACKLOG.id] }),
            expect.objectContaining({ id: 'k-in-flight', statusIds: [PROGRESS.id, REVIEW.id] }),
            expect.objectContaining({ id: 'k-done', statusIds: [DONE.id, BLOCKED.id] }),
          ],
        }),
        expect.anything(),
      ),
    )
  })
})

describe('deleting a board', () => {
  it('disables Delete on the project’s only board and says why', async () => {
    mockReads([teamBoard()])
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Team board')

    const remove = screen.getByTestId('BoardSettings.Board.Team board.Delete')
    expect(remove).toBeDisabled()
    expect(remove).toHaveAttribute('title', expect.stringContaining('keeps at least one board'))
  })

  it('asks first, and says a board is a view', async () => {
    /*
      The destructive-sounding word is the one thing on this screen a person will hesitate over, and
      the answer is a property of the model rather than a reassurance: a board owns no issue. The
      note at the top of the page says it once; the dialog says it again where the question is asked,
      because a note at the top is not on screen at the bottom.
    */
    mockReads([teamBoard(), stakeholderBoard()])
    const user = userEvent.setup()
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Stakeholder board')
    await user.click(screen.getByTestId('BoardSettings.Board.Stakeholder board.Delete'))

    // Scoped to the dialog: the note at the top of the page says the same thing, which is the
    // point — it is said twice because the second time is where the question is actually asked.
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/no issue is lost/)).toBeInTheDocument()
    expect(del).not.toHaveBeenCalled()

    await user.click(screen.getByTestId('BoardSettings.Delete.Confirm'))
    await waitFor(() => expect(del).toHaveBeenCalledWith('/boards/b-stake', expect.anything()))
  })
})

describe('creating a board', () => {
  it('starts it with one column per status, so the 422 is unreachable', async () => {
    /*
      Every status claimed by exactly one column is the create's own 422, and the seeded shape is
      the only starting arrangement that cannot trip it. Folding comes afterwards, on the board's own
      panel, where the coverage check is watching.
    */
    mockReads([teamBoard()])
    const user = userEvent.setup()
    const post = vi.spyOn(api, 'post').mockResolvedValue(stakeholderBoard() as never)
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Team board')
    await user.click(screen.getByTestId('BoardSettings.NewBoard'))
    await user.type(screen.getByTestId('BoardSettings.NewBoard.Name'), 'Stakeholder board')
    await user.click(screen.getByTestId('BoardSettings.NewBoard.Confirm'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        `/projects/${PROJECT}/boards`,
        {
          name: 'Stakeholder board',
          swimlaneBy: 'NONE',
          columns: STATUSES.map((status) => ({
            name: status.name,
            wipLimit: null,
            statusIds: [status.id],
            defaultStatusId: status.id,
          })),
        },
        expect.anything(),
      ),
    )
  })
})

describe('labels', () => {
  it('creates one with the colour slot that was pressed', async () => {
    /*
      Step 1b's decision, on screen: the ten `--color-label-*` tokens are declared, so the picker is
      the swatches themselves. A `<select>` of ten token names would be the blind guess the palette
      was deferred to avoid, with a dropdown around it.
    */
    mockReads([teamBoard()])
    const user = userEvent.setup()
    const post = vi.spyOn(api, 'post').mockResolvedValue(anIssueLabel() as never)
    renderPage()

    await screen.findByTestId('BoardSettings.Labels')
    await user.type(screen.getByTestId('BoardSettings.Labels.Name'), 'payments')
    await user.click(screen.getByTestId('BoardSettings.Labels.Color.label-7'))
    await user.click(screen.getByTestId('BoardSettings.Labels.Add'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        `/projects/${PROJECT}/labels`,
        { name: 'payments', color: 'label-7' },
        expect.anything(),
      ),
    )
  })

  it('deletes one without asking, unlike a board', async () => {
    // A label leaving takes a word off some cards and loses nothing else. A board and a label are
    // different weights of act and the screen treats them differently on purpose.
    mockReads([teamBoard()])
    const user = userEvent.setup()
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    await screen.findByTestId('BoardSettings.Labels.Delete.checkout')
    await user.click(screen.getByTestId('BoardSettings.Labels.Delete.checkout'))

    await waitFor(() => expect(del).toHaveBeenCalledWith('/labels/lab-1', expect.anything()))
  })
})

describe('a caller without board:configure', () => {
  it('reads the boards and can change nothing', async () => {
    /*
      §31, disable-don't-hide. The reads cost `board:read`, which every role holds, so the route is
      registered for everybody — and this is also the screen that explains what a board IS, which is
      the sentence a reader who cannot change one needs most.
    */
    mockReads([teamBoard(), stakeholderBoard()], STATUSES, ['board:read'])
    renderPage()

    await screen.findByTestId('BoardSettings.Board.Team board')

    // Re-queried inside the wait: `PermissionGate` REMOUNTS its child when the verdict lands, so a
    // node captured before the answer is a detached element that stays disabled whatever happens.
    await waitFor(() => expect(screen.getByTestId('BoardSettings.NewBoard')).toBeDisabled())

    expect(screen.getByTestId('BoardSettings.Board.Team board.ReadOnly')).toBeInTheDocument()
    expect(screen.getByTestId('BoardSettings.Board.Stakeholder board.Delete')).toBeDisabled()
    expect(screen.getByTestId('BoardSettings.Labels.Add')).toBeDisabled()
    // The check still renders — it is a fact about the project, not an editing affordance.
    expect(screen.getByTestId('BoardSettings.Coverage.Holds')).toBeInTheDocument()
  })
})
