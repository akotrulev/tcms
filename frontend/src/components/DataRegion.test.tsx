import { ThemeProvider } from '@mui/material/styles'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, TimeoutError } from '../api/client'
import { theme } from '../app/theme'
import { DataRegion, type DataRegionState } from './DataRegion'

/**
 * The three-state binding. Every list, panel and tile in the app renders through this one
 * component, which is what makes its state matrix worth a test on its own: a mistake here is
 * not one broken screen, it is every screen (ui-rules §8).
 *
 * The states are asserted as *user-visible outcomes* — a skeleton, an alert with retry, rows
 * still on screen with a staleness marker — rather than as props, because the property that
 * matters is not "the component received `isError`" but "the user still has their nineteen rows".
 */

type Rows = { items: string[] }

const state = (partial: Partial<DataRegionState<Rows>>): DataRegionState<Rows> => ({
  data: undefined,
  isPending: false,
  isError: false,
  error: null,
  ...partial,
})

function renderRegion(regionState: DataRegionState<Rows>, extra?: Partial<Parameters<typeof DataRegion<Rows>>[0]>) {
  return render(
    <ThemeProvider theme={theme}>
      <DataRegion<Rows>
        testId="Cases"
        state={regionState}
        isEmpty={(data) => data.items.length === 0}
        {...extra}
      >
        {(data) => (
          <ul data-testid="Cases.Rows">
            {data.items.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        )}
      </DataRegion>
    </ThemeProvider>,
  )
}

const rows = (...items: string[]): Rows => ({ items })

describe('DataRegion', () => {
  describe('loading', () => {
    it('renders the loading state, announced as busy', () => {
      renderRegion(state({ isPending: true }))

      const loading = screen.getByTestId('Cases.Loading')
      expect(loading).toBeInTheDocument()
      expect(loading).toHaveAttribute('aria-busy', 'true')
      expect(screen.queryByTestId('Cases.Rows')).not.toBeInTheDocument()
    })

    it('renders the caller’s skeleton when one is supplied, so rows do not jump', () => {
      renderRegion(state({ isPending: true }), {
        skeleton: <div data-testid="Cases.GridSkeleton" />,
      })
      expect(screen.getByTestId('Cases.GridSkeleton')).toBeInTheDocument()
    })

    it('stays in the loading state when a settled query has no data, rather than rendering undefined', () => {
      // `isPending` false with `data === undefined` is reachable (a disabled query that later
      // enables); rendering the children there would call `children(undefined)`.
      renderRegion(state({ isPending: false, data: undefined }))
      expect(screen.getByTestId('Cases.Loading')).toBeInTheDocument()
    })
  })

  describe('loaded', () => {
    it('renders the data', () => {
      renderRegion(state({ data: rows('TC-1', 'TC-2') }))
      expect(screen.getByTestId('Cases.Rows')).toHaveTextContent('TC-1')
      expect(screen.queryByTestId('Cases.Loading')).not.toBeInTheDocument()
    })

    it('shows a background refresh as a hint, not as a spinner that hides the content', () => {
      renderRegion(state({ data: rows('TC-1'), isFetching: true }))

      expect(screen.getByTestId('Cases.Rows')).toBeInTheDocument()
      const hint = screen.getByTestId('Cases.Refreshing')
      // Decorative: a progress hairline announced to a screen reader on every refetch is noise.
      expect(hint).toHaveAttribute('aria-hidden', 'true')
    })
  })

  describe('empty', () => {
    it('distinguishes an empty result from a broken one', () => {
      renderRegion(state({ data: rows() }))

      expect(screen.getByTestId('Cases.Empty')).toBeInTheDocument()
      expect(screen.queryByTestId('Cases.Error')).not.toBeInTheDocument()
    })

    it('says something different when filters are what emptied it, and offers the way out', async () => {
      const user = userEvent.setup()
      const onClearFilters = vi.fn()
      renderRegion(state({ data: rows() }), {
        empty: { kind: 'no-filter-match', onClearFilters },
      })

      // "No data" for all three kinds is how an empty grid becomes indistinguishable from a
      // broken one — and from a filter the user forgot they set.
      expect(screen.getByText('No matches')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Clear filters' }))
      expect(onClearFilters).toHaveBeenCalledOnce()
    })

    it('renders the data when no emptiness predicate is supplied', () => {
      renderRegion(state({ data: rows() }), { isEmpty: undefined })
      expect(screen.getByTestId('Cases.Rows')).toBeInTheDocument()
      expect(screen.queryByTestId('Cases.Empty')).not.toBeInTheDocument()
    })
  })

  describe('failed first load', () => {
    it('renders the failure with copy from describeError, not the raw exception', () => {
      renderRegion(state({ isError: true, error: new ApiError(503, 'upstream connect error 111') }))

      const alert = screen.getByTestId('Cases.Error')
      expect(alert).toHaveTextContent("Couldn't load this")
      expect(alert).not.toHaveTextContent('upstream connect error')
    })

    it('offers a retry only where one could plausibly succeed', async () => {
      const refetch = vi.fn()
      const user = userEvent.setup()

      const { unmount } = renderRegion(state({ isError: true, error: new TimeoutError(10_000), refetch }))
      await user.click(screen.getByTestId('Cases.Retry'))
      expect(refetch).toHaveBeenCalledOnce()
      unmount()

      // A 403 will fail identically on the next click; a button that never works is worse than
      // no button.
      renderRegion(state({ isError: true, error: new ApiError(403, 'nope'), refetch }))
      expect(screen.queryByTestId('Cases.Retry')).not.toBeInTheDocument()
    })

    it('shows the correlation id, so a failure can be looked up rather than described', () => {
      renderRegion(state({ isError: true, error: new ApiError(500, 'boom', null, 'corr-42') }))
      expect(screen.getByTestId('Cases.Error')).toHaveTextContent('corr-42')
    })

    /**
     * A cancelled request happens on every navigation and every superseded filter keystroke.
     * Rendering it as a failure would put a red alert on screen every time someone types.
     */
    it('never renders a cancellation as a failure', () => {
      const abort = new DOMException('The operation was aborted.', 'AbortError')

      renderRegion(state({ isError: true, error: abort, isPending: true }))

      expect(screen.queryByTestId('Cases.Error')).not.toBeInTheDocument()
      expect(screen.getByTestId('Cases.Loading')).toBeInTheDocument()
    })
  })

  /**
   * The state that most often goes unbuilt, and the one where a user makes a decision on stale
   * figures without knowing it. §5 records it verified in a browser: 19 rows kept, marked
   * "Showing data from 11:25 UTC", retry offered, no failed body.
   */
  describe('failed refetch — the data stays', () => {
    const failedRefetch = state({
      data: rows('TC-1', 'TC-2', 'TC-3'),
      isError: true,
      error: new ApiError(503, 'unavailable'),
      dataUpdatedAt: Date.parse('2026-07-31T11:25:00Z'),
    })

    it('keeps the rows it already had', () => {
      renderRegion(failedRefetch)
      expect(screen.getByTestId('Cases.Rows')).toHaveTextContent('TC-3')
      // Throwing away rows you already have because a background refresh failed is strictly
      // worse than showing them with an honest marker.
      expect(screen.queryByTestId('Cases.Error')).not.toBeInTheDocument()
    })

    it('names the age of what is on screen, rather than saying "older data"', () => {
      renderRegion(failedRefetch)
      // "Older data" gives the user nothing to judge; a time lets them decide whether it still
      // supports the decision they were about to make.
      expect(screen.getByTestId('Cases.Stale')).toHaveTextContent('Showing data from 11:25 UTC')
    })

    it('offers a retry from the stale marker', async () => {
      const user = userEvent.setup()
      const refetch = vi.fn()
      renderRegion({ ...failedRefetch, refetch })

      await user.click(screen.getByTestId('Cases.Retry'))
      expect(refetch).toHaveBeenCalledOnce()
    })

    it('does not also show the refreshing hairline while it is failed', () => {
      renderRegion({ ...failedRefetch, isFetching: true })
      expect(screen.queryByTestId('Cases.Refreshing')).not.toBeInTheDocument()
    })

    it('drops the stale marker once the refetch succeeds', () => {
      const { rerender } = renderRegion(failedRefetch)
      expect(screen.getByTestId('Cases.Stale')).toBeInTheDocument()

      rerender(
        <ThemeProvider theme={theme}>
          <DataRegion<Rows> testId="Cases" state={state({ data: rows('TC-1', 'TC-2', 'TC-3', 'TC-4') })}>
            {(data) => <ul data-testid="Cases.Rows">{data.items.map((i) => <li key={i}>{i}</li>)}</ul>}
          </DataRegion>
        </ThemeProvider>,
      )

      expect(screen.queryByTestId('Cases.Stale')).not.toBeInTheDocument()
      expect(screen.getByTestId('Cases.Rows')).toHaveTextContent('TC-4')
    })
  })

  /**
   * §5's third Phase 3 defect: a drifted 200 crashed the whole ROUTE rather than the region,
   * because `isEmpty(data)` — a predicate over the response body — was evaluated outside the
   * region's own boundary. The fix moved it into a child component, and this is the assertion
   * that the fix is still in place.
   */
  describe('a drifted response degrades the region, not the page', () => {
    const Sibling = ({ children }: { children: ReactNode }) => (
      <div>
        <div data-testid="PageChrome">Search and filters</div>
        {children}
      </div>
    )

    it('contains a throw from the children', () => {
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

      render(
        <ThemeProvider theme={theme}>
          <Sibling>
            <DataRegion<Rows> testId="Cases" state={state({ data: rows('TC-1') })}>
              {() => {
                throw new TypeError("Cannot read properties of undefined (reading 'key')")
              }}
            </DataRegion>
          </Sibling>
        </ThemeProvider>,
      )

      // The page's own chrome — the search box and the filters the user needs in order to
      // retry — is still mounted.
      expect(screen.getByTestId('PageChrome')).toBeInTheDocument()
      expect(screen.queryByTestId('Cases.Rows')).not.toBeInTheDocument()
      // The region's own boundary caught it — not merely "nothing crashed".
      expect(screen.getByTestId('Cases.RenderError')).toBeInTheDocument()
      consoleError.mockRestore()
    })

    it('contains a throw from the emptiness predicate, which reads the body outside the children', () => {
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

      render(
        <ThemeProvider theme={theme}>
          <Sibling>
            <DataRegion<Rows>
              testId="Cases"
              state={state({ data: { items: undefined as unknown as string[] } })}
              isEmpty={(data) => data.items.length === 0}
            >
              {() => <ul data-testid="Cases.Rows" />}
            </DataRegion>
          </Sibling>
        </ThemeProvider>,
      )

      expect(screen.getByTestId('PageChrome')).toBeInTheDocument()
      expect(screen.getByTestId('Cases.RenderError')).toBeInTheDocument()
      consoleError.mockRestore()
    })
  })
})
