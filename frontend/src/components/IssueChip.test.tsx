import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { anIssueLink, aTrackerIssue } from '../test/fixtures'
import { TestProviders } from '../test/TestProviders'
import { createTestQueryClient } from '../test/queryClient'
import { drivingAFailedRead } from '../test/renderFailures'
import { IssueChip } from './IssueChip'

/**
 * The id-only tests (004 D17) and the two degraded states (D16).
 *
 * The rule under test is one sentence — **no label is ever derived from `external_url`** — and it
 * is the kind of rule that passes every review and then quietly breaks: parsing `PAY-991` out of a
 * stored URL is one regex, it looks right on every fixture anybody writes by hand, and it goes
 * wrong exactly after the event id-only was chosen to survive. So every fixture here has a URL
 * whose key does **not** match its id, and the assertions name both.
 */

const PROJECT = 'p1'

function mockLookup(body: unknown) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/tracker/issues/')) return body
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderChip(link = anIssueLink()) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <IssueChip projectId={PROJECT} link={link} />
    </TestProviders>,
  )
}

describe('IssueChip', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the key the FETCH returned, not the one in the stored URL', async () => {
    // The row's url says PAY-991 and the tracker says the issue is now PAY-1200 — a project move.
    // The chip must show today's key, with nothing written back to the row.
    mockLookup({ state: 'AVAILABLE', issue: aTrackerIssue({ key: 'PAY-1200', title: 'Moved' }) })
    renderChip()

    expect(await screen.findByTestId('IssueChip.Live')).toHaveTextContent('PAY-1200')
    expect(screen.queryByText(/PAY-991/)).not.toBeInTheDocument()
  })

  it('renders the stable id and never the URL’s key when the tracker will not answer', async () => {
    mockLookup({ state: 'UNAVAILABLE', issue: null })
    renderChip()

    const chip = await screen.findByTestId('IssueChip.Degraded')
    expect(chip).toHaveTextContent('10042')
    // The whole rule, asserted as an absence: the key is in the href and must not be in the label.
    expect(chip).not.toHaveTextContent('PAY-991')
  })

  it('keeps a working href on the degraded chip — the link is what still works', async () => {
    mockLookup({ state: 'UNAVAILABLE', issue: null })
    renderChip()

    const link = await screen.findByRole('link')
    expect(link).toHaveAttribute('href', 'https://acme.atlassian.test/browse/PAY-991')
  })

  it('offers the way out only when the viewer has not connected', async () => {
    mockLookup({ state: 'NOT_CONNECTED', issue: null })
    renderChip()

    expect(await screen.findByTestId('IssueChip.Connect')).toBeInTheDocument()
  })

  it('offers no connect link when the tracker is simply unavailable', async () => {
    // D16: the two render the same chip. What differs is that one of them is fixable by the reader,
    // and offering "connect" to somebody already connected would send them to a page that agrees.
    mockLookup({ state: 'UNAVAILABLE', issue: null })
    renderChip()

    await screen.findByTestId('IssueChip.Degraded')
    expect(screen.queryByTestId('IssueChip.Connect')).not.toBeInTheDocument()
  })

  it('degrades without fetching when the binding is gone (D18)', async () => {
    // Nothing cascades from deleting a binding: the link still renders and still works, and the
    // chip must not fire a lookup it has no binding to make.
    const get = vi.spyOn(api, 'get')
    renderChip(anIssueLink({ bindingId: null }))

    expect(await screen.findByTestId('IssueChip.Degraded')).toHaveTextContent('10042')
    expect(get).not.toHaveBeenCalled()
  })

  it('renders the degraded chip rather than an error when the lookup itself fails', async () => {
    // The read is expected to fail: a chip must degrade rather than take the case screen down
    // with it. Failure is a first-class state here (004 Phase 2), which is why the assertion is a
    // rendered chip and not an error boundary.
    drivingAFailedRead()
    vi.spyOn(api, 'get').mockRejectedValue(new Error('boom'))
    renderChip()

    expect(await screen.findByTestId('IssueChip.Degraded')).toBeInTheDocument()
  })

  it('does not collide two bindings holding the same numeric id (D17c)', async () => {
    // Identity is (provider, host, id). Two chips for the same id under different bindings must
    // ask two different questions — a shared cache entry would show one issue under both.
    const get = vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.includes('bindingId=tb1')) return { state: 'AVAILABLE', issue: aTrackerIssue({ key: 'PAY-1' }) }
      if (path.includes('bindingId=tb2')) {
        return { state: 'AVAILABLE', issue: aTrackerIssue({ key: 'OPS-9', bindingId: 'tb2' }) }
      }
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    render(
      <TestProviders client={createTestQueryClient()}>
        <IssueChip projectId={PROJECT} link={anIssueLink({ id: 'a', bindingId: 'tb1' })} testId="A" />
        <IssueChip projectId={PROJECT} link={anIssueLink({ id: 'b', bindingId: 'tb2' })} testId="B" />
      </TestProviders>,
    )

    expect(await screen.findByTestId('A.Live')).toHaveTextContent('PAY-1')
    expect(await screen.findByTestId('B.Live')).toHaveTextContent('OPS-9')
    expect(get).toHaveBeenCalledTimes(2)
  })
})
