import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, TimeoutError } from '../api/client'
import { drivingAFailedRead, drivingAFailedWrite, renderingIntoAnErrorBoundary } from '../test/renderFailures'
import { reportClientError, resetReportedErrors } from './reportClientError'

/**
 * The one sink every catch in the app funnels into — tested for what it *refuses* to report.
 *
 * Reporting is not the interesting half; suppression is. Three rules decide whether a fault leaves
 * this module at all, and each exists because of a way the reporter can make an incident worse:
 *
 *   - **A cancellation is not a fault.** An `AbortError` fires on every navigation away from a
 *     loading page. Reporting them buries the real faults under routine noise.
 *   - **A fingerprint is reported once.** A broken subtree re-renders, and its throw arrives again
 *     on each attempt.
 *   - **A window is capped.** An error inside a render loop fires thousands of times a second; an
 *     unbounded reporter turns one bug into a denial of service against the collector.
 *
 * None of the three is reachable by using the app, and none has a UI, so this is the only place
 * they can be exercised. Under `DEV` the transport writes to `window.__tcmsErrors`, which is the
 * seam these read — the same array the browser fault switches expose, so a green test here means
 * the thing a developer inspects by hand holds what it claims to.
 */

interface ReportLike {
  source: string
  message: string
  status: number | null
  correlationId: string | null
  context: Record<string, string> | null
}

function reports(): ReportLike[] {
  return (window as unknown as { __tcmsErrors?: ReportLike[] }).__tcmsErrors ?? []
}

beforeEach(() => {
  /*
    The three suspensions, and why this file takes all of them at once.

    `renderFailures` fails any test that leaves a boundary trip or a failed request in the sink —
    it reads `window.__tcmsErrors`, which is the same array this file asserts on. That check exists
    because a caught throw is invisible to every assertion not aimed at it; here every throw IS the
    assertion, and this file renders nothing at all, so there is no second thing on screen for the
    check to be protecting. Taken in `beforeEach` rather than per test because the reason is a
    property of the file, not of any one case.
  */
  renderingIntoAnErrorBoundary()
  drivingAFailedRead()
  drivingAFailedWrite()
  resetReportedErrors()
  ;(window as unknown as { __tcmsErrors?: ReportLike[] }).__tcmsErrors = []
  // The DEV transport is a `console.error`, and the rate-limit notice a `console.warn`. Both are
  // the module working correctly; letting them through would make a passing run look like a failing
  // one.
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.spyOn(console, 'warn').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
  resetReportedErrors()
})

describe('reportClientError — what it drops', () => {
  it('drops a cancellation', () => {
    reportClientError(new DOMException('aborted', 'AbortError'), 'query')

    expect(reports()).toHaveLength(0)
  })

  it('reports one fingerprint once, however many times it arrives', () => {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      reportClientError(new Error('the same broken render'), 'region-boundary')
    }

    expect(reports()).toHaveLength(1)
  })

  it('treats the same message from a different source as a different fault', () => {
    reportClientError(new Error('boom'), 'region-boundary')
    reportClientError(new Error('boom'), 'window-error')

    expect(reports().map((r) => r.source)).toEqual(['region-boundary', 'window-error'])
  })

  it('stops at the window cap and says so exactly once', () => {
    // Distinct messages, so the dedupe above is not what does the suppressing here.
    for (let n = 0; n < 50; n += 1) {
      reportClientError(new Error(`render loop ${n}`), 'root-boundary')
    }

    expect(reports()).toHaveLength(20)
    expect(console.warn).toHaveBeenCalledTimes(1)
  })
})

describe('reportClientError — what a report carries', () => {
  it('carries an ApiError’s status and correlation id', () => {
    reportClientError(new ApiError(503, 'Service Unavailable', null, 'corr-7'), 'query')

    expect(reports()[0]).toMatchObject({ status: 503, correlationId: 'corr-7' })
  })

  it('reads a timeout as 408, which no response ever carried', () => {
    // The budget expired on our side; there is no response and so no status. 408 is the reading
    // that lets a collector group them with the server-side timeouts rather than with `null`.
    reportClientError(new TimeoutError(15_000), 'query')

    expect(reports()[0]?.status).toBe(408)
  })

  it('carries no status for a plain render throw', () => {
    reportClientError(new Error('cannot read properties of undefined'), 'route-boundary')

    expect(reports()[0]).toMatchObject({ status: null, correlationId: null })
  })

  it('names a non-Error throw rather than dropping it', () => {
    // `throw 'string'` is legal JavaScript and a library does it eventually. A reporter that only
    // understood `Error` would silently lose the fault.
    reportClientError('a bare string', 'unhandled-rejection')

    expect(reports()[0]?.message).toBe('a bare string')
  })

  it('passes the call site’s context through', () => {
    reportClientError(new Error('region failed'), 'region-boundary', { region: 'RunDetail' })

    expect(reports()[0]?.context).toEqual({ region: 'RunDetail' })
  })
})
