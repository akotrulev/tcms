import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { BacklogRow, IssueStatusRef } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { drivingAFailedWrite } from '../../test/renderFailures'
import {
  aBacklogRow,
  aRankResult,
  anIssue,
  anIssueLabel,
  anIssueSummary,
  anIssueType,
  aStatusRef,
} from '../../test/fixtures'
import { BacklogView } from './BacklogView'

/**
 * `work-backlog.html` — story B4, and the five things a static render cannot show.
 *
 * 1. **The `↑ ↓` pair and the drag handle end in the same mutation.** The mockup states it on the
 *    panel — *"drag the ⠿ handle, or use ↑ / ↓ — both persist"* — and an arrow taking a different
 *    path would be the keyboard route quietly not getting the rollback, which is the defect class
 *    keyboard parity exists to prevent arriving through the fix for it.
 * 2. **Moving up places BEFORE the row above, not "swap ranks"**, and the payload carries exactly
 *    one neighbour — the two the server answers 400 for are unconstructible (D-q).
 * 3. **A refused rank snaps the row back.** The write is the one optimistic mutation in the
 *    product, so a test that only asserted the request would pass on a board with no rollback.
 * 4. **There is no "Move to release"** (D-f). `release` is M3's table; the bulk actions that ship
 *    are Set epic and Assign.
 * 5. **Empty is two different states**, and only one of them offers a way out.
 */

const READY: IssueStatusRef = aStatusRef({ id: 'st-ready', name: 'Ready', kind: 'NOT_STARTED', position: 0 })

const ROWS: BacklogRow[] = [
  aBacklogRow({ key: 'ACME-121', title: 'One-page address entry', position: 1, status: READY }),
  aBacklogRow({ key: 'ACME-122', title: 'Card capture without a redirect', position: 2, status: READY }),
  aBacklogRow({ key: 'ACME-131', title: 'Saved card list renders twice', position: 3, status: READY }),
]

function renderView(rows: BacklogRow[] = ROWS, route = '/projects/p1/work?view=backlog') {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['issue:read', 'issue:edit', 'board:rank'])
    }
    // Anchored, so `/projects/p1/labels` is not caught by the backlog arm's prefix.
    if (/^\/projects\/[^/]+\/backlog/.test(path)) return rows
    if (/^\/projects\/[^/]+\/labels$/.test(path)) return [anIssueLabel({ id: 'lab-1', name: 'checkout' })]
    if (/^\/projects\/[^/]+\/members$/.test(path)) return []
    // The bulk bar's epic picker — `GET /issues?projectId=p1&level=EPIC&sort=key`.
    if (path.startsWith('/issues')) {
      return [
        anIssueSummary({
          key: 'ACME-101',
          title: 'Checkout rebuild',
          type: anIssueType({ id: 'ty-epic', name: 'Epic', level: 'EPIC' }),
        }),
      ]
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)

  return render(
    <TestProviders client={createTestQueryClient()} route={route}>
      <Routes>
        <Route path="/projects/:projectId/work" element={<BacklogView projectId="p1" />} />
      </Routes>
    </TestProviders>,
  )
}

/**
 * A promise this test resolves by hand.
 *
 * The optimistic patch and its rollback both live in the window between the request leaving and
 * `onSettled`'s refetch landing — and the read double answers with the order it always had, so a
 * test that let the refetch run would assert the fixture rather than the patch. Holding both halves
 * open is what makes the two states separately observable.
 */
function deferred<T>() {
  let settle!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolveIt, rejectIt) => {
    settle = resolveIt
    reject = rejectIt
  })
  return { promise, settle, reject }
}

/** The keys on screen, top to bottom — the assertion an optimistic reorder is actually about. */
const order = () =>
  screen.getAllByTestId(/^Backlog\.Row\.[^.]+$/).map((row) => row.getAttribute('data-testid'))

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('ranking', () => {
  it('places a row BEFORE the one above it when the up arrow is pressed', async () => {
    /*
      Not "swap ranks": a swap works for adjacent rows and silently does the wrong thing the first
      time somebody adds a multi-row move. And exactly one neighbour reaches the wire — `beforeKey`
      here — because both, and neither, are the two payloads the server answers 400 for (D-q).
    */
    renderView()
    const put = vi.spyOn(api, 'put').mockResolvedValue(aRankResult({ key: 'ACME-122' }) as never)
    const user = userEvent.setup()

    await screen.findByTestId('Backlog.Row.ACME-122')
    await user.click(screen.getByRole('button', { name: 'Move ACME-122 up' }))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(
        '/issues/ACME-122/rank',
        { beforeKey: 'ACME-121' },
        expect.anything(),
      ),
    )
  })

  it('places a row AFTER the one below it when the down arrow is pressed', async () => {
    renderView()
    const put = vi.spyOn(api, 'put').mockResolvedValue(aRankResult({ key: 'ACME-122' }) as never)
    const user = userEvent.setup()

    await screen.findByTestId('Backlog.Row.ACME-122')
    await user.click(screen.getByRole('button', { name: 'Move ACME-122 down' }))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(
        '/issues/ACME-122/rank',
        { afterKey: 'ACME-131' },
        expect.anything(),
      ),
    )
  })

  it('reorders the list before the server answers, and renumbers it', async () => {
    // The one optimistic mutation in the product (D-c): a rank change's legality is the server's
    // neighbours, so there is nothing the client could have checked first, and a row that hung
    // where it was for a round trip and then jumped would read worse than one that snaps back.
    renderView()
    const write = deferred<unknown>()
    vi.spyOn(api, 'put').mockReturnValue(write.promise as never)
    const user = userEvent.setup()

    await screen.findByTestId('Backlog.Row.ACME-122')
    await user.click(screen.getByRole('button', { name: 'Move ACME-122 up' }))

    // Still in flight. Whatever is on screen now can only be the patch.
    await waitFor(() =>
      expect(order()).toEqual([
        'Backlog.Row.ACME-122',
        'Backlog.Row.ACME-121',
        'Backlog.Row.ACME-131',
      ]),
    )
    // `position` is the server's, derived from rank order and never stored — but the optimistic
    // patch renumbers it, or the moved row draws its old number beside its new place.
    expect(screen.getByTestId('Backlog.Row.ACME-122.Position')).toHaveTextContent('1')
    expect(screen.getByTestId('Backlog.Row.ACME-121.Position')).toHaveTextContent('2')

    write.settle(aRankResult({ key: 'ACME-122' }))
  })

  it('snaps the row back when the server refuses the rank', async () => {
    drivingAFailedWrite() // the 409 `tcmsFaults.rank('conflict')` fabricates — a neighbour moved
    // between the read and the write, and the optimistic patch must revert rather than leave an
    // order the server refused.
    /*
      Both halves are held open, and that is what makes this test discriminating. `onSettled`
      invalidates on the error path too, so a refetch would restore the fixture's order whether or
      not the rollback ran — and this assertion would pass against a hook that had none. Holding the
      refetch pending leaves the snapshot restore as the only thing that could have moved the row
      back.
    */
    const refetch = deferred<BacklogRow[]>()
    let reads = 0
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) {
        return heldPermissions(['issue:read', 'board:rank'])
      }
      if (/^\/projects\/[^/]+\/backlog/.test(path)) {
        reads += 1
        return reads === 1 ? ROWS : refetch.promise
      }
      if (/^\/projects\/[^/]+\/labels$/.test(path)) return []
      if (/^\/projects\/[^/]+\/members$/.test(path)) return []
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    render(
      <TestProviders client={createTestQueryClient()} route="/projects/p1/work?view=backlog">
        <Routes>
          <Route path="/projects/:projectId/work" element={<BacklogView projectId="p1" />} />
        </Routes>
      </TestProviders>,
    )

    const write = deferred<unknown>()
    vi.spyOn(api, 'put').mockReturnValue(write.promise as never)
    const user = userEvent.setup()

    await screen.findByTestId('Backlog.Row.ACME-122')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Move ACME-122 up' })).toBeEnabled(),
    )
    await user.click(screen.getByRole('button', { name: 'Move ACME-122 up' }))

    await waitFor(() => expect(order()[0]).toBe('Backlog.Row.ACME-122'))

    write.reject(new ApiError(409, 'A neighbour moved. Refresh and try again.'))

    await waitFor(() =>
      expect(order()).toEqual([
        'Backlog.Row.ACME-121',
        'Backlog.Row.ACME-122',
        'Backlog.Row.ACME-131',
      ]),
    )
    refetch.settle(ROWS)
  })

  it('refuses to move the top row up and the bottom row down, and says why', async () => {
    // Disabled with a reason, not hidden: a control that vanishes on the first row is one a reader
    // has to discover twice, and the position it would move to does not exist.
    renderView()

    await screen.findByTestId('Backlog.Row.ACME-121')
    expect(screen.getByRole('button', { name: 'Move ACME-121 up' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move ACME-121 up' })).toHaveAttribute(
      'title',
      'This is already the top of the backlog.',
    )
    expect(screen.getByRole('button', { name: 'Move ACME-131 down' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move ACME-121 down' })).toBeEnabled()
  })

  it('gives every row both affordances', async () => {
    // The mockup's panel line is a promise about every row, not about the screen: "drag the ⠿
    // handle, or use ↑ / ↓ — both persist".
    renderView()

    const row = await screen.findByTestId('Backlog.Row.ACME-122')
    expect(within(row).getByRole('button', { name: 'Drag ACME-122' })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Move ACME-122 up' })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Move ACME-122 down' })).toBeInTheDocument()
    expect(screen.getByTestId('Backlog.Hint')).toHaveTextContent('both persist')
  })

  it('disables both affordances for a caller without board:rank, with the reason on them', async () => {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read'])
      if (/^\/projects\/[^/]+\/backlog/.test(path)) return ROWS
      if (/^\/projects\/[^/]+\/labels$/.test(path)) return []
      if (/^\/projects\/[^/]+\/members$/.test(path)) return []
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    render(
      <TestProviders client={createTestQueryClient()} route="/projects/p1/work?view=backlog">
        <Routes>
          <Route path="/projects/:projectId/work" element={<BacklogView projectId="p1" />} />
        </Routes>
      </TestProviders>,
    )

    await screen.findByTestId('Backlog.Row.ACME-122')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Drag ACME-122' })).toBeDisabled(),
    )
    expect(screen.getByRole('button', { name: 'Move ACME-122 up' })).toBeDisabled()
  })
})

describe('the bulk bar', () => {
  it('does not offer Move to release, which is M3’s', async () => {
    // D-f: `release` is M3's table and `issue_fact.release_id` is NULL until then, so the control
    // would name a field nothing writes.
    renderView()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('checkbox', { name: 'Select ACME-121' }))

    expect(await screen.findByTestId('Backlog.Bulk')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Move to release/i)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Set epic')).toBeInTheDocument()
    expect(screen.getByLabelText('Assign')).toBeInTheDocument()
  })

  it('arrives with the selection and leaves with it', async () => {
    // A contextual toolbar, not a permanently-rendered row of controls disabled most of the time.
    renderView()
    const user = userEvent.setup()

    await screen.findByTestId('Backlog.Row.ACME-121')
    expect(screen.queryByTestId('Backlog.Bulk')).not.toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Select ACME-121' }))
    expect(await screen.findByTestId('Backlog.Bulk.Count')).toHaveTextContent('1 issue selected')

    await user.click(screen.getByTestId('Backlog.Bulk.Clear'))
    expect(screen.queryByTestId('Backlog.Bulk')).not.toBeInTheDocument()
  })

  it('refuses Set epic over a task, before the request', async () => {
    /*
      The contract's depth rule answers 400 for "a parent more than one level above the child": a
      Task's parent is a Story, so setting an epic on one is a guaranteed rejection. `typeLevel` is
      on the row, so this is `ui-rules`' disable-don't-render-then-reject rather than a guess.
    */
    renderView([
      aBacklogRow({ key: 'ACME-126', title: 'Validate the postcode', typeLevel: 'TASK', typeName: 'Task' }),
    ])
    const user = userEvent.setup()

    await user.click(await screen.findByRole('checkbox', { name: 'Select ACME-126' }))

    const picker = await screen.findByLabelText('Set epic')
    await waitFor(() => expect(picker).toBeDisabled())
    expect(picker).toHaveAttribute(
      'title',
      "A task's parent is a story, not an epic — ACME-126 is a task.",
    )
    // Assign is untouched: an assignee is legal at every level.
    expect(screen.getByLabelText('Assign')).toBeEnabled()
  })

  it('applies the chosen epic to every selected row, one PATCH each', async () => {
    // There is no bulk endpoint over issues, so a selection of two is two `PATCH`es. That is the
    // deviation worth seeing in a test rather than only in a comment.
    renderView()
    // `PATCH /issues/{key}` answers with the whole `Issue`, and the contract double compares it.
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(anIssue({ key: 'ACME-121' }) as never)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('checkbox', { name: 'Select ACME-121' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select ACME-122' }))
    await user.selectOptions(await screen.findByLabelText('Set epic'), 'ACME-101')

    await waitFor(() => expect(patch).toHaveBeenCalledTimes(2))
    expect(patch).toHaveBeenCalledWith(
      '/issues/ACME-121',
      { parentKey: 'ACME-101' },
      expect.anything(),
    )
    expect(patch).toHaveBeenCalledWith(
      '/issues/ACME-122',
      { parentKey: 'ACME-101' },
      expect.anything(),
    )
  })

  it('reports a mixed outcome per item and keeps the failures selected', async () => {
    /*
      A blanket success toast over a partial failure is the worse half of the defect — the reader
      believes both went through — and a blanket failure hides the one that worked. So the bar names
      the row that failed and leaves it selected, which is what makes "try again" mean the one that
      did not rather than both.
    */
    renderView()
    vi.spyOn(api, 'patch').mockImplementation((async (path: string) => {
      if (path.includes('ACME-122')) throw new ApiError(404, 'No such issue.')
      return anIssue({ key: 'ACME-121' })
    }) as never)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('checkbox', { name: 'Select ACME-121' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select ACME-122' }))
    await user.selectOptions(await screen.findByLabelText('Set epic'), 'ACME-101')

    const report = await screen.findByTestId('Backlog.Bulk.Report.Text')
    expect(report).toHaveTextContent('1 of 2 updated')
    expect(report).toHaveTextContent('ACME-122')
    expect(report).not.toHaveTextContent('ACME-121 (')
    // Still selected, and only it.
    expect(screen.getByTestId('Backlog.Bulk.Count')).toHaveTextContent('1 issue selected')
    expect(screen.getByRole('checkbox', { name: 'Select ACME-122' })).toBeChecked()
  })
})

describe('the filter and the empty states', () => {
  it('sends the board’s parameter set, never the table’s', async () => {
    // D-v: `statusId` is the table's and is a 400 here. The filter is one URL and three endpoints,
    // and `useWorkFilter` hands each view the subset its own endpoint declares.
    const paths: string[] = []
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      paths.push(path)
      if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read', 'board:rank'])
      if (/^\/projects\/[^/]+\/backlog/.test(path)) return ROWS
      if (/^\/projects\/[^/]+\/labels$/.test(path)) return [anIssueLabel({ id: 'lab-1', name: 'checkout' })]
      if (/^\/projects\/[^/]+\/members$/.test(path)) return []
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    render(
      <TestProviders
        client={createTestQueryClient()}
        route="/projects/p1/work?view=backlog&labelId=lab-1&statusId=st-1&level=STORY"
      >
        <Routes>
          <Route path="/projects/:projectId/work" element={<BacklogView projectId="p1" />} />
        </Routes>
      </TestProviders>,
    )

    await screen.findByTestId('Backlog.Row.ACME-121')
    const request = paths.find((path) => path.includes('/backlog'))
    expect(request).toContain('labelId=lab-1')
    expect(request).toContain('level=STORY')
    expect(request).not.toContain('statusId')
  })

  it('chips a filter it has no picker for, so a reader who narrowed elsewhere can get back off it', async () => {
    renderView(ROWS, '/projects/p1/work?view=backlog&level=STORY&epicKey=ACME-1')

    expect(await screen.findByTestId('Backlog.Filters.Applied.level:STORY')).toHaveTextContent(
      'Level · Story',
    )
    expect(screen.getByTestId('Backlog.Filters.Applied.epic:ACME-1')).toHaveTextContent(
      'Epic · ACME-1',
    )
    // No picker for either — B3's backlog asks for label, assignee and priority, and §12.2's rule
    // is that the strip's height is bounded by the reader's own choices.
    expect(screen.queryByLabelText('Level')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument()
  })

  it('offers the way back off a list a filter emptied', async () => {
    renderView([], '/projects/p1/work?view=backlog&labelId=lab-1')

    const empty = await screen.findByTestId('Backlog.Content.Empty')
    expect(within(empty).getByRole('button', { name: /clear/i })).toBeInTheDocument()
  })

  it('says what an unfiltered empty backlog means, rather than offering a filter to clear', async () => {
    // Empty under no filter is finished work, not a dead end — and there is nothing to clear.
    renderView([])

    const empty = await screen.findByTestId('Backlog.Content.Empty')
    expect(empty).toHaveTextContent('Nothing left in the backlog')
    expect(within(empty).queryByRole('button', { name: /clear/i })).not.toBeInTheDocument()
  })

  it('keeps the panel head above the region, so a read that returns nothing still names the list', async () => {
    renderView([])

    expect(await screen.findByTestId('Backlog.Count')).toHaveTextContent('0 issues in rank order')
    // The drag instruction is not rendered over an empty list — it would contradict the state
    // right below it.
    expect(screen.queryByTestId('Backlog.Hint')).not.toBeInTheDocument()
  })
})
