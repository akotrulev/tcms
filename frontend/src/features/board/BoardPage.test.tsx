import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { BoardCard, IssueStatusRef } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { anIssue } from '../../test/fixtures'
import { Board } from './BoardPage'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * The board had **zero tests** until §53, and that is why this file leads with the reason.
 *
 * The whole status vocabulary was rewritten in that slice — every column, every comparison, every
 * `data-testid` — and all 327 existing tests stayed green throughout, because not one of them
 * touched this screen. A suite that cannot go red on a change that large is not covering it.
 *
 * What is worth pinning here is the part that is *logic* rather than markup:
 *
 * 1. **Cards are filed by the status they state, and the columns are assembled from them.**
 *    ~~Before §53 the board compared `item.status` against display text while the contract typed
 *    it as an unconstrained string.~~ → 011 D-c went further: there is no status *enum* at all
 *    now, because a project names its own. The failure this guards is the same one in its new
 *    spelling — a card returned by the API that lands in no column, invisible, with nothing
 *    failing.
 * 2. **A derived card cannot be moved, and the refusal says where to go instead.** This is §6l's
 *    central invariant as the user meets it. A refusal that only says "no" is a wall.
 * 3. **`allowedTransitions` is honoured before the request, not after it.** A card that appears to
 *    move and then snaps back on the next refetch is the failure the pre-check exists to prevent.
 */

const READY: IssueStatusRef = { id: 's-ready', name: 'Ready', kind: 'NOT_STARTED', position: 0, blocking: false }
const PROGRESS: IssueStatusRef = { id: 's-prog', name: 'In Progress', kind: 'IN_PROGRESS', position: 1, blocking: false }
const REVIEW: IssueStatusRef = { id: 's-rev', name: 'In Review', kind: 'IN_PROGRESS', position: 2, blocking: false }
const DONE: IssueStatusRef = { id: 's-done', name: 'Done', kind: 'DONE', position: 3, blocking: false }

/**
 * A card, defaults plus overrides — and **no `as BoardCard`**, which is §79's repair applied here.
 *
 * That cast turned a new required field on the contract into a runtime failure in eleven tests
 * instead of one compile error (`ReviewPanel.test.tsx`, §79), and §80 measured what removing it
 * was worth one slice later. The base object is typed on its own line so the compiler checks it
 * against the generated type; spreading the partial over it cannot remove a field.
 */
const card = (partial: Partial<BoardCard> & Pick<BoardCard, 'id' | 'title'>): BoardCard => {
  const base: BoardCard = {
    id: partial.id,
    title: partial.title,
    derived: false,
    sourceType: null,
    sourceId: null,
    kind: 'ISSUE',
    description: null,
    status: { name: READY.name, kind: READY.kind },
    priority: 'MEDIUM',
    dueDate: null,
    overdue: false,
    projectId: 'p1',
    projectKey: 'PRJ',
    linkedKey: null,
    allowedTransitions: [PROGRESS, REVIEW, DONE],
  }
  return { ...base, ...partial }
}

/**
 * `projectId` and `mine` are gone from `Board` as of §83 and the links below did not move a
 * character — because every card this factory builds carries `projectId: 'p1'`, which is where
 * `linkFor` reads it from now. That equality is exactly what made the old bug invisible: on a
 * project board the route's project and the card's project are always the same value.
 */
function renderBoard(items: BoardCard[], crossProject = false) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <Board items={items} crossProject={crossProject} />
    </TestProviders>,
  )
}

describe('Board columns', () => {
  it('files each card into the column matching the status it states', () => {
    renderBoard([
      card({ id: 'PRJ-1', title: 'Waiting' }),
      card({ id: 'PRJ-2', title: 'Underway', status: { name: 'In Progress', kind: 'IN_PROGRESS' } }),
      card({ id: 'PRJ-3', title: 'Checking', status: { name: 'In Review', kind: 'IN_PROGRESS' } }),
      card({ id: 'PRJ-4', title: 'Finished', status: { name: 'Done', kind: 'DONE' } }),
    ])

    // Asserted per column rather than "all four render": a card whose status matches no column
    // renders nowhere at all, and a test that only counted cards would pass on an empty board.
    expect(within(screen.getByTestId('Board.Column.Ready')).getByText('Waiting')).toBeTruthy()
    expect(within(screen.getByTestId('Board.Column.In Progress')).getByText('Underway')).toBeTruthy()
    expect(within(screen.getByTestId('Board.Column.In Review')).getByText('Checking')).toBeTruthy()
    expect(within(screen.getByTestId('Board.Column.Done')).getByText('Finished')).toBeTruthy()
  })

  it('draws a column a card can only MOVE to, so a board is not one column wide on day one', () => {
    /*
      A project whose issues are all in Ready still has four columns, because every card names
      where it may go. ~~The columns were a constant, so this was free.~~ → D-c made it a
      derivation, and this is the assertion that keeps it honest: a board that drew only the
      statuses cards *hold* would show one column and no destination to drag into.
    */
    renderBoard([card({ id: 'PRJ-1', title: 'Only one' })])

    for (const name of ['Ready', 'In Progress', 'In Review', 'Done']) {
      expect(screen.getByTestId(`Board.Column.${name}`)).toBeTruthy()
    }
  })

  it('shows the project’s own words for a status, not a code', () => {
    // The column heading is the name the project chose. There is no label map behind it any more
    // — D-c's whole point is that a five-member enum could not spell a per-project status.
    renderBoard([card({ id: 'PRJ-1', title: 'Waiting' })])

    expect(within(screen.getByTestId('Board.Column.Ready')).getByText('Ready')).toBeTruthy()
  })
})

describe('Moving a card', () => {
  it('refuses a derived card and names the action that works', async () => {
    /*
      **This test used to drive `fireEvent.dragStart` + `drop`, and §106 took that away.** The
      board's native HTML5 drag became a `@dnd-kit` one, which cannot be driven in jsdom at all —
      so the drag path's refusal is pinned directly in `cardMove.test.ts`, and what is asserted
      *here* is the same sentence on the surface a user can still reach without a pointer.

      ~~A disabled menu item carrying the refusal.~~ → **superseded by D-c.** The menu is now the
      card's OWN legal set rather than every column minus this one, because a cross-project board
      draws the union of several workflows and offering another project's status would be a
      destination the server has never heard of. A card with no legal move therefore renders its
      trigger **disabled with the reason beside it** — the same rule, moved from the item to the
      control, and readable without a hover (`disabled:pointer-events-none` means no tooltip on a
      disabled button ever opens, which is what `WorkCard`'s comment spent a paragraph on).
    */
    renderBoard([
      card({
        id: 'execution:abc',
        title: 'Run the refund case',
        derived: true,
        kind: 'EXECUTION',
        sourceType: 'EXECUTION',
        linkedKey: 'TC-42',
        status: { name: 'In Progress', kind: 'IN_PROGRESS' },
        allowedTransitions: [],
      }),
    ])

    const refusal = screen.getByTestId('Board.Card.Immobile').textContent
    expect(refusal).toContain('an execution')
    // The sentence has to point somewhere. "You can't do that" leaves the user with a card they
    // believe is broken rather than one that is following its source.
    expect(refusal).toContain('follows')
  })

  it('disables the move control rather than hiding it when nothing is allowed', () => {
    // Disabled, not absent — §31's disable-don't-hide. An absent control teaches that the board
    // cannot move cards at all, which is what a reader would then believe of every card.
    renderBoard([
      card({
        id: 'execution:abc',
        title: 'Run it',
        derived: true,
        kind: 'EXECUTION',
        allowedTransitions: [],
      }),
    ])

    expect(screen.getByTestId('Board.Card.Move')).toBeDisabled()
  })

  it('offers exactly the moves the card states, and never its own column', async () => {
    renderBoard([card({ id: 'PRJ-1', title: 'Movable', allowedTransitions: [PROGRESS, DONE] })])

    await userEvent.click(screen.getByTestId('Board.Card.Move'))
    expect(await screen.findByTestId('Board.Card.MoveTo.In Progress')).toBeInTheDocument()
    expect(await screen.findByTestId('Board.Card.MoveTo.Done')).toBeInTheDocument()
    // Never a destination back to the column it is already in — it would do nothing and read as
    // broken. The server does not offer it, which is what makes this a contract assertion.
    expect(screen.queryByTestId('Board.Card.MoveTo.Ready')).toBeNull()
  })

  it('keeps WCAG 2.2 satisfied — every destination reachable without a drag', async () => {
    // The property the shape change must not cost. Audit 4.2 records this board as the product's
    // only conformant answer to 2.2's drag rule; a menu satisfies it exactly as four buttons did.
    renderBoard([card({ id: 'PRJ-1', title: 'Movable' })])

    await userEvent.click(screen.getByTestId('Board.Card.Move'))
    for (const name of ['In Progress', 'In Review', 'Done']) {
      expect(await screen.findByTestId(`Board.Card.MoveTo.${name}`)).toBeInTheDocument()
    }
  })
})

describe('Why a card is on the board', () => {
  it('renders a maintenance card’s reason, labelled', () => {
    // The defect the live pass found, and it was invisible to every gate: the server has always
    // sent `description` and the backend test asserts it, but `WorkCard` never rendered the field.
    // On screen a maintenance card was its case title and a chip, directly above an EXECUTION card
    // with the *same title* — two cards, the same words, neither explaining itself.
    renderBoard([
      card({
        id: 'case:abc',
        title: 'Full refund to card',
        derived: true,
        kind: 'CASE_MAINTENANCE',
        description: 'NEVER_EXECUTED',
        linkedKey: 'TC-1',
        allowedTransitions: [],
      }),
    ])

    // Labelled through the same map the dashboard uses, not the raw signal name — one value must
    // not read two ways across two screens.
    expect(screen.getByTestId('Board.Card.Reason').textContent).toBe('Never executed')
  })

  it('labels every signal when a case raises more than one', () => {
    renderBoard([
      card({
        id: 'case:abc',
        title: 'Full refund to card',
        derived: true,
        kind: 'CASE_MAINTENANCE',
        description: 'NEVER_EXECUTED, STALE_APPROVAL',
        allowedTransitions: [],
      }),
    ])

    const reason = screen.getByTestId('Board.Card.Reason').textContent
    expect(reason).toContain('Never executed')
    expect(reason).toContain('Approved before its last edit')
  })

  it('passes an unrecognised signal through rather than dropping it', () => {
    // A signal the SPA has no word for yet is still a reason. Dropping it silently would put the
    // card back in exactly the state this rendering exists to fix.
    renderBoard([
      card({
        id: 'case:abc',
        title: 'Something',
        derived: true,
        kind: 'CASE_MAINTENANCE',
        description: 'A_SIGNAL_FROM_THE_FUTURE',
        allowedTransitions: [],
      }),
    ])

    expect(screen.getByTestId('Board.Card.Reason').textContent).toBe('A_SIGNAL_FROM_THE_FUTURE')
  })

  it('shows an execution card’s run without treating it as a signal', () => {
    renderBoard([
      card({
        id: 'execution:abc',
        title: 'Full refund to card',
        derived: true,
        kind: 'EXECUTION',
        description: 'Execute in Regression 1.0',
        allowedTransitions: [],
      }),
    ])

    expect(screen.getByTestId('Board.Card.Reason').textContent).toBe('Execute in Regression 1.0')
  })
})

describe('Where a card’s key goes (§81)', () => {
  /**
   * The reviewer's inbox is the first generator that draws cards about **suites and plans**, and
   * every card used to route to the case list. A review of `TP-5` sent to a case search finds
   * nothing and renders "no cases match", which reads as the plan having been deleted — the
   * failure is silent, on the one affordance the card exists to offer.
   */
  const linkOf = (item: BoardCard) => {
    renderBoard([item])
    return screen.getByTestId('Board.Card.LinkedKey').getAttribute('href')
  }

  it('sends a review of a case to the case, where §6g’s panel is', () => {
    // Not a search for the key: the panel lives on the artifact's page, so navigating to the
    // artifact IS navigating to the review — which is why the server puts the ARTIFACT in
    // sourceType/sourceId rather than the review, whose URL does not exist.
    expect(
      linkOf(
        card({
          id: 'review:r1:u1',
          title: 'Cart survives a page reload',
          derived: true,
          kind: 'REVIEW',
          sourceType: 'CASE',
          sourceId: 'case-1',
          linkedKey: 'TC-20',
          allowedTransitions: [],
        }),
      ),
    ).toBe('/projects/p1/cases/case-1')
  })

  it('sends a review of a plan to the plan', () => {
    expect(
      linkOf(
        card({
          id: 'review:r2:u1',
          title: 'Payments hardening cycle',
          derived: true,
          kind: 'REVIEW',
          sourceType: 'PLAN',
          sourceId: 'plan-5',
          linkedKey: 'TP-5',
          allowedTransitions: [],
        }),
      ),
    ).toBe('/projects/p1/plans/plan-5')
  })

  it('sends a review of a suite to the case list filtered to it, having no page of its own', () => {
    // The one subject type §6g can review that has no screen. The suite tree is the case list's
    // left pane, so the filter is the closest thing to a suite page that exists.
    expect(
      linkOf(
        card({
          id: 'review:r3:u1',
          title: 'Refunds',
          derived: true,
          kind: 'REVIEW',
          sourceType: 'SUITE',
          sourceId: 'suite-9',
          linkedKey: 'TS-4',
          allowedTransitions: [],
        }),
      ),
    ).toBe('/projects/p1/cases?suiteId=suite-9')
  })

  it('still searches for an execution card, whose route needs a run id the card lacks', () => {
    expect(
      linkOf(
        card({
          id: 'execution:e1',
          title: 'Full refund to card',
          derived: true,
          kind: 'EXECUTION',
          sourceType: 'EXECUTION',
          sourceId: 'exec-1',
          linkedKey: 'TC-3',
          allowedTransitions: [],
        }),
      ),
    ).toBe('/projects/p1/cases?q=TC-3')
  })

  it('sends an ISSUE card to the issue’s own page, whose key IS the card id (011)', () => {
    /*
      The arm M1 added, and the one that could not exist before it: a row-backed card had no page,
      so it fell through to a case search that could never have found it. `/issues/:key` needs no
      project segment — a key is unique in the only scope RLS lets a caller see.
    */
    renderBoard([card({ id: 'PRJ-118', title: 'Guest checkout', linkedKey: 'PRJ-118' })])
    expect(screen.getByTestId('Board.Card.LinkedKey').getAttribute('href')).toBe('/issues/PRJ-118')
  })
})

describe('Card ordering', () => {
  it('puts overdue cards above higher-priority ones that are not', () => {
    // The sort is overdue-first THEN priority. A critical card that is not yet late must not bury
    // a medium one that already is — which is the ordering people actually act on.
    renderBoard([
      card({ id: 'PRJ-1', title: 'Critical but on time', priority: 'CRITICAL' }),
      card({ id: 'PRJ-2', title: 'Medium and late', priority: 'MEDIUM', overdue: true }),
    ])

    const titles = within(screen.getByTestId('Board.Column.Ready'))
      .getAllByTestId('Board.Card.Title')
      .map((node) => node.textContent)

    expect(titles).toEqual(['Medium and late', 'Critical but on time'])
  })
})

/**
 * The write.
 *
 * ~~Optimistic, and therefore rolling back — the two things a drag made necessary that a button
 * did not (§106).~~ → **superseded by 011.** `PATCH /work-items/{id}` is gone; a move is
 * `POST /issues/{key}/transitions`, and it is **not** optimistic: a transition can be refused by
 * the workflow, and showing the card in a column the server is about to reject is precisely the
 * "snapping back in silence" story H5 rules out. M2 rebuilds the board and gets the optimism back
 * there, where a *drag* makes the rollback machinery worth its cost.
 *
 * What must not regress is the half that was never about optimism: **the refusal persists and it
 * is the server's own sentence**, because the 409 the workflow answers with names the legal moves.
 */
/**
 * 021 R9 / D-n — the composite board's project-scoped screen is gone, and the renderer is not.
 *
 * Two facts, and neither is provable from the other: a module that still exported `BoardPage`
 * would compile and route nowhere, and a module that had deleted `Board` would take
 * `/teams/:teamId/board` with it. `routeMeta.test.ts` proves the route itself is gone by deriving
 * the key set from `router.tsx`; this proves the module's own shape.
 */
describe('What retiring the composite board did and did not remove', () => {
  it('no longer exports a project-scoped page, and still exports the renderer', async () => {
    const module_ = (await import('./BoardPage')) as Record<string, unknown>

    expect(module_.BoardPage).toBeUndefined()
    expect(typeof module_.Board).toBe('function')
  })
})

describe('Moving a card, and what a refusal says', () => {
  const READY_CARD = card({ id: 'PRJ-1', title: 'Reconcile the file' })

  /*
    ~~Rendered `<BoardPage />` at `/projects/p1/board` and answered `GET /board`.~~ →
    **superseded (021 R9, T8): that page and that route are retired**, and the move machinery it
    exercised is `Board`'s — still alive, still `/teams/:teamId/board`'s renderer. So the two
    assertions below are made against the component that owns them rather than through a screen
    that no longer exists. They are the same two assertions; nothing was dropped with the page.
  */
  function renderPage() {
    return renderBoard([READY_CARD])
  }

  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['project:read'])
      return []
    }) as never)
  })

  it('posts the transition with the status id the CARD named', async () => {
    // Not the column's id: on a cross-project board a column may have taken its id from another
    // project's card, and a transition posted with that id names a status this issue's workflow
    // does not contain.
    // The transition answers with an **Issue**, not a card: the board reads the invalidation, not
    // this body. Serving a card here would be a double answering a different endpoint.
    const post = vi.spyOn(api, 'post').mockResolvedValue(anIssue({ key: 'PRJ-1' }) as never)

    renderPage()
    await userEvent.click(await screen.findByTestId('Board.Card.Move'))
    await userEvent.click(await screen.findByTestId('Board.Card.MoveTo.Done'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/issues/PRJ-1/transitions',
        { statusId: 's-done' },
        expect.anything(),
      ),
    )
  })

  it('shows the server’s own refusal, in place, and keeps it on screen', async () => {
    // The refused write is the subject.
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(
      // (status, detail, fieldErrors, correlationId, retryAfter, title) — the board renders
      // `detail`, which names the legal moves rather than the kind of problem.
      new ApiError(
        409,
        'This issue can only move to In Review from where it stands.',
        null,
        null,
        null,
        'The workflow forbids this move',
      ),
    )

    renderPage()
    await userEvent.click(await screen.findByTestId('Board.Card.Move'))
    await userEvent.click(await screen.findByTestId('Board.Card.MoveTo.Done'))

    /*
      **The reason PERSISTS, and it is the server's own sentence.** A toast about a refused move is
      gone before the eye gets back to the card; this alert is still there when it does. `detail`,
      not `title` — "The workflow forbids this move" is the *kind* of problem, "can only move to In
      Review" is what is actually available.

      This is the assertion `tcmsFaults.issue('conflict')` exists to make reachable by hand: the
      picker only ever offers legal moves, so no ordinary session can produce this 409.
    */
    expect((await screen.findByTestId('Board.RejectedMove')).textContent).toContain(
      'can only move to In Review',
    )

    // And the card never left its column, because the move was never applied optimistically.
    expect(within(screen.getByTestId('Board.Column.Ready')).getByTestId('Board.Card')).toBeInTheDocument()
  })
})
