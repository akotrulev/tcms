import { describe, expect, it } from 'vitest'
import { stepToAdjacentColumn, stepToAdjacentLane, type BandRect } from './boardKeyboard'

/**
 * The keyboard drag's column step.
 *
 * `@dnd-kit`'s stock getters cannot do this — `sortableKeyboardCoordinates` needs a
 * `SortableContext` the board does not have, and the default one nudges 25px per keypress. So the
 * arithmetic is ours, which means it is ours to get wrong, and a jsdom keypress through the sensor
 * would exercise `@dnd-kit` rather than this.
 */

/** Four 200px columns with a 20px gutter, centres at 100, 320, 540, 760. */
const COLUMNS: BandRect[] = [0, 220, 440, 660].map((left) => ({ left, width: 200, top: 0, height: 400 }))

const ORIGIN = { x: 0, y: 0 }

describe('stepToAdjacentColumn', () => {
  it('moves the card to the centre of the next column along', () => {
    // Card sitting on column 0's centre; right takes it to column 1's, 220px away.
    expect(stepToAdjacentColumn(COLUMNS, { x: 100, y: 200 }, ORIGIN, 1)).toEqual({ x: 220, y: 0 })
  })

  it('adds to the existing translate rather than replacing it', () => {
    /*
      The translate is measured from where the card STARTED. Returning an absolute position here
      would teleport the card by its own offset, which is the mistake that makes a keyboard drag
      jump on the first press and look broken from then on.
    */
    expect(stepToAdjacentColumn(COLUMNS, { x: 320, y: 200 }, { x: 220, y: 15 }, 1)).toEqual({
      x: 220 + 220,
      y: 15,
    })
  })

  it('returns null at the right edge instead of wrapping to the first column', () => {
    // A wrap would move a card four columns on one keypress — the opposite of what was asked.
    expect(stepToAdjacentColumn(COLUMNS, { x: 760, y: 200 }, ORIGIN, 1)).toBeNull()
  })

  it('returns null at the left edge', () => {
    expect(stepToAdjacentColumn(COLUMNS, { x: 100, y: 200 }, ORIGIN, -1)).toBeNull()
  })

  it('resolves the current column by nearest centre, so a card over a gutter still steps', () => {
    /*
      Containment answers "none" here — x=210 is in the 20px gap between columns 0 and 1 — and a
      containment-based implementation would either do nothing or fall back to column 0. Nearest
      centre picks 1 (|320-210| = 110 < |100-210| = 110… tie broken to the first, so 0), and from
      0 the step right is 1. Asserted on the OUTCOME rather than on the internal index.
    */
    const result = stepToAdjacentColumn(COLUMNS, { x: 210, y: 200 }, ORIGIN, 1)
    expect(result).not.toBeNull()
    // Whichever of the two neighbours it resolved to, the card must land on a column centre.
    expect([100, 320, 540, 760]).toContain(210 + (result?.x ?? 0))
  })

  it('answers null rather than throwing when no column has been measured', () => {
    // Every rect missing is the state before the first layout pass; a getter that threw there
    // would break the drag on its first keypress.
    expect(stepToAdjacentColumn([], { x: 0, y: 0 }, ORIGIN, 1)).toBeNull()
  })

  it('steps left symmetrically', () => {
    expect(stepToAdjacentColumn(COLUMNS, { x: 540, y: 200 }, ORIGIN, -1)).toEqual({ x: -220, y: 0 })
  })
})

/**
 * The lane step — the same arithmetic on the other axis (012 T6), which is why it shares an
 * implementation rather than repeating one.
 *
 * The lanes are deliberately **unequal heights**: an epic's lane is as tall as the work it holds, so
 * a fixed stride is the thing the stock 25px nudge gets wrong twice over — too small, and
 * unpredictably so. Every case below is one the column tests already make on x; they are made again
 * here because the axis is the parameter that could be threaded through wrong, and a getter that
 * moved the card sideways on Down would typecheck.
 */
const LANES: BandRect[] = [
  { left: 0, width: 900, top: 0, height: 300 },
  { left: 0, width: 900, top: 300, height: 120 },
  { left: 0, width: 900, top: 420, height: 500 },
]

const LANE_CENTERS = [150, 360, 670]

describe('stepToAdjacentLane', () => {
  it('moves the card to the centre of the next lane down, whatever that lane’s height', () => {
    expect(stepToAdjacentLane(LANES, { x: 400, y: LANE_CENTERS[0] }, ORIGIN, 1)).toEqual({
      x: 0,
      y: LANE_CENTERS[1] - LANE_CENTERS[0],
    })
  })

  it('moves only the y member, so a lane step never drifts the card between columns', () => {
    // The axis is the one thing this could be threaded through wrong, and an x that moved with it
    // would take the card out of its column on a keypress that is not about columns.
    const stepped = stepToAdjacentLane(LANES, { x: 400, y: LANE_CENTERS[1] }, { x: 220, y: 0 }, 1)
    expect(stepped?.x).toBe(220)
  })

  it('adds to the existing translate rather than replacing it', () => {
    expect(stepToAdjacentLane(LANES, { x: 400, y: LANE_CENTERS[1] }, { x: 0, y: 210 }, 1)).toEqual({
      x: 0,
      y: 210 + (LANE_CENTERS[2] - LANE_CENTERS[1]),
    })
  })

  it('returns null at the bottom lane instead of wrapping to the top', () => {
    // A wrap here is worse than the column one: it moves a card past every epic on the board.
    expect(stepToAdjacentLane(LANES, { x: 400, y: LANE_CENTERS[2] }, ORIGIN, 1)).toBeNull()
  })

  it('returns null at the top lane', () => {
    expect(stepToAdjacentLane(LANES, { x: 400, y: LANE_CENTERS[0] }, ORIGIN, -1)).toBeNull()
  })

  it('resolves the current lane by nearest centre, so a card over a lane header still steps', () => {
    // y = 305 is just inside the short middle lane and nowhere near its centre. Containment and
    // nearest-centre disagree about nothing here; what is asserted is that the card lands ON a
    // lane centre rather than 25px from wherever it started.
    const result = stepToAdjacentLane(LANES, { x: 400, y: 305 }, ORIGIN, 1)
    expect(result).not.toBeNull()
    expect(LANE_CENTERS).toContain(305 + (result?.y ?? 0))
  })

  it('answers null rather than throwing when no lane has been measured', () => {
    // The state before the first layout pass — a getter that threw would break the drag on its
    // first keypress, which is the column helper's rule and the reason it is one implementation.
    expect(stepToAdjacentLane([], { x: 0, y: 0 }, ORIGIN, 1)).toBeNull()
  })
})
