import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { anAssistant } from '../../test/fixtures'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { AssistantsPage } from './AssistantsPage'

/**
 * `profile-assistants.html`.
 *
 * What is pinned here is the pair of decisions the screen would be wrong without: a **revoked
 * assistant stays in the list** — its acts are in every issue's history attributed through it, and
 * a vanished row would leave a reader reading "via laptop-agent" beside a profile that says no such
 * thing existed — and **"never used" is its own state**, because a credential that was issued and
 * never presented is a setup somebody did not finish, which reading it as "idle" hides.
 */

function mockList(rows: unknown[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path === '/me/assistants') return rows
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route="/account/assistants">
      <AssistantsPage />
    </TestProviders>,
  )
}

describe('My AI assistants', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('invites a first connection rather than showing an empty box', async () => {
    mockList([])
    renderPage()
    expect(await screen.findByTestId('Assistants.Empty')).toBeInTheDocument()
  })

  it('keeps a revoked assistant in the list rather than dropping the row', async () => {
    mockList([
      anAssistant({ id: 'a1', name: 'claude-code', lastUsedAt: '2026-01-02T00:00:00Z' }),
      anAssistant({ id: 'a2', name: 'laptop-agent', revokedAt: '2026-01-03T00:00:00Z' }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('Assistants.Row')).toHaveLength(2))
    expect(screen.getByTestId('Assistants.Row.Revoked')).toBeInTheDocument()
    // And the revoked one offers no Revoke button — only the live row does.
    expect(screen.getAllByTestId('Assistants.Row.Revoke')).toHaveLength(1)
  })

  it('says a never-used assistant was never used, not that it is idle', async () => {
    mockList([anAssistant({ lastUsedAt: null })])
    renderPage()
    expect(await screen.findByText('Never used')).toBeInTheDocument()
  })

  it('names the project an assistant is pinned to, and says so when it is pinned to none', async () => {
    mockList([
      anAssistant({ id: 'a1', projectId: 'p1', projectKey: 'ACME' }),
      anAssistant({ id: 'a2', name: 'wide' }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('Assistants.Row.Scope')).toHaveLength(2))
    const scopes = screen.getAllByTestId('Assistants.Row.Scope')
    expect(scopes[0]).toHaveTextContent('ACME')
    expect(scopes[1]).toHaveTextContent('Every project I can see')
  })

  it('revokes through the endpoint that owns the caller’s own credentials', async () => {
    mockList([anAssistant({ id: 'a1' })])
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    await userEvent.click(await screen.findByTestId('Assistants.Row.Revoke'))
    await waitFor(() => expect(del).toHaveBeenCalledWith('/me/assistants/a1', expect.anything()))
  })
})
