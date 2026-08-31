import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { useWorkFilter } from './useWorkFilter'

/**
 * One URL, three endpoints — and the mapping is the thing that can be wrong.
 *
 * D34 puts the filter in the URL so a view switch never loses it and a pasted link restores it.
 * D-v is what makes reading it a mapping rather than a cast: `IssueFilters` and `BoardFilters`
 * differ in **both** directions, so the only way for this hook to be wrong is to hand a view a
 * parameter its endpoint does not declare — a 400 for the board, and for the table a filter the
 * server silently ignores while the chip says it applied.
 *
 * So the assertions here are mostly about **absence**: what each subset must NOT carry.
 */

function harness(initialUrl: string) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[initialUrl]}>{children}</MemoryRouter>
  )
  return renderHook(() => ({ ...useWorkFilter(), location: useLocation() }), { wrapper })
}

const WORK = '/projects/p1/work'

describe('reading one URL into two parameter sets', () => {
  it('gives the table statusId and parentKey, and the board neither', () => {
    const { result } = harness(`${WORK}?statusId=st-1&statusId=st-2&parentKey=ACME-9`)

    expect(result.current.filters.issue.statusId).toEqual(['st-1', 'st-2'])
    expect(result.current.filters.issue.parentKey).toBe('ACME-9')
    // A `statusId` over a board is a 400, and it is also incoherent on its face: a status filter
    // would empty the columns the board is drawn from.
    expect(result.current.filters.board).not.toHaveProperty('statusId')
    expect(result.current.filters.board).not.toHaveProperty('parentKey')
  })

  it('gives the board labelId and priority, and the table neither', () => {
    const { result } = harness(`${WORK}?labelId=lab-1&labelId=lab-2&priority=HIGH`)

    expect(result.current.filters.board.labelId).toEqual(['lab-1', 'lab-2'])
    expect(result.current.filters.board.priority).toEqual(['HIGH'])
    expect(result.current.filters.issue).not.toHaveProperty('labelId')
    expect(result.current.filters.issue).not.toHaveProperty('priority')
  })

  it('gives both the four they share', () => {
    const { result } = harness(`${WORK}?level=STORY&epicKey=ACME-1&assigneeId=u1&unparented=true`)

    for (const subset of [result.current.filters.issue, result.current.filters.board]) {
      expect(subset.level).toBe('STORY')
      expect(subset.epicKey).toBe('ACME-1')
      expect(subset.assigneeId).toEqual(['u1'])
      expect(subset.unparented).toBe(true)
    }
  })

  it('drops a priority the contract does not have rather than sending it', () => {
    // A link shared from a later release could name one. An unknown member reaching the server is
    // a 400 on a request the reader did not make.
    const { result } = harness(`${WORK}?priority=HIGH&priority=BLOCKER`)

    expect(result.current.filters.board.priority).toEqual(['HIGH'])
  })

  it('reads an absent filter as undefined, never as an empty array', () => {
    // `qs` drops an `undefined` and spells an empty array as nothing at all — but an empty array
    // is a different cache key from `undefined`, so two identical filters would be two caches.
    const { result } = harness(WORK)

    expect(result.current.filters.board.labelId).toBeUndefined()
    expect(result.current.filters.issue.statusId).toBeUndefined()
    expect(result.current.filters.issue.level).toBeUndefined()
  })

  it('reads a single-member repeated parameter as an array', () => {
    // The one that breaks a request silently: `labelId: 'lab-1'` serializes differently from
    // `labelId: ['lab-1']`.
    const { result } = harness(`${WORK}?labelId=lab-1`)

    expect(result.current.filters.board.labelId).toEqual(['lab-1'])
  })
})

describe('what is not a filter', () => {
  it('leaves view, sort, cols and board out of both subsets', () => {
    /*
      They are which view you are looking at, not which work — and folding them in would make a
      filter object that changes on a tab switch, which evicts every cached list on a tab switch.
      `board` is the one most easily mistaken for a filter: it narrows what is on screen, and it
      names *which board* rather than *which work*.
    */
    const { result } = harness(`${WORK}?view=board&board=b2&sort=title&cols=type,summary`)

    expect(Object.values(result.current.filters.issue).every((value) => value === undefined)).toBe(
      true,
    )
    expect(Object.values(result.current.filters.board).every((value) => value === undefined)).toBe(
      true,
    )
  })

  it('keeps all four across a filter clear', () => {
    const { result } = harness(
      `${WORK}?view=board&board=b2&sort=title&cols=type&labelId=lab-1&level=STORY`,
    )

    act(() => result.current.clearFilters())

    const params = new URLSearchParams(result.current.location.search)
    expect(params.get('view')).toBe('board')
    // Clearing a filter set on the stakeholder board and landing on the engineering one is a
    // surprise, and re-picking it is a second step nobody asked for.
    expect(params.get('board')).toBe('b2')
    expect(params.get('sort')).toBe('title')
    expect(params.get('cols')).toBe('type')
    expect(params.get('labelId')).toBeNull()
    expect(params.get('level')).toBeNull()
  })
})

describe('writing the URL', () => {
  it('appends a repeated value once, however many times it is chosen', () => {
    const { result } = harness(`${WORK}?labelId=lab-1`)

    act(() => result.current.appendOnce('labelId', 'lab-1'))
    act(() => result.current.appendOnce('labelId', 'lab-2'))

    expect(result.current.filters.board.labelId).toEqual(['lab-1', 'lab-2'])
  })

  it('drops one value of a repeated parameter and keeps the rest', () => {
    const { result } = harness(`${WORK}?labelId=lab-1&labelId=lab-2&labelId=lab-3`)

    act(() => result.current.dropOne('labelId', 'lab-2'))

    expect(result.current.filters.board.labelId).toEqual(['lab-1', 'lab-3'])
  })

  it('round-trips what it writes', () => {
    // The URL is the contract, and the two halves are written in different places: the `useMemo`
    // parses and `setParam` serializes. A store round-trips by construction; a URL does not.
    const { result } = harness(WORK)

    act(() => result.current.setParam((next) => next.set('epicKey', 'ACME-1')))
    act(() => result.current.appendOnce('priority', 'CRITICAL'))

    expect(result.current.filters.board.epicKey).toBe('ACME-1')
    expect(result.current.filters.issue.epicKey).toBe('ACME-1')
    expect(result.current.filters.board.priority).toEqual(['CRITICAL'])
  })
})
