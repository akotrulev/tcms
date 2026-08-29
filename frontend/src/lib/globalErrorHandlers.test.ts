import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { installGlobalErrorHandlers } from './globalErrorHandlers'
import { drivingAFailedRead, drivingAFailedWrite, renderingIntoAnErrorBoundary } from '../test/renderFailures'
import { resetReportedErrors } from './reportClientError'

/**
 * The two listeners that catch what React boundaries structurally cannot: a throw inside an event
 * handler, and a promise nobody awaited.
 *
 * Neither path can be exercised through a component. A boundary never sees them — that is the whole
 * reason this module exists — so a test that rendered something and waited would prove only that
 * the boundary stayed quiet, which it would have done with no listeners installed at all. The
 * events are dispatched directly here instead.
 *
 * The install-once latch is the other subject, and it is not a nicety: Vite's HMR re-executes a
 * module, and two installs report every fault twice. A double report is worse than a missing one
 * because it looks like the fault happened twice.
 */

interface ReportLike {
  source: string
  message: string
  context: Record<string, string> | null
}

function reports(): ReportLike[] {
  return (window as unknown as { __tcmsErrors?: ReportLike[] }).__tcmsErrors ?? []
}

beforeEach(() => {
  // Same reason as `reportClientError.test.ts`: the sink is the subject, and this file renders
  // nothing for the boundary check to be protecting.
  renderingIntoAnErrorBoundary()
  drivingAFailedRead()
  drivingAFailedWrite()
  resetReportedErrors()
  ;(window as unknown as { __tcmsErrors?: ReportLike[] }).__tcmsErrors = []
  vi.spyOn(console, 'error').mockImplementation(() => {})

  // The latch is module state, so it survives between test files as well as between tests. The
  // listeners it guards are installed against this file's `window` exactly once, which is what the
  // module promises — every case below therefore dispatches against the same pair.
  installGlobalErrorHandlers()
})

afterEach(() => {
  vi.restoreAllMocks()
  resetReportedErrors()
})

describe('installGlobalErrorHandlers — the handler throw', () => {
  it('reports an error event as window-error', () => {
    window.dispatchEvent(new ErrorEvent('error', { error: new Error('onClick threw'), message: 'onClick threw' }))

    expect(reports()).toMatchObject([{ source: 'window-error', message: 'Error: onClick threw' }])
  })

  it('reports an error event that carries only a message', () => {
    // A cross-origin script gives `error: null` and a message alone. Reporting nothing would make
    // the loudest class of fault the one that leaves no trace.
    window.dispatchEvent(new ErrorEvent('error', { message: 'Script error.' }))

    expect(reports()[0]?.message).toContain('Script error.')
  })

  it('marks a failed dynamic import as a chunk load rather than as our bug', () => {
    // A stale build, not a defect in this code. The boundaries own the recovery; the report exists
    // so a deploy that stranded open tabs is visible as that in the logs.
    const stale = new Error('Failed to fetch dynamically imported module: /assets/CaseList-abc.js')
    window.dispatchEvent(new ErrorEvent('error', { error: stale, message: stale.message }))

    expect(reports()[0]?.context).toEqual({ kind: 'chunk-load' })
  })
})

describe('installGlobalErrorHandlers — the floating promise', () => {
  it('reports an unhandled rejection', () => {
    dispatchRejection(new Error('a void call rejected'))

    expect(reports()).toMatchObject([
      { source: 'unhandled-rejection', message: 'Error: a void call rejected' },
    ])
  })

  it('carries no chunk-load context for an ordinary rejection', () => {
    dispatchRejection(new Error('ordinary failure'))

    expect(reports()[0]?.context).toBeNull()
  })
})

describe('installGlobalErrorHandlers — the latch', () => {
  it('does not double-report when installed again', () => {
    installGlobalErrorHandlers()
    installGlobalErrorHandlers()

    window.dispatchEvent(new ErrorEvent('error', { error: new Error('once'), message: 'once' }))

    expect(reports()).toHaveLength(1)
  })
})

/**
 * jsdom implements `PromiseRejectionEvent` in name only — constructing one requires a real promise
 * it would then leave unhandled, which is its own failure. A plain `Event` carrying `reason` is
 * what the listener actually reads.
 */
function dispatchRejection(reason: unknown): void {
  const event = new Event('unhandledrejection') as Event & { reason: unknown }
  event.reason = reason
  window.dispatchEvent(event)
}
