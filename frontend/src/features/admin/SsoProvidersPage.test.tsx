import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { EmailDomains, IdentityProvider, PermissionKey } from '../../api/types'
import { anIdentityProvider } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { SsoProvidersPage } from './SsoProvidersPage'

/**
 * The provider list — and every assertion here is about something no type or gate can see.
 *
 *   1. **A caller without `company:idp` fires no read.** The server refuses this list rather than
 *      narrowing it, so an ungated request would 403 for every Test Lead who opened the page and
 *      raise a global "Something didn't load" over a screen behaving correctly.
 *   2. **Disabled providers are listed.** A list that hid them could not be used to enable one,
 *      and registration always produces a disabled provider — so the first row an administrator
 *      ever sees is a disabled one.
 *   3. **There is no Delete and no reveal.** Neither exists in the contract; both are the kind of
 *      affordance that gets added later by analogy with a neighbouring screen, and neither could
 *      work — `user_identity` rows reference a provider, and nothing in this app has the secret.
 */

const DOMAINS: EmailDomains = { domains: ['acme.test'] }

function mockApi(permissions: PermissionKey[], providers: IdentityProvider[] = [anIdentityProvider()]) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    // Anchored, so the sub-resource is not caught by the collection's prefix.
    if (path === '/identity-providers/email-domains') return DOMAINS
    if (path === '/identity-providers') return providers
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1">
      <SsoProvidersPage />
    </TestProviders>,
  )
}

describe('SsoProvidersPage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('does not fetch for a caller without company:idp, and says who can', async () => {
    const get = mockApi(['company:read', 'company:members'])
    renderPage()

    expect(await screen.findByTestId('Admin.Sso.Denied')).toHaveTextContent(/company administrator/i)
    expect(get).not.toHaveBeenCalledWith('/identity-providers', expect.anything())
    expect(screen.queryByTestId('Admin.Sso.Register')).not.toBeInTheDocument()
  })

  it('lists disabled providers rather than hiding them', async () => {
    mockApi(['company:read', 'company:idp'], [anIdentityProvider({ displayName: 'Acme SSO', enabled: false })])
    renderPage()

    expect(await screen.findByText('Acme SSO')).toBeInTheDocument()
    expect(screen.getByTestId('Admin.Sso.Row.Status')).toHaveTextContent(/disabled/i)
  })

  it('offers no delete, because there is none', async () => {
    mockApi(['company:read', 'company:idp'])
    renderPage()

    await screen.findByText('Acme Corporate SSO')
    expect(screen.queryByRole('button', { name: /delete|remove/i })).not.toBeInTheDocument()
  })

  it('never shows the secret and never offers to reveal it', async () => {
    mockApi(['company:read', 'company:idp'], [anIdentityProvider({ clientSecretSet: true })])
    renderPage()

    await screen.findByText('Acme Corporate SSO')
    expect(
      screen.queryByRole('button', { name: /reveal|show secret|copy secret/i }),
    ).not.toBeInTheDocument()
  })

  it('shows the claimed domains beside the providers, since the two together are what discovery resolves', async () => {
    mockApi(['company:read', 'company:idp'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Admin.Sso.Domains.Chip')).toHaveTextContent('acme.test'))
  })
})
