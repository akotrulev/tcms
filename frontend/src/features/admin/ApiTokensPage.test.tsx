import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { ApiToken } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { ApiTokensPage } from './ApiTokensPage'

/**
 * The token list — and the three things about it that no gate, type or lint rule can see.
 *
 *   1. **A caller without `token:issue` never fires the read.** The server refuses this list
 *      rather than narrowing it, so an ungated request would 403 for every Test Lead who opened
 *      the page and raise a global "Something didn't load" over a screen behaving correctly.
 *   2. **Revoke is disabled on a revoked row and ENABLED on an expired one.** The two dead states
 *      look alike and are not: closing a lapsed credential deliberately is a real act, and a
 *      screen that collapsed them would take it away.
 *   3. **A token with no project renders the wide sentence, never a blank cell.** The row with the
 *      largest reach on the page must not look like the one with no scope.
 *
 * Every assertion about a *disabled* control waits for the panel to resolve first: the page defers
 * on `Gate.pending`, so a test that asserted before the answer landed would be reading a screen
 * with nothing on it (§66's vacuous-barrier finding, in this page's shape).
 */

const LIVE: ApiToken = {
  id: 'tok-live',
  name: 'Nightly regression',
  tokenPrefix: 'tcms_live001',
  ownerId: 'user-1',
  roleId: 'role-automation',
  roleName: 'Automation Service',
  projectId: null,
  expiresAt: null,
  lastUsedAt: null,
  revokedAt: null,
  createdAt: '2026-08-01T09:00:00Z',
}

const EXPIRED: ApiToken = {
  ...LIVE,
  id: 'tok-expired',
  name: 'Release candidate smoke',
  tokenPrefix: 'tcms_exp0002',
  projectId: 'p1',
  expiresAt: '2026-01-01T00:00:00Z',
}

const REVOKED: ApiToken = {
  ...LIVE,
  id: 'tok-revoked',
  name: 'Old laptop',
  tokenPrefix: 'tcms_rev0003',
  revokedAt: '2026-07-01T00:00:00Z',
}

const PROJECTS = [
  {
    id: 'p1',
    key: 'OLB',
    name: 'Acme Online Banking',
    description: null,
    archived: false,
    caseCount: 0,
    myRole: 'Test Lead',
  },
]

const USERS = [
  {
    id: 'user-1',
    displayName: 'Ana Petrova',
    email: 'ana@example.com',
    avatarUrl: null,
    status: 'ACTIVE',
    lastLoginAt: null,
    teams: [],
  },
]

function mockApi(permissions: string[], tokens: ApiToken[] = [LIVE, EXPIRED, REVOKED]) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (path.startsWith('/api-tokens')) return tokens
    if (path.startsWith('/projects')) return PROJECTS
    if (path.startsWith('/users')) return USERS
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1">
      <ApiTokensPage />
    </TestProviders>,
  )
}

describe('ApiTokensPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the company tokens for a caller who may issue them', async () => {
    mockApi(['company:read', 'token:issue'])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Tokens.Row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByTestId('Admin.Tokens.Row.Prefix')).toHaveTextContent('tcms_live001')
    expect(within(rows[0]).getByTestId('Admin.Tokens.Row.Status')).toHaveTextContent('Live')
    expect(within(rows[1]).getByTestId('Admin.Tokens.Row.Status')).toHaveTextContent('Expired')
    expect(within(rows[2]).getByTestId('Admin.Tokens.Row.Status')).toHaveTextContent('Revoked')
  })

  it('does not request the list at all for a caller who may not read it', async () => {
    // THE HEADLINE. The list is refused, not filtered, so firing it is a guaranteed 403 and a
    // global error notification over a screen that is working.
    const get = mockApi(['company:read', 'company:members'])
    renderPage()

    expect(await screen.findByTestId('Admin.Tokens.Denied')).toHaveTextContent(
      /do not have permission/i,
    )
    expect(screen.queryByTestId('Admin.Tokens.Grid')).not.toBeInTheDocument()
    expect(
      vi.mocked(get).mock.calls.filter(([path]) => String(path).startsWith('/api-tokens')),
    ).toHaveLength(0)
  })

  it('disables Issue a token, rather than hiding it, for a caller who may not', async () => {
    mockApi(['company:read', 'company:members'])
    renderPage()

    await screen.findByTestId('Admin.Tokens.Denied')
    expect(screen.getByTestId('Admin.Tokens.Issue')).toBeDisabled()
  })

  it('offers Revoke on an expired token and refuses it on an already-revoked one', async () => {
    // The distinction `tokenState` exists for, at the one place it becomes an affordance.
    mockApi(['company:read', 'token:issue'])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Tokens.Row')
    await waitFor(() =>
      expect(within(rows[0]).getByTestId('Admin.Tokens.Row.Revoke')).toBeEnabled(),
    )
    expect(within(rows[1]).getByTestId('Admin.Tokens.Row.Revoke')).toBeEnabled()
    expect(within(rows[2]).getByTestId('Admin.Tokens.Row.Revoke')).toBeDisabled()
  })

  it('says an unpinned token reaches every project, and names the project when pinned', async () => {
    mockApi(['company:read', 'token:issue'])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Tokens.Row')
    expect(within(rows[0]).getByTestId('Admin.Tokens.Row.Scope')).toHaveTextContent(/every project/i)
    expect(within(rows[1]).getByTestId('Admin.Tokens.Row.Scope')).toHaveTextContent(
      'Acme Online Banking',
    )
  })

  it('resolves the owner to a name rather than showing a uuid', async () => {
    mockApi(['company:read', 'token:issue'])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Tokens.Row')
    expect(within(rows[0]).getByTestId('Admin.Tokens.Row.Owner')).toHaveTextContent('Ana Petrova')
  })

  it('states that a token without an expiry does not expire, rather than leaving the cell blank', async () => {
    mockApi(['company:read', 'token:issue'])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Tokens.Row')
    expect(within(rows[0]).getByTestId('Admin.Tokens.Row.Expires')).toHaveTextContent(
      /does not expire/i,
    )
  })

  it('says a token has never been used, and shows a date-time once it has', async () => {
    // Both halves matter and only one of them is cosmetic. "Never used" is the answer an
    // administrator deciding what to revoke is actually looking for, so a blank cell would report
    // "we don't know" where the product knows. And the used value renders as a date-time rather
    // than "3 minutes ago": the server keeps it only to within an hour by default, so a relative
    // phrase would claim a precision the column does not have.
    mockApi(['company:read', 'token:issue'], [
      LIVE,
      { ...REVOKED, lastUsedAt: '2026-07-30T11:25:00Z' },
    ])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Tokens.Row')
    expect(within(rows[0]).getByTestId('Admin.Tokens.Row.LastUsed')).toHaveTextContent(
      /never used/i,
    )
    expect(within(rows[1]).getByTestId('Admin.Tokens.Row.LastUsed')).not.toHaveTextContent(
      /never used/i,
    )
    expect(within(rows[1]).getByTestId('Admin.Tokens.Row.LastUsed')).toHaveTextContent(/2026/)
  })

  it('invites the first token when the company has none', async () => {
    mockApi(['company:read', 'token:issue'], [])
    renderPage()

    expect(await screen.findByTestId('Admin.Tokens.List.Empty')).toHaveTextContent(/no tokens yet/i)
  })
})
