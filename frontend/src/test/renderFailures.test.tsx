/**
 * The seam's own tests: it sees a real boundary trip, tells one apart from a failed fetch, and can be
 * suspended by the test whose subject a trip is.
 *
 * ## Why every case here throws through a real boundary
 *
 * `renderFailures.ts` reads `window.__tcmsErrors`, which is filled only on the `import.meta.env.DEV`
 * branch of `reportClientError`'s `send()`. A test that pushed a hand-made report onto that array
 * would pass with the whole path between a component throwing and the array being written unplugged —
 * and a check that reads an array nobody fills is indistinguishable, from every green run, from a
 * suite in which nothing ever throws. So each case below renders a component that throws, inside the
 * boundary the app really uses.
 *
 * This is `contractDoubles.test.ts`'s vacuity argument in the same shape: the instrument's working
 * state is silence, so silence has to be measured rather than assumed.
 */
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { theme } from '../app/theme'
import { RegionErrorBoundary, RouteErrorBoundary } from '../components/ErrorBoundaries'
import { reportClientError } from '../lib/reportClientError'
import { drainBoundaryTrips, renderingIntoAnErrorBoundary } from './renderFailures'

function Throws(): ReactNode {
  throw new TypeError("Cannot read properties of undefined (reading 'map')")
}

/**
 * React logs a caught error through `console.error` on its own, above and beyond the app's sink.
 * Silenced per render rather than globally, so a case that throws where it did not mean to still
 * reports itself.
 */
function renderThrowing(tree: ReactNode) {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
  render(
    <ThemeProvider theme={theme}>
      <MemoryRouter>{tree}</MemoryRouter>
    </ThemeProvider>,
  )
  consoleError.mockRestore()
}

describe('the render-failure seam', () => {
  it('sees a region catch a throw, and names the region', () => {
    renderingIntoAnErrorBoundary()

    renderThrowing(
      <RegionErrorBoundary testId="CaseVersions" label="this content">
        <Throws />
      </RegionErrorBoundary>,
    )

    const trips = drainBoundaryTrips()
    expect(trips).toHaveLength(1)
    expect(trips[0]?.source).toBe('region-boundary')
    // The `testId` is what makes the failure point at a panel rather than at a page, and it is the
    // one thing the DOM would not have given back after a boundary reset.
    expect(trips[0]?.context?.region).toBe('CaseVersions')
    expect(trips[0]?.message).toContain('TypeError')
  })

  it('sees the route tier too, and carries the path', () => {
    renderingIntoAnErrorBoundary()

    renderThrowing(
      <RouteErrorBoundary>
        <Throws />
      </RouteErrorBoundary>,
    )

    const trips = drainBoundaryTrips()
    expect(trips).toHaveLength(1)
    expect(trips[0]?.source).toBe('route-boundary')
    expect(trips[0]?.context?.path).toBe('/')
  })

  /**
   * The distinction the whole check rests on: a region rendering its *designed* failure state is not
   * a region whose children threw, and hundreds of tests drive the first on purpose.
   *
   * The two reports are made by hand here, unlike every other case in this file, because
   * `createTestQueryClient` installs no error handlers — so no test in this suite can produce one
   * through the app. That is the check's own recorded blind spot, and this is where the classification
   * is pinned against the day the test client grows them.
   */
  it('says nothing about a failed query or a failed mutation', () => {
    reportClientError(new Error('500'), 'query', { queryKey: '["cases"]' })
    reportClientError(new Error('500'), 'mutation')

    expect(drainBoundaryTrips()).toEqual([])
  })

  it('is quiet when nothing threw', () => {
    renderThrowing(
      <RegionErrorBoundary testId="CaseVersions" label="this content">
        <div data-testid="Rows" />
      </RegionErrorBoundary>,
    )

    expect(drainBoundaryTrips()).toEqual([])
  })

  /**
   * The sink dedupes on `source:name:message` for the life of the module, so without the
   * `beforeEach` reset this seam installs, the *second* test in a file to hit the same broken panel
   * would collect nothing — the check would redden whichever test ran first and call every later one
   * clean, which is the exact reading failure it exists to end. The first case in this file threw the
   * same `TypeError` from the same tier; this one proves it is still reported.
   */
  it('reports a trip a previous test in this file already reported', () => {
    renderingIntoAnErrorBoundary()

    renderThrowing(
      <RegionErrorBoundary testId="CaseVersions" label="this content">
        <Throws />
      </RegionErrorBoundary>,
    )

    expect(drainBoundaryTrips()).toHaveLength(1)
  })
})
