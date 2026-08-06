import { describe, expect, it } from 'vitest'
import type { WorkItem, WorkItemStatus } from '../../api/types'
import { workItemColumns } from '../../lib/labels'
import { refusalFor, resolveCardMove } from './cardMove'

/**
 * Where a dragged card lands — **the only part of drag-and-drop that can be wrong rather than
 * merely unwired**, and the part a component test cannot reach: driving a real pointer drag
 * through jsdom exercises `@dnd-kit`, and `onDragEnd` is a prop of a context provider.
 *
 * §94 wrote the same test file for `resolveStepMove` and named the reason. This one carries one
 * thing that one did not: a card drag can be **legally refused**, and the refusal must happen at
 * drop time rather than as a 409 the card visibly springs back from.
 */

const COLUMNS = workItemColumns

function card(partial: Partial<WorkItem> = {}): WorkItem {
  return {
    id: 'wi-1',
    projectId: 'p1',
    projectKey: 'OLB',
    derived: false,
    sourceType: null,
    sourceId: null,
    kind: 'TASK',
    title: 'Reconcile the settlement file',
    description: null,
    status: 'TODO',
    priority: 'MEDIUM',
    dueDate: null,
    overdue: false,
    assignee: null,
    linkedKey: null,
    allowedTransitions: ['IN_PROGRESS', 'DONE'] as WorkItemStatus[],
    ...partial,
  } as WorkItem
}

describe('resolveCardMove — the three ways a drag ends in nothing', () => {
  it('does nothing when the card is dropped outside every column', () => {
    // `over` is null. The most common way a drag ends, and the one §94 lists first.
    expect(resolveCardMove(card(), null, COLUMNS)).toEqual({ outcome: 'NONE' })
    expect(resolveCardMove(card(), undefined, COLUMNS)).toEqual({ outcome: 'NONE' })
  })

  it('does nothing when the card is dropped back in the column it came from', () => {
    /*
      The board spelling of §94's `move(i, i)`. There it marked a form dirty; here it would be a
      PATCH writing an unchanged status — a version and an audit row for a gesture that changed
      nothing. Note the target IS in `allowedTransitions` here, so only the same-column check
      stops it.
    */
    const item = card({ status: 'TODO', allowedTransitions: ['TODO', 'DONE'] as WorkItemStatus[] })
    expect(resolveCardMove(item, 'TODO', COLUMNS)).toEqual({ outcome: 'NONE' })
  })

  it('does nothing when dropped on an id that is not a column', () => {
    // A stale droppable from a drag that outlived a re-render — `resolveStepMove`'s third arm.
    expect(resolveCardMove(card(), 'wi-9', COLUMNS)).toEqual({ outcome: 'NONE' })
  })

  it('does nothing when there is no active card', () => {
    expect(resolveCardMove(null, 'DONE', COLUMNS)).toEqual({ outcome: 'NONE' })
  })
})

describe('resolveCardMove — the move and the refusal', () => {
  it('moves to a column the card allows', () => {
    expect(resolveCardMove(card(), 'DONE', COLUMNS)).toEqual({ outcome: 'MOVE', to: 'DONE' })
  })

  it('refuses a column the card does not allow, rather than sending it and reverting', () => {
    /*
      `ui-rules`: never render an action the backend will always reject. The endpoint answers 409
      for exactly this, and a card that visibly lands and then springs back is the worse version
      of the same information.
    */
    const item = card({ status: 'TODO', allowedTransitions: ['IN_PROGRESS'] as WorkItemStatus[] })
    const outcome = resolveCardMove(item, 'DONE', COLUMNS)

    expect(outcome.outcome).toBe('REFUSED')
    expect(outcome).not.toHaveProperty('to')
  })

  it('names the card’s OWN source in a derived refusal, not "an execution"', () => {
    // §81's defect: every derived card that was not an execution was called a review.
    const review = card({
      derived: true,
      kind: 'REVIEW',
      linkedKey: 'TP-5',
      allowedTransitions: [] as WorkItemStatus[],
    })

    const outcome = resolveCardMove(review, 'DONE', COLUMNS)
    expect(outcome).toMatchObject({ outcome: 'REFUSED' })
    if (outcome.outcome !== 'REFUSED') throw new Error('unreachable')
    expect(outcome.reason).toContain('TP-5')
    expect(outcome.reason).toContain('a review')
    expect(outcome.reason).not.toContain('execution')
  })

  it('gives the button path the identical sentence the drop path produces', () => {
    /*
      Two affordances, one rule, one wording. They were two hand-written strings until §106 and
      had already drifted — the tooltip said "This card follows its source" while the drop said
      "mirrors a review… update the review and the card follows".
    */
    const item = card({ status: 'TODO', allowedTransitions: [] as WorkItemStatus[] })
    const dropped = resolveCardMove(item, 'DONE', COLUMNS)

    if (dropped.outcome !== 'REFUSED') throw new Error('expected a refusal')
    expect(dropped.reason).toBe(refusalFor(item, 'DONE'))
  })
})
