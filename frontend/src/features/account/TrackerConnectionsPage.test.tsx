import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { aTrackerIdentity } from '../../test/fixtures'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { TrackerConnectionsPage } from './TrackerConnectionsPage'

/**
 * The caller's own tracker connections (004 D2, Phase 1).
 *
 * The two assertions worth having are about **where a fact is allowed to be shown**. The expiry is
 * on the first screen rather than in a drawer, because Jira Cloud enforces a one-year maximum and
 * every Jira Cloud row therefore has a death date by construction. And `lastError` — what the
 * tracker said about *this person's* credential — is shown here and nowhere else: the D19 members
 * list on the project settings page is forbidden from carrying it.
 */

function mockIdentities(rows: unknown[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path === '/me/tracker-identities') return rows
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route="/account/trackers">
      <TrackerConnectionsPage />
    </TestProviders>,
  )
}

describe('TrackerConnectionsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('invites a first connection rather than showing an empty box', async () => {
    mockIdentities([])
    renderPage()
    expect(await screen.findByTestId('TrackerConnections.List.Empty')).toBeInTheDocument()
  })

  it('shows the token expiry on the row, not behind a click', async () => {
    mockIdentities([aTrackerIdentity({ expiresAt: '2027-01-01T00:00:00Z' })])
    renderPage()
    expect(await screen.findByTestId('TrackerConnections.Expiry')).toBeInTheDocument()
  })

  it('shows what the tracker last said about this person’s credential', async () => {
    mockIdentities([aTrackerIdentity({ lastError: 'Unauthorized (401)' })])
    renderPage()
    expect(await screen.findByTestId('TrackerConnections.LastError')).toHaveTextContent('401')
  })

  it('reports the result of a test rather than leaving the row unchanged', async () => {
    // The check is a mutation and its answer is rendered from the mutation, because pressing it IS
    // the point: a cached "ok" from a minute ago cannot tell somebody whether the token they just
    // rotated works.
    mockIdentities([aTrackerIdentity()])
    vi.spyOn(api, 'post').mockResolvedValue({
      ok: false,
      checkedAt: '2026-01-01T00:00:00Z',
      error: 'The tracker rejected the credential.',
    } as never)
    renderPage()

    await userEvent.click(await screen.findByTestId('TrackerConnections.Test'))
    expect(await screen.findByTestId('TrackerConnections.CheckResult')).toHaveTextContent(
      'rejected the credential',
    )
  })

  it('says what disconnecting does and does not do, before it happens', async () => {
    mockIdentities([aTrackerIdentity()])
    renderPage()

    await userEvent.click(await screen.findByTestId('TrackerConnections.Disconnect'))
    const dialog = await screen.findByTestId('TrackerConnections.DisconnectDialog')
    // The consequence is named because the obvious guess is wrong: nothing is deleted, links keep
    // working, and only previewing stops.
    expect(dialog).toHaveTextContent(/stay where they are/i)
  })
})
