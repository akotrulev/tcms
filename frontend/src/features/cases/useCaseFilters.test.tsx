import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { DEFAULT_SIZE, DEFAULT_SORT, useCaseFilters } from './useCaseFilters'

/**
 * Filter state lives in the URL, not in a store — a product decision (plan §6o/§6q), because a
 * filtered view of a test repository is the thing people paste into a ticket and bookmark for
 * their release.
 *
 * That makes the URL a **contract**, and the round trip its assertion: what the hook writes must
 * be what the hook reads back. A store round-trips by construction; a URL does not, and the two
 * halves are written in different places — `filters` parses, `setFilter` serializes.
 */

function harness(initialUrl: string) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[initialUrl]}>{children}</MemoryRouter>
  )
  return renderHook(
    () => ({ ...useCaseFilters(), location: useLocation(), navigate: useNavigate() }),
    { wrapper },
  )
}

const search = (result: { current: { location: { search: string } } }) =>
  new URLSearchParams(result.current.location.search)

describe('useCaseFilters — reading the URL', () => {
  it('reads defaults from a bare URL', () => {
    const { result } = harness('/projects/p/cases')

    expect(result.current.filters.page).toBe(0)
    expect(result.current.filters.size).toBe(DEFAULT_SIZE)
    expect(result.current.sort).toBe(DEFAULT_SORT)
    expect(result.current.hasActiveFilters).toBe(false)
    expect(result.current.filters.q).toBeUndefined()
  })

  it('reads a single-valued filter', () => {
    const { result } = harness('/projects/p/cases?q=refund&suiteId=s-1&releaseVersion=4.10.0')

    expect(result.current.filters.q).toBe('refund')
    expect(result.current.filters.suiteId).toBe('s-1')
    expect(result.current.filters.releaseVersion).toBe('4.10.0')
    expect(result.current.hasActiveFilters).toBe(true)
  })

  it('reads a repeated parameter as an array, which is what the API takes', () => {
    const { result } = harness('/projects/p/cases?priority=P1&priority=P2&lifecycleStatus=APPROVED')

    expect(result.current.filters.priority).toEqual(['P1', 'P2'])
    expect(result.current.filters.lifecycleStatus).toEqual(['APPROVED'])
  })

  it('reads a single-member multi filter as an array, not a bare string', () => {
    // The one that breaks a request silently: `priority: 'P1'` serializes differently from
    // `priority: ['P1']` and the grid comes back unfiltered.
    const { result } = harness('/projects/p/cases?priority=P1')
    expect(result.current.filters.priority).toEqual(['P1'])
  })

  it('treats an empty parameter as absent rather than as a filter on the empty string', () => {
    // `?priority=` is what a cleared select leaves behind if the URL is edited by hand.
    const { result } = harness('/projects/p/cases?priority=&lifecycleStatus=')
    expect(result.current.filters.priority).toBeUndefined()
    expect(result.current.hasActiveFilters).toBe(false)
  })

  it('does not count paging or sorting as an active filter', () => {
    // `hasActiveFilters` drives no-records vs no-filter-match. Counting `page` would make an
    // empty page 4 read as "nothing matches your filters", offering a Clear that changes nothing.
    const { result } = harness('/projects/p/cases?page=3&size=50&sort=title,desc')
    expect(result.current.hasActiveFilters).toBe(false)
  })
})

describe('useCaseFilters — writing the URL', () => {
  it('round-trips a value it wrote', () => {
    const { result } = harness('/projects/p/cases')

    act(() => result.current.setFilter('q', 'refund timeout'))

    expect(result.current.filters.q).toBe('refund timeout')
    expect(search(result).get('q')).toBe('refund timeout')
  })

  it('round-trips a multi-value filter', () => {
    const { result } = harness('/projects/p/cases')

    act(() => result.current.setFilter('priority', ['P1', 'P3']))

    expect(result.current.filters.priority).toEqual(['P1', 'P3'])
    expect(search(result).getAll('priority')).toEqual(['P1', 'P3'])
  })

  it('replaces rather than appends when a multi filter is set twice', () => {
    const { result } = harness('/projects/p/cases?priority=P1')

    act(() => result.current.setFilter('priority', ['P2']))

    // Appending would grow the URL on every click of the same facet chip and send P1 forever.
    expect(search(result).getAll('priority')).toEqual(['P2'])
  })

  it('removes a filter set to undefined or to an empty list', () => {
    const { result } = harness('/projects/p/cases?q=x&priority=P1')

    act(() => result.current.setFilter('q', undefined))
    act(() => result.current.setFilter('priority', []))

    expect(search(result).has('q')).toBe(false)
    expect(search(result).has('priority')).toBe(false)
    expect(result.current.hasActiveFilters).toBe(false)
  })

  it('returns to page 1 whenever a filter changes', () => {
    const { result } = harness('/projects/p/cases?page=4&q=old')

    act(() => result.current.setFilter('q', 'new'))

    // Staying on page 4 of a result set that now has two pages shows an empty grid the user
    // cannot explain.
    expect(search(result).has('page')).toBe(false)
    expect(result.current.filters.page).toBe(0)
  })

  it('returns to page 1 when the page size changes', () => {
    const { result } = harness('/projects/p/cases?page=4')
    act(() => result.current.setSize(100))
    expect(search(result).has('page')).toBe(false)
    expect(result.current.filters.size).toBe(100)
  })

  it('keeps page 1 out of the URL, so the shareable link is the short one', () => {
    const { result } = harness('/projects/p/cases?page=3')
    act(() => result.current.setPage(0))
    expect(search(result).has('page')).toBe(false)
    expect(result.current.filters.page).toBe(0)
  })

  it('keeps the default sort out of the URL and writes any other', () => {
    const { result } = harness('/projects/p/cases')

    act(() => result.current.setSort('title,desc'))
    expect(search(result).get('sort')).toBe('title,desc')
    expect(result.current.sort).toBe('title,desc')

    act(() => result.current.setSort(DEFAULT_SORT))
    expect(search(result).has('sort')).toBe(false)
    expect(result.current.sort).toBe(DEFAULT_SORT)
  })

  it('clears every filter and leaves paging and sorting alone', () => {
    const { result } = harness(
      '/projects/p/cases?q=x&suiteId=s1&priority=P1&priority=P2&healthSignal=FLAKY&page=2&size=50&sort=title,desc',
    )

    act(() => result.current.clearFilters())

    expect(result.current.hasActiveFilters).toBe(false)
    expect(search(result).has('q')).toBe(false)
    expect(search(result).has('priority')).toBe(false)
    expect(search(result).has('page')).toBe(false)
    // A "Clear filters" that also reset the page size and the column sort would undo work the
    // user did not ask about.
    expect(search(result).get('size')).toBe('50')
    expect(search(result).get('sort')).toBe('title,desc')
  })

  it('preserves unrelated parameters, so a deep link from a dashboard tile survives a filter change', () => {
    // §6o — a dashboard tile is a link carrying a filter predicate, and two predicates combine
    // by merging query parameters. A `setParams` that rebuilt the string would drop the rest.
    const { result } = harness('/projects/p/cases?suiteId=s-1&highlight=TC-7')

    act(() => result.current.setFilter('q', 'refund'))

    expect(search(result).get('highlight')).toBe('TC-7')
    expect(search(result).get('suiteId')).toBe('s-1')
  })

  it('round-trips ownerId, which arrives only from a dashboard drill-down', () => {
    /*
      The by-owner bars on the dashboard are links carrying `ownerId`, and there is no control
      in the filter bar that sets it. So this parameter's ONLY path into the app is a URL
      somebody else wrote — which makes the round trip the whole of its correctness, and makes
      `hasActiveFilters` load-bearing: without it the list would read as unfiltered and offer no
      way back to everyone's cases.
    */
    const { result } = harness('/projects/p/cases?ownerId=u-7&lifecycleStatus=DRAFT')

    expect(result.current.filters.ownerId).toBe('u-7')

    act(() => result.current.setFilter('ownerId', undefined))
    expect(search(result).has('ownerId')).toBe(false)
    expect(result.current.filters.lifecycleStatus).toEqual(['DRAFT'])
  })

  it('counts ownerId ALONE as an active filter', () => {
    /*
      Split out from the round trip above, which asserted this against a URL that ALSO carried a
      lifecycleStatus — so `hasActiveFilters` was true either way and dropping ownerId from the
      filter set left the test green. A fixture that cannot distinguish the right behaviour from
      the wrong one proves nothing; found by mutating the parameter out.

      It matters because this is the ONLY filter with no control in the bar: if it does not count
      as active, the list renders narrowed with no "Clear filters" and no way back.
    */
    const { result } = harness('/projects/p/cases?ownerId=u-7')

    expect(result.current.hasActiveFilters).toBe(true)
  })

  it('replaces the history entry rather than pushing one per keystroke', async () => {
    const { result } = harness('/projects/p/cases')

    act(() => result.current.setFilter('q', 'r'))
    act(() => result.current.setFilter('q', 're'))
    act(() => result.current.setFilter('q', 'ref'))
    expect(search(result).get('q')).toBe('ref')

    await act(async () => {
      result.current.navigate(-1)
    })

    /*
      Pushing would make Back walk letter by letter out of a search box instead of returning to
      the screen the user drilled in from — which is the *other* half of "filters live in the
      URL": the back button has to keep meaning what it meant.

      Three replaced entries leave the stack at length one, so there is nowhere to go back to and
      the query survives. Three pushed entries would land this on `?q=re`.
    */
    expect(search(result).get('q')).toBe('ref')
  })
})
