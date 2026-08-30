import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { RegionErrorBoundary, RouteErrorBoundary } from './ErrorBoundaries'
import { renderingIntoAnErrorBoundary } from '../test/renderFailures'

/**
 * §7: *"Every failure carries a six-character reference and a timestamp"*.
 *
 * The 2026-08-30 renders audit found all three tiers carrying neither (9.2) — *"a reader who
 * reports it has nothing to quote"*. That is the difference between a support message a log line
 * can be matched to and one that says "the page went red", and it is invisible to every other kind
 * of check: the fallback rendered, the copy was right, the retry worked.
 */
function Throws(): never {
  throw new Error('the shape was wrong')
}

describe('every failure says which failure it is', () => {
  it('gives the route tier a reference and a timestamp', () => {
    // The route boundary IS the subject here, so tripping it is expected rather than a defect.
    renderingIntoAnErrorBoundary()
    vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <MemoryRouter>
        <RouteErrorBoundary>
          <Throws />
        </RouteErrorBoundary>
      </MemoryRouter>,
    )

    expect(screen.getByTestId('Route.Error.Reference').textContent).toMatch(
      /^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}$/,
    )
    expect(screen.getByTestId('Route.Error.Timestamp')).toHaveAttribute('title')
  })

  it('gives the region tier one too, so a panel failure is reportable on its own', () => {
    // The region boundary IS the subject here.
    renderingIntoAnErrorBoundary()
    vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <RegionErrorBoundary testId="Dashboard.Coverage" label="the coverage chart">
        <Throws />
      </RegionErrorBoundary>,
    )

    expect(screen.getByTestId('Dashboard.Coverage.RenderError.Reference').textContent).toHaveLength(
      6,
    )
  })
})
