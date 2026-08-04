import { describe, expect, it } from 'vitest'
import { resolveStepMove } from './stepReorder'

/**
 * Where a dropped step lands — the half of §94's drag-and-drop that can be wrong.
 *
 * Driving a real drag through jsdom would test `@dnd-kit`; this tests the decision the editor
 * makes with what `@dnd-kit` reports, which is the part this repo owns. The three refusals matter
 * more than the move: each is a state a real drag produces, and each would otherwise call
 * `move(i, i)` or `move(-1, …)` on a form the author has not changed.
 */

const ids = ['a', 'b', 'c']

describe('resolveStepMove', () => {
  it('moves a row to the position it was dropped on', () => {
    expect(resolveStepMove(ids, 'a', 'c')).toEqual({ from: 0, to: 2 })
    expect(resolveStepMove(ids, 'c', 'a')).toEqual({ from: 2, to: 0 })
  })

  it('does nothing when the drop landed outside the list', () => {
    expect(resolveStepMove(ids, 'a', null)).toBeNull()
    expect(resolveStepMove(ids, 'a', undefined)).toBeNull()
  })

  it('does nothing when a row is dropped back where it started', () => {
    // `move(i, i)` reorders nothing and marks the form dirty — on an edit form that turns Save on
    // and offers to write a new version of a case nobody changed.
    expect(resolveStepMove(ids, 'b', 'b')).toBeNull()
  })

  it('does nothing when either id is no longer in the list', () => {
    // What a drag that outlived a remove looks like. `indexOf` answering -1 would otherwise reach
    // `move(-1, 1)`, which react-hook-form does not refuse.
    expect(resolveStepMove(ids, 'a', 'gone')).toBeNull()
    expect(resolveStepMove(ids, 'gone', 'a')).toBeNull()
  })

  it('accepts the numeric ids @dnd-kit permits, though this list uses strings', () => {
    // `UniqueIdentifier` is `string | number`; the field ids are strings, and a comparison that
    // assumed so would silently answer null for a caller that is legitimately typed.
    expect(resolveStepMove(['1', '2'], 1, 2)).toEqual({ from: 0, to: 1 })
  })
})
