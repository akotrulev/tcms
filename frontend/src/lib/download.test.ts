import { afterEach, describe, expect, it, vi } from 'vitest'

import { reportFilename } from './download'

/**
 * The generated filename, which is the half of `download.ts` that has a decision in it.
 *
 * `saveBlob` is the DOM half and belongs to a component-level test; this is the naming rule, and
 * the rule has two parts worth pinning: the extension comes from the **format the person asked
 * for** rather than from the blob's media type, and everything a filesystem could object to is
 * flattened before it reaches a Save dialog.
 */

afterEach(() => {
  vi.useRealTimers()
})

describe('reportFilename', () => {
  it('names the file for the type and the day, with the requested format as the extension', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-29T11:22:33Z'))

    expect(reportFilename('run-summary', 'PDF')).toBe('run-summary-2026-08-29.pdf')
  })

  it('flattens anything a filesystem would object to', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-29T00:00:00Z'))

    // A report type carrying a slash would otherwise read as a path segment in the Save dialog.
    expect(reportFilename('quarterly/exec summary', 'csv')).toBe(
      'quarterly-exec-summary-2026-08-29.csv',
    )
  })

  it('lower-cases the extension whatever case the job echoed', () => {
    expect(reportFilename('x', 'HtmL').endsWith('.html')).toBe(true)
  })
})
