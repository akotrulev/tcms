import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { IdentityProvider } from '../../api/types'
import { anIdentityProvider } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { ProviderFormDialog } from './ProviderFormDialog'

/**
 * The provider form, and the one line in it that could blank a live credential.
 *
 * `clientSecret` is **required and nullable** on `IdentityProviderInput`, and the contract reads a
 * null as *keep* and an empty string as *rotate to an empty credential*. So the assertion here is
 * on the **payload**, not on the outcome: a save that sends `''` succeeds, reports success, and
 * breaks every sign-in through that provider at a time unconnected to the edit that caused it.
 *
 * This is the same failure `NotificationChannelDialog` guards, arriving with the opposite spelling
 * — there the keep is an absent property, here it is a present null — which is exactly why each
 * one has to be asserted rather than inferred from the other.
 */

function renderDialog(provider: IdentityProvider | null) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1">
      <ProviderFormDialog provider={provider} onClose={vi.fn()} />
    </TestProviders>,
  )
}

describe('ProviderFormDialog', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends a null clientSecret when the field is left blank on an edit, never an empty string', async () => {
    const user = userEvent.setup()
    const put = vi.spyOn(api, 'put').mockResolvedValue(anIdentityProvider())

    renderDialog(anIdentityProvider({ clientSecretSet: true }))
    await user.clear(screen.getByTestId('Admin.Sso.DisplayName'))
    await user.type(screen.getByTestId('Admin.Sso.DisplayName'), 'Renamed')
    await user.click(screen.getByTestId('Admin.Sso.Save'))

    const body = put.mock.calls[0][1] as Record<string, unknown>
    expect(body.clientSecret).toBeNull()
    expect(body.displayName).toBe('Renamed')
  })

  it('says the secret is kept rather than asking for it again', () => {
    renderDialog(anIdentityProvider({ clientSecretSet: true }))

    expect(screen.getByText(/leave blank to keep the stored secret/i)).toBeInTheDocument()
  })

  it('offers no Enable on registration, because registration cannot enable', () => {
    renderDialog(null)

    expect(screen.queryByTestId('Admin.Sso.Enabled')).not.toBeInTheDocument()
  })

  it('offers Enable on an edit, which is the only place going live can happen', () => {
    renderDialog(anIdentityProvider())

    expect(screen.getByTestId('Admin.Sso.Enabled')).toBeInTheDocument()
  })

  it('locks the key on an edit, because the server ignores it there', () => {
    renderDialog(anIdentityProvider())

    expect(screen.getByTestId('Admin.Sso.Key')).toBeDisabled()
  })

  it('refuses a plain-http issuer before the server has to, and says why', async () => {
    const user = userEvent.setup()
    const post = vi.spyOn(api, 'post').mockResolvedValue(anIdentityProvider())

    renderDialog(null)
    await user.type(screen.getByTestId('Admin.Sso.Key'), 'acme-sso')
    await user.type(screen.getByTestId('Admin.Sso.DisplayName'), 'Acme SSO')
    await user.type(screen.getByTestId('Admin.Sso.IssuerUri'), 'http://issuer.acme.test')
    await user.type(screen.getByTestId('Admin.Sso.ClientId'), 'tcms')
    await user.click(screen.getByTestId('Admin.Sso.Save'))

    expect(screen.getByText(/signing keys/i)).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })
})
