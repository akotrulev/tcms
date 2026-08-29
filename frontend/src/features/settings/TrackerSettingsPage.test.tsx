import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey } from '../../api/types'
import { aProject, aTrackerBinding, aTrackerConnectedMember, aUserRef } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { Route, Routes } from 'react-router-dom'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { TrackerSettingsPage } from './TrackerSettingsPage'

/**
 * The project's bindings, and the D19 members list.
 *
 * The assertion this file exists for is the **field restriction**, and it is written as a closed
 * set rather than as a list of leaks somebody thought of: the natural implementation of
 * `/tracker/connected-members` reuses the identity view, which carries five fields this endpoint
 * must not — the hint, the account id, the expiry, the scopes and the error text. A test naming
 * those five would pass the day somebody adds a sixth.
 */

const PROJECT = 'p1'

function mockApi(keys: PermissionKey[], overrides: Record<string, unknown> = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(keys)
    if (path === `/projects/${PROJECT}`) return aProject({ id: PROJECT })
    if (path.endsWith('/tracker/bindings')) return overrides.bindings ?? [aTrackerBinding()]
    if (path.endsWith('/tracker/connected-members')) return overrides.members ?? []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/settings/trackers`}>
      {/* The route pattern, not a bare element: the page reads `projectId` from useParams, and
          TestProviders' MemoryRouter matches nothing on its own — so every gate saw an EMPTY id
          and the whole page rendered as unreachable rather than as refused. */}
      <Routes>
        <Route path="/projects/:projectId/settings/trackers" element={<TrackerSettingsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('TrackerSettingsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the bindings to a caller who holds integration:manage', async () => {
    mockApi(['integration:manage'])
    renderPage()

    expect(await screen.findByTestId('TrackerSettings.BindingsTable')).toHaveTextContent('PAY')
  })

  it('says who can configure it rather than showing an empty table, for a caller without the key', async () => {
    // The reads themselves cost `integration:manage`, so there is no read-only view. An empty
    // table would claim this project has no bindings, which is a fact this caller is not entitled
    // to — the same argument `NotificationSettingsPage` records.
    mockApi(['case:read'])
    renderPage()

    expect(await screen.findByTestId('TrackerSettings.Denied')).toBeInTheDocument()
    expect(screen.queryByTestId('TrackerSettings.BindingsTable')).not.toBeInTheDocument()
  })

  it('fetches nothing at all while the caller may not read it', async () => {
    const get = vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['case:read'])
      if (path === `/projects/${PROJECT}`) return aProject({ id: PROJECT })
      throw new Error(`unmocked read: ${path}`)
    }) as never)
    renderPage()

    await screen.findByTestId('TrackerSettings.Denied')
    // An ungated fetch would raise a global "Something didn't load" over a screen working exactly
    // as designed.
    expect(get.mock.calls.map(([path]) => path).some((p) => String(p).includes('/tracker/'))).toBe(false)
  })

  it('renders a member’s name and state, and nothing else about their credential (D19)', async () => {
    mockApi(['integration:manage'], {
      members: [
        aTrackerConnectedMember({
          user: aUserRef({ id: 'u9', displayName: 'Dana Reed' }),
          hosts: [
            {
              provider: 'JIRA_CLOUD',
              baseUrl: 'https://acme.atlassian.test',
              state: 'NEEDS_ATTENTION',
            },
          ],
        }),
      ],
    })
    renderPage()

    const row = await screen.findByTestId('TrackerSettings.MemberRow')
    expect(row).toHaveTextContent('Dana Reed')
    expect(row).toHaveTextContent('Needs attention')

    /*
      The closed set. Everything the row is allowed to say is asserted above; this asserts that
      what it says is *only* that, by comparing the row's own text against the three things it may
      contain. A future edit that reuses the identity view fails here regardless of which of its
      five extra fields it brings.
    */
    const said = (row.textContent ?? '').replace(/\s+/g, ' ').trim()
    const allowed = ['Dana Reed', 'https://acme.atlassian.test', 'Needs attention']
    const leftover = allowed.reduce((text, part) => text.replace(part, ''), said).trim()
    expect(leftover).toBe('')
  })

  it('says why the connected list is empty rather than implying nobody has connected', async () => {
    mockApi(['integration:manage'], { members: [] })
    renderPage()

    await waitFor(() => expect(screen.getByTestId('TrackerSettings.Members.Empty')).toBeInTheDocument())
  })
})
