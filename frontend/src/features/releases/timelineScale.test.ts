import { describe, expect, it } from 'vitest'
import { aRoadmapEpic, aRoadmapRelease } from '../../test/fixtures'
import { monthsIn, spanOf, spanOfEpic, todayAt, windowOf } from './timelineScale'

/**
 * The roadmap's arithmetic — a `.test.ts` with no `render()`, because none of this is a component.
 *
 * The three span shapes are the plan's own criterion and each is a different *claim on screen*:
 * a bar says "this runs from here to here", a point marker says "this lands here and nothing
 * inside it is scheduled", and `null` says "this exists and has no dates" — which is what
 * `work-timeline.html` draws for `2.10`.
 */

const WINDOW = { from: '2026-01-01', to: '2026-07-01' }

describe('a release’s bar spans min(epic.startDate) … targetDate', () => {
  it('starts at the earliest epic start, not at the first epic in the array', () => {
    const span = spanOf(
      aRoadmapRelease({
        targetDate: '2026-04-01',
        epics: [
          aRoadmapEpic({ key: 'P-2', startDate: '2026-03-01' }),
          aRoadmapEpic({ key: 'P-1', startDate: '2026-02-01' }),
        ],
      }),
      WINDOW,
    )

    // Feb 1 is one month into a six-month window; Apr 1 is three.
    expect(span?.left).toBeCloseTo((31 / 181) * 100, 1)
    expect(span?.width).toBeGreaterThan(30)
  })

  it('is a point marker when no epic under it has a start date', () => {
    /*
      The shape the plan names, and the reason it is not a zero-width bar: a bar of no width is
      invisible, so a release with nothing scheduled inside it would read as a release that is
      missing from the timeline rather than as one with an empty plan.
    */
    const span = spanOf(
      aRoadmapRelease({ targetDate: '2026-04-01', epics: [aRoadmapEpic({ startDate: null })] }),
      WINDOW,
    )

    expect(span).not.toBeNull()
    expect(span?.width).toBe(0)
  })

  it('is null when the release has no target date', () => {
    // `2.10`'s row: it still appears, in version order, with its epics and no bar of its own.
    expect(spanOf(aRoadmapRelease({ targetDate: null }), WINDOW)).toBeNull()
  })

  it('collapses to a point when an epic starts after the target date', () => {
    /*
      A real state — somebody moved the target in — and the one that would flip the bar if the
      width were computed as `right - left` without the guard. The marker sits at the target,
      because that is the date the release is about.
    */
    const span = spanOf(
      aRoadmapRelease({
        targetDate: '2026-02-01',
        epics: [aRoadmapEpic({ startDate: '2026-05-01' })],
      }),
      WINDOW,
    )

    expect(span?.width).toBe(0)
    expect(span?.left).toBeCloseTo((31 / 181) * 100, 1)
  })
})

describe('an epic’s bar falls back to whichever date it has', () => {
  it('spans start to target when it has both', () => {
    const span = spanOfEpic(
      aRoadmapEpic({ startDate: '2026-02-01', targetDate: '2026-03-01' }),
      WINDOW,
    )
    expect(span?.width).toBeGreaterThan(10)
  })

  it('is a point marker with only one of the two', () => {
    expect(spanOfEpic(aRoadmapEpic({ startDate: '2026-02-01' }), WINDOW)?.width).toBe(0)
    expect(spanOfEpic(aRoadmapEpic({ targetDate: '2026-02-01' }), WINDOW)?.width).toBe(0)
  })

  it('is null with neither, which is what puts it in the unscheduled lane', () => {
    expect(spanOfEpic(aRoadmapEpic({}), WINDOW)).toBeNull()
  })
})

describe('the window is derived from the data plus today', () => {
  it('always contains today, even when every release is a year out', () => {
    const window = windowOf(
      { releases: [aRoadmapRelease({ targetDate: '2027-06-01' })] },
      '2026-09-01',
    )

    // Today is the reader's reference point; a marker clamped to an edge reads as a date rather
    // than as "now".
    expect(window.from <= '2026-09-01').toBe(true)
    expect(window.to >= '2026-09-01').toBe(true)
    expect(todayAt(window, '2026-09-01')).toBeGreaterThan(0)
    expect(todayAt(window, '2026-09-01')).toBeLessThan(100)
  })

  it('pads a month on each side, so a bar flush to the edge cannot be mistaken for one that continues', () => {
    const window = windowOf(
      { releases: [aRoadmapRelease({ targetDate: '2026-09-20' })] },
      '2026-09-01',
    )
    expect(window.from).toBe('2026-08-01')
    expect(window.to).toBe('2026-11-01')
  })

  it('names every month it covers, in order', () => {
    expect(monthsIn({ from: '2026-08-01', to: '2026-11-01' }).map((m) => m.label)).toEqual([
      'Aug',
      'Sep',
      'Oct',
    ])
  })
})
