import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { render, renderHook, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Toaster } from '../../components/ui'
import { aBacklogRow, aBoardIssueCard, aCardMoveResult, aRankResult } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { ApiError, api } from '../client'
import { keys } from './keys'
import { reorder, useMoveCard, useRankIssue } from './projectBoards'

/**
 * The **optimistic split** — the one asymmetry in this module, and the part of it that can be
 * silently wrong (012 D-c).
 *
 * `useMoveCard` is not optimistic because §12.4 forbids it and `resolveColumnMove` has already
 * refused what the server would refuse. `useRankIssue` is, because the server owns the neighbours
 * and there is nothing to check first. Three behaviours follow from that and none of them can be
 * reached by using the app, which is why `tcmsFaults.rank()` landed in the same task:
 *
 *  1. the row moves before the round trip,
 *  2. a **409 snaps it back** — the rollback M2 earned and M1 deliberately did not write,
 *  3. `rebalanced: true` **drops** the optimistic order instead of rolling it back, because the
 *     placement succeeded and it is every *other* row's rank that is now stale.
 *
 * The third is the one that is easy to get wrong in both directions, and a suite that only tested
 * the failure would pass on a client that treated a respace as one.
 *
 * Driven through the hooks over a seeded cache rather than through a screen: nothing renders in this
 * task by design, and the claims above are about the cache rather than about markup.
 */

const PROJECT = 'p1'
const BOARD = 'b1'

/** Three rows in rank order — the list a drag reorders, and the order a rollback must restore. */
function threeRows() {
  return [
    aBacklogRow({ key: 'ACME-1', position: 1, rank: '1024' }),
    aBacklogRow({ key: 'ACME-2', position: 2, rank: '2048' }),
    aBacklogRow({ key: 'ACME-3', position: 3, rank: '3072' }),
  ]
}

describe('reorder — the optimistic order, and nothing else', () => {
  it('places a row after its neighbour and renumbers the positions it moved', () => {
    /*
      `position` is the 1-based number the ROW SHOWS, derived from rank order and never stored.
      Leaving it alone would draw the moved row with its old number beside its new place, which is
      the optimistic patch contradicting itself on screen.
    */
    const next = reorder(threeRows(), { key: 'ACME-1', afterKey: 'ACME-3' })

    expect(next.map((row) => row.key)).toEqual(['ACME-2', 'ACME-3', 'ACME-1'])
    expect(next.map((row) => row.position)).toEqual([1, 2, 3])
  })

  it('places a row before its neighbour, which is how the TOP is reached', () => {
    // D-q: "neither neighbour" is a 400, not "to the top". The top is `beforeKey: <the first row>`,
    // so this is the only spelling of that gesture and it has to work.
    const next = reorder(threeRows(), { key: 'ACME-3', beforeKey: 'ACME-1' })

    expect(next.map((row) => row.key)).toEqual(['ACME-3', 'ACME-1', 'ACME-2'])
  })

  it('leaves rank alone, because the server owns the midpoint', () => {
    // The client cannot compute one: `rank` is a decimal string the contract forbids arithmetic on,
    // and a fabricated value would sort the list by a number the server never had.
    const next = reorder(threeRows(), { key: 'ACME-1', afterKey: 'ACME-2' })

    expect(next.map((row) => row.rank)).toEqual(['2048', '1024', '3072'])
  })

  it('returns the list unchanged when the placement names a row it does not hold', () => {
    // The drag was against a different filter's list. Inventing a position in this one would be a
    // reorder nobody asked for, and one the refetch would then silently correct.
    const rows = threeRows()
    expect(reorder(rows, { key: 'ACME-1', afterKey: 'ACME-9' }).map((row) => row.key)).toEqual(
      rows.map((row) => row.key),
    )
  })
})

describe('useRankIssue — optimistic, with the rollback that earns it', () => {
  let client: QueryClient

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }

  /** The keys the drag targets, in the order the cache holds them. */
  function order(): string[] {
    return (client.getQueryData<ReturnType<typeof threeRows>>(keys.backlog(PROJECT, {})) ?? []).map(
      (row) => row.key,
    )
  }

  beforeEach(() => {
    client = createTestQueryClient()
    client.setQueryData(keys.backlog(PROJECT, {}), threeRows())
  })

  it('moves the row before the round trip answers', async () => {
    /*
      The whole point of the optimism. Without it the row hangs where it was for a round trip and
      then jumps, which reads worse than a row that moves and, rarely, snaps back saying why — and
      unlike a status drag there is nothing the client could have checked to avoid the failure.

      The write is held open so the assertion lands while it is still in flight; resolving it after
      would make this indistinguishable from a test of the refetch.
    */
    let land = (_result: unknown) => {}
    vi.spyOn(api, 'put').mockReturnValue(new Promise((resolve) => { land = resolve }) as never)

    const { result } = renderHook(() => useRankIssue(PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', afterKey: 'ACME-3' })

    await waitFor(() => expect(order()).toEqual(['ACME-2', 'ACME-3', 'ACME-1']))

    // Settled before the test returns, so the write is not still in flight when the harness drains
    // — a body compared after `afterEach` would be reported against whatever test ran next.
    land(aRankResult({ key: 'ACME-1' }))
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('snaps the row back when the rank write conflicts', async () => {
    /*
      The failed write IS the subject: a 409 from `PUT /issues/{key}/rank` is the
      concurrent-neighbour case, and the optimistic patch must be reverted rather than left showing
      an order the server refused. Unreachable in an ordinary session — it needs a second person to
      move the neighbour inside one drag, which is what `tcmsFaults.rank('conflict')` exists for.
    */
    drivingAFailedWrite()
    vi.spyOn(api, 'put').mockRejectedValue(
      new ApiError(409, 'The issue you dropped this next to was moved by somebody else.', null,
        null, null, 'Someone else saved first'),
    )

    const { result } = renderHook(() => useRankIssue(PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', afterKey: 'ACME-3' })

    await waitFor(() => expect(result.current.isError).toBe(true))
    // As it was before the drag. The rows are read back rather than the mutation's state, because a
    // rollback that set the error and left the patch in place would satisfy any assertion on it.
    expect(order()).toEqual(['ACME-1', 'ACME-2', 'ACME-3'])
  })

  it('restores every filter’s list, not just the one the drag was on', async () => {
    /*
      The rollback targets `keys.backlogRoot`, and this is why. A drag on a filtered list reorders
      rows in the unfiltered one too — both were patched by `setQueriesData` on the same prefix — so
      a rollback that restored only the list on screen would leave the other holding an order the
      server refused, with nothing to say so until something else refetched.
    */
    // The failed write is the subject again — the same 409, asserted over two cached filters
    // rather than one, because the rollback's reach is the thing under test here.
    drivingAFailedWrite()
    const filtered = keys.backlog(PROJECT, { level: 'STORY' })
    client.setQueryData(filtered, threeRows())
    vi.spyOn(api, 'put').mockRejectedValue(new ApiError(409, 'Moved by somebody else.'))

    const { result } = renderHook(() => useRankIssue(PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-3', beforeKey: 'ACME-1' })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(client.getQueryData<ReturnType<typeof threeRows>>(filtered)?.map((row) => row.key))
      .toEqual(['ACME-1', 'ACME-2', 'ACME-3'])
  })

  it('drops the optimistic order rather than keeping it when the server rebalanced', async () => {
    /*
      `rebalanced: true` is a **success**, and both ways of getting it wrong are one line apart.
      After a respace every other row's rank is stale, so the optimistic order is no longer a
      prediction of anything: keeping the patch shows an order the server does not have, and
      treating the answer as a failure snaps back a row the server did move.

      What the handler must do is invalidate — so the assertion is on the list being marked stale,
      which is what makes the next read go to the server, and NOT on the rows, which a rollback and
      a refetch would both eventually change.
    */
    vi.spyOn(api, 'put').mockResolvedValue(
      aRankResult({ key: 'ACME-1', rank: '2048', rebalanced: true }) as never,
    )

    const { result } = renderHook(() => useRankIssue(PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', afterKey: 'ACME-3' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(client.getQueryState(keys.backlog(PROJECT, {}))?.isInvalidated).toBe(true)
    // And it is not a rollback: the row the server moved is still where the drag put it.
    expect(order()).toEqual(['ACME-2', 'ACME-3', 'ACME-1'])
  })

  it('sends exactly one neighbour, and the union is what makes the other payloads unwritable', async () => {
    /*
      D-q. Both neighbours and neither are each a 400, and T4 landed it that way against a stale
      `RankInput.afterKey` description that still says an empty body means "to the top". The type is
      a union so a caller cannot construct either refused payload; this pins that the hook does not
      then put the absent member on the wire as an explicit null, which is a body the server reads
      as "both given".
    */
    const put = vi.spyOn(api, 'put').mockResolvedValue(aRankResult() as never)

    const { result } = renderHook(() => useRankIssue(PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', beforeKey: 'ACME-2' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(put.mock.calls[0][1]).toEqual({ beforeKey: 'ACME-2' })
  })
})

describe('useMoveCard — not optimistic, and the breach it reports', () => {
  let client: QueryClient

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }

  beforeEach(() => {
    client = createTestQueryClient()
  })

  it('leaves the board alone until the server answers', async () => {
    /*
      Scope §12.4, verbatim and binding: *"A drop that `issue_transition` forbids is refused at the
      drag, with the reason on screen. It is never accepted optimistically and reverted."* The
      legality is on the card, so `resolveColumnMove` has already refused what the server would —
      which leaves an optimistic patch here buying nothing except the failure mode that sentence
      rules out.

      Asserted the only way it can be: with the write held open, the cached cards are untouched.
    */
    const cards = keys.boardCards(BOARD, {})
    client.setQueryData(cards, { boardId: BOARD, swimlaneBy: 'NONE', lanes: [], breaches: [] })

    let land = (_result: unknown) => {}
    vi.spyOn(api, 'post').mockReturnValue(new Promise((resolve) => { land = resolve }) as never)

    const { result } = renderHook(() => useMoveCard(BOARD, PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', toColumnId: 'col-2' })

    await waitFor(() => expect(result.current.isPending).toBe(true))
    expect(client.getQueryData(cards)).toEqual({
      boardId: BOARD,
      swimlaneBy: 'NONE',
      lanes: [],
      breaches: [],
    })
    land(aCardMoveResult())
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('invalidates EVERY board’s cards, not only the one the card was dragged on', async () => {
    /*
      The module's done-criterion — *"a card moved on the team board is in the matching column on the
      stakeholder board"* — is about two boards over one project (D-a's second column). They show
      the same statuses (D2), and `staleTime` is 30 s, so a sweep of this board alone would serve the
      order from before the move to anybody who switched boards inside half a minute.
    */
    const other = keys.boardCards('b2', {})
    client.setQueryData(other, { boardId: 'b2', swimlaneBy: 'NONE', lanes: [], breaches: [] })
    vi.spyOn(api, 'post').mockResolvedValue(aCardMoveResult() as never)

    const { result } = renderHook(() => useMoveCard(BOARD, PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', toColumnId: 'col-2' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(client.getQueryState(other)?.isInvalidated).toBe(true)
  })

  it('says nothing when the column is at its limit rather than over it', async () => {
    // A column at exactly its limit is NOT in breach, and the server sends no `breach` for one. The
    // off-by-one lives in the middle of three states, so the absence is asserted rather than assumed.
    vi.spyOn(api, 'post').mockResolvedValue(aCardMoveResult() as never)
    render(<Toaster />)

    const { result } = renderHook(() => useMoveCard(BOARD, PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', toColumnId: 'col-2' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(screen.queryByTestId('Toast.warning')).not.toBeInTheDocument()
  })

  it('reports a WIP breach as a warning, because the move still landed', async () => {
    /*
      Story B3: *"the drop is accepted and the column header shows the breach"*. So this is a
      **warning** on a 200, never an error — a limit that refused drops is one people route around
      by not using the board. `tcmsFaults.wip()` is what makes this reachable by hand; nothing else
      does short of dragging four cards into one column.
    */
    vi.spyOn(api, 'post').mockResolvedValue(
      aCardMoveResult({
        card: aBoardIssueCard({ key: 'ACME-1', columnId: 'col-2' }),
        breach: { columnId: 'col-2', limit: 3, count: 4 },
      }) as never,
    )
    render(<Toaster />)

    const { result } = renderHook(() => useMoveCard(BOARD, PROJECT), { wrapper })
    result.current.mutate({ key: 'ACME-1', toColumnId: 'col-2' })

    expect(await screen.findByTestId('Toast.warning')).toHaveTextContent(/4 of a 3 limit/)
    expect(screen.getByTestId('Toast.warning')).toHaveTextContent(/reported, not enforced/)
  })
})
