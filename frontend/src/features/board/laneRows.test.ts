import { describe, expect, it } from 'vitest'
import { aBoardIssueCard, aBoardLane } from '../../test/fixtures'
import { epicCardsByLane, laneRowsOf } from './laneRows'

/**
 * 021 §2.5's grouping rule, asked directly.
 *
 * The layout it feeds is asserted through the board's own render; what is pinned here is the part
 * that can be *wrong* rather than merely unstyled — which cards land in the band, which earn a row,
 * and the two ways a card could silently render nowhere at all.
 */

const story = (key: string, overrides = {}) =>
  aBoardIssueCard({ key, typeLevel: 'STORY', ...overrides })
const task = (key: string, parentKey: string, overrides = {}) =>
  aBoardIssueCard({ key, typeLevel: 'TASK', parentKey, ...overrides })
const epic = (key: string, overrides = {}) =>
  aBoardIssueCard({ key, typeLevel: 'EPIC', parentKey: null, ...overrides })

describe('laneRowsOf', () => {
  it('puts a story with no tasks here in the band and gives it no row', () => {
    const shape = laneRowsOf([story('ACME-150')], 'EPIC')

    expect(shape.rows).toEqual([])
    expect(shape.band.map((card) => card.key)).toEqual(['ACME-150'])
  })

  it('gives a story a row when its tasks are cards here, and keeps it out of the band', () => {
    const shape = laneRowsOf(
      [story('ACME-118'), task('ACME-119', 'ACME-118'), story('ACME-150')],
      'EPIC',
    )

    expect(shape.rows).toHaveLength(1)
    expect(shape.rows[0].key).toBe('ACME-118')
    expect(shape.rows[0].tasks.map((card) => card.key)).toEqual(['ACME-119'])
    // The row's story is NOT also a card in the band — an item drawn as a card has no row header,
    // and an item with a row header is not also drawn as a card (render note 17).
    expect(shape.band.map((card) => card.key)).toEqual(['ACME-150'])
  })

  it('earns a row on ONE task, which §2.5 settled deliberately', () => {
    const shape = laneRowsOf([story('ACME-130'), task('ACME-131', 'ACME-130')], 'EPIC')

    expect(shape.rows).toHaveLength(1)
  })

  it('still draws a task whose story is on another board, under a header with no card', () => {
    /*
      The case the obvious spelling — "stories that have tasks here" — loses in silence. A board
      claims a subset of the statuses (R7), so `ACME-136` can be in a status this board does not
      show while its task is in one it does.
    */
    const shape = laneRowsOf([task('ACME-142', 'ACME-136')], 'EPIC')

    expect(shape.rows).toHaveLength(1)
    expect(shape.rows[0].key).toBe('ACME-136')
    expect(shape.rows[0].story).toBeNull()
    expect(shape.rows[0].tasks.map((card) => card.key)).toEqual(['ACME-142'])
    expect(shape.band).toEqual([])
  })

  it('puts a task with no parent in the band rather than dropping it', () => {
    const shape = laneRowsOf([aBoardIssueCard({ key: 'ACME-9', typeLevel: 'TASK', parentKey: null })], 'EPIC')

    expect(shape.band.map((card) => card.key)).toEqual(['ACME-9'])
    expect(shape.rows).toEqual([])
  })

  it('leaves every other axis flat — rows are specified for EPIC only', () => {
    const cards = [story('ACME-118'), task('ACME-119', 'ACME-118')]

    const shape = laneRowsOf(cards, 'ASSIGNEE')

    expect(shape.rows).toEqual([])
    expect(shape.band).toHaveLength(2)
  })

  it('orders rows by rank, with the key as the tiebreaker', () => {
    const shape = laneRowsOf(
      [
        story('ACME-130', { rank: '2048' }),
        task('ACME-131', 'ACME-130'),
        story('ACME-118', { rank: '512' }),
        task('ACME-119', 'ACME-118'),
      ],
      'EPIC',
    )

    expect(shape.rows.map((row) => row.key)).toEqual(['ACME-118', 'ACME-130'])
  })
})

describe('epicCardsByLane', () => {
  it('finds an epic filed in the catch-all lane that has a lane of its own', () => {
    const lanes = [
      aBoardLane({ key: 'ACME-101', label: 'Checkout redesign', cards: [story('ACME-150')] }),
      aBoardLane({ key: null, label: 'No epic', cards: [epic('ACME-101')] }),
    ]

    expect([...epicCardsByLane(lanes).keys()]).toEqual(['ACME-101'])
  })

  it('leaves an epic with no lane of its own where it is, rather than deleting it from the board', () => {
    const lanes = [aBoardLane({ key: null, label: 'No epic', cards: [epic('ACME-199')] })]

    expect(epicCardsByLane(lanes).size).toBe(0)
  })
})
