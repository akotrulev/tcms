import { describe, expect, it } from 'vitest'
import type { BoardCard, IssueStatusRef } from '../../api/types'
import { columnsFor, immobileReason, refusalFor, resolveCardMove } from './cardMove'

/**
 * Where a dragged card lands — **the only part of drag-and-drop that can be wrong rather than
 * merely unwired**, and the part a component test cannot reach: driving a real pointer drag
 * through jsdom exercises `@dnd-kit`, and `onDragEnd` is a prop of a context provider.
 *
 * §94 wrote the same test file for `resolveStepMove` and named the reason. This one carries one
 * thing that one did not: a card drag can be **legally refused**, and the refusal must happen at
 * drop time rather than as a 409 the card visibly springs back from.
 *
 * 011 D-c added a second: the columns are no longer a constant. A project names its own statuses,
 * so the board's layout is assembled from the cards, and the failure that would hide behind a
 * fixed five-column array is a card whose status has no column — returned by the API, on nobody's
 * screen, with nothing failing.
 */

const READY: IssueStatusRef = { id: 's-ready', name: 'Ready', kind: 'NOT_STARTED', position: 0 }
const PROGRESS: IssueStatusRef = { id: 's-prog', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 }
const DONE: IssueStatusRef = { id: 's-done', name: 'Done', kind: 'DONE', position: 2 }

function card(partial: Partial<BoardCard> = {}): BoardCard {
  return {
    id: 'OLB-14',
    projectId: 'p1',
    projectKey: 'OLB',
    derived: false,
    sourceType: null,
    sourceId: null,
    kind: 'ISSUE',
    title: 'Reconcile the settlement file',
    description: null,
    status: { name: READY.name, kind: READY.kind },
    priority: 'MEDIUM',
    dueDate: null,
    overdue: false,
    assignee: null,
    linkedKey: null,
    allowedTransitions: [PROGRESS, DONE],
    ...partial,
  }
}

const COLUMNS = columnsFor([card()])

describe('columnsFor — the board lays itself out from the cards', () => {
  it('draws a column for a status a card HOLDS even when nothing may move there', () => {
    /*
      The card's own `status` is a name and a kind with no id and no position — the only place
      `Ready` appears when no card may move back into it. A derivation that used only
      `allowedTransitions` would leave every card in that column with no column to sit in.
    */
    expect(COLUMNS.map((column) => column.name)).toContain('Ready')
  })

  it('orders by kind first, so the backlog is not pushed behind Done', () => {
    /*
      **Position alone put `Ready` last**, and that is the defect this assertion exists for. A
      card's own status carries no position — the contract sends a name and a kind there — so the
      only columns with one are the ones something may move *into*, and the leftmost column of a
      real workflow is usually not a transition target from anywhere. Sorting on position with a
      "unknown goes last" fallback therefore drew Done, then Ready.
    */
    const shuffled = columnsFor([
      card({ id: 'OLB-1', status: { name: DONE.name, kind: DONE.kind }, allowedTransitions: [] }),
      card({ id: 'OLB-2', allowedTransitions: [PROGRESS, DONE] }),
    ])
    expect(shuffled.map((column) => column.name)).toEqual(['Ready', 'In Progress', 'Done'])
  })

  it('orders two statuses of the same kind by the position the project gave them', () => {
    // Position is what it is for: within a kind, the order the project arranged.
    const ordered = columnsFor([
      card({ id: 'OLB-1', allowedTransitions: [{ ...PROGRESS, name: 'In Review', position: 9 }, PROGRESS] }),
    ])
    expect(ordered.map((column) => column.name)).toEqual(['Ready', 'In Progress', 'In Review'])
  })

  it('merges two projects’ identically named statuses into one column', () => {
    /*
      The cross-project board. The two `In Progress` rows are genuinely different ids in two
      workflows, and a reader on My Work wants one column — which is also the only reading
      available, since neither id means anything to the other project.
    */
    const merged = columnsFor([
      card({ id: 'OLB-1', projectKey: 'OLB', allowedTransitions: [PROGRESS] }),
      card({
        id: 'MOB-1',
        projectKey: 'MOB',
        allowedTransitions: [{ ...PROGRESS, id: 'mob-prog' }],
      }),
    ])
    expect(merged.filter((column) => column.name === 'In Progress')).toHaveLength(1)
  })
})

describe('resolveCardMove — the three ways a drag ends in nothing', () => {
  it('does nothing when the card is dropped outside every column', () => {
    // `over` is null. The most common way a drag ends, and the one §94 lists first.
    expect(resolveCardMove(card(), null, COLUMNS)).toEqual({ outcome: 'NONE' })
    expect(resolveCardMove(card(), undefined, COLUMNS)).toEqual({ outcome: 'NONE' })
  })

  it('does nothing when the card is dropped back in the column it came from', () => {
    /*
      The board spelling of §94's `move(i, i)`. There it marked a form dirty; here it would be a
      transition writing an unchanged status — an audit row for a gesture that changed nothing.
      Note the target IS in `allowedTransitions` here, so only the same-column check stops it.
    */
    const item = card({ allowedTransitions: [READY, DONE] })
    expect(resolveCardMove(item, 'Ready', COLUMNS)).toEqual({ outcome: 'NONE' })
  })

  it('does nothing when dropped on an id that is not a column', () => {
    // A stale droppable from a drag that outlived a re-render — `resolveStepMove`'s third arm.
    expect(resolveCardMove(card(), 'OLB-9', COLUMNS)).toEqual({ outcome: 'NONE' })
  })

  it('does nothing when there is no active card', () => {
    expect(resolveCardMove(null, 'Done', COLUMNS)).toEqual({ outcome: 'NONE' })
  })
})

describe('resolveCardMove — the move and the refusal', () => {
  it('answers with the card’s OWN status ref, so the transition names an id its project has', () => {
    /*
      The cross-project trap. A column may have taken its id from another project's card, and a
      transition posted with that id names a status this issue's workflow does not contain — a
      404 or a 409 on a move the client had already decided was legal.
    */
    const columns = columnsFor([
      card({ id: 'MOB-1', allowedTransitions: [{ ...DONE, id: 'mob-done' }] }),
      card(),
    ])
    expect(resolveCardMove(card(), 'Done', columns)).toEqual({ outcome: 'MOVE', to: DONE })
  })

  it('refuses a column the card does not allow, rather than sending it and reverting', () => {
    /*
      `ui-rules`: never render an action the backend will always reject. The endpoint answers 409
      for exactly this, and a card that visibly lands and then springs back is the worse version
      of the same information.
    */
    const item = card({ allowedTransitions: [PROGRESS] })
    const outcome = resolveCardMove(item, 'Done', COLUMNS)

    expect(outcome.outcome).toBe('REFUSED')
    expect(outcome).not.toHaveProperty('to')
  })

  it('names the card’s OWN source in a derived refusal, not "an execution"', () => {
    // §81's defect: every derived card that was not an execution was called a review.
    const review = card({ derived: true, kind: 'REVIEW', linkedKey: 'TP-5', allowedTransitions: [] })

    const outcome = resolveCardMove(review, 'Done', COLUMNS)
    expect(outcome).toMatchObject({ outcome: 'REFUSED' })
    if (outcome.outcome !== 'REFUSED') throw new Error('unreachable')
    expect(outcome.reason).toContain('TP-5')
    expect(outcome.reason).toContain('a review')
    expect(outcome.reason).not.toContain('execution')
  })

  it('gives the menu path the identical sentence the drop path produces', () => {
    /*
      Two affordances, one rule, one wording. They were two hand-written strings until §106 and
      had already drifted — the tooltip said "This card follows its source" while the drop said
      "mirrors a review… update the review and the card follows".
    */
    const item = card({ allowedTransitions: [PROGRESS] })
    const dropped = resolveCardMove(item, 'Done', COLUMNS)
    const target = COLUMNS.find((column) => column.name === 'Done')

    if (dropped.outcome !== 'REFUSED') throw new Error('expected a refusal')
    if (!target) throw new Error('expected a Done column')
    expect(dropped.reason).toBe(refusalFor(item, target))
  })
})

describe('immobileReason — a card that can go nowhere says which kind of nowhere', () => {
  it('tells an epic its status is derived from its stories', () => {
    /*
      D31, and the reason this sentence is separate from `refusalFor`: an epic is refused whatever
      the target, so a sentence naming one would send the reader looking for a workflow rule that
      is not the reason. The control is disabled WITH this beside it — hidden, it would read as a
      missing permission.
    */
    // A board card carries no level, so "an epic" here is simply a row-backed card with no legal
    // move — which is exactly what the server sends for one (D31 empties `allowedTransitions`).
    const epic = card({ allowedTransitions: [] })
    expect(immobileReason(epic)).toContain('derived from its stories')
  })

  it('tells a derived card to update its source', () => {
    const execution = card({ derived: true, kind: 'EXECUTION', allowedTransitions: [] })
    expect(immobileReason(execution)).toContain('an execution')
  })
})
