import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { ClientDetail, PermissionKey } from '../../api/types'
import { aClient, aClientContact, aClientDetail, aClientProject } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ClientPage } from './ClientPage'

/**
 * `client.html`.
 *
 * Two of the assertions here are about what is **absent**, and both are boundaries rather than
 * omissions: the hours-and-invoices figures are M8's (018 D-g) and the contracts panel belongs to
 * no module at all (018 D-h). A test that only checked what renders would go green on a build that
 * had quietly grown a rate card.
 *
 * The third is scope D1's, and it is the one this module rests on: **a contact is not an account**,
 * and the panel has to say so where somebody adding one will read it.
 */

const DETAIL: ClientDetail = aClientDetail({
  client: aClient({ id: 'cl-acme', name: 'Acme Retail', location: 'Stockholm', industry: 'retail' }),
  contacts: [aClientContact({ id: 'cc-1', name: 'Hanna Lund', role: 'Product owner' })],
  projects: [aClientProject({ id: 'p-1', key: 'ACME', name: 'Acme Storefront' })],
})

function mockReads({
  detail = DETAIL,
  permissions = ['client:read', 'client:edit'] as PermissionKey[],
}: { detail?: ClientDetail; permissions?: PermissionKey[] } = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (/^\/clients\/[^/]+$/.test(path)) return detail
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/clients/cl-acme">
      <Routes>
        <Route path="/clients/:clientId" element={<ClientPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Client detail', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the client, its projects and its contacts in one read', async () => {
    mockReads()
    renderPage()

    expect(await screen.findByTestId('Client.Project.p-1')).toHaveTextContent('Acme Storefront')
    expect(screen.getByTestId('Client.Contacts.Row.cc-1')).toHaveTextContent('Hanna Lund')
    expect(screen.getByTestId('Client.Projects.Note')).toHaveTextContent(
      /A project has at most one client — or none/i,
    )
  })

  it('says a contact is never a login, where somebody adding one will read it', async () => {
    mockReads()
    renderPage()

    expect(await screen.findByTestId('Client.Contacts.NotLogins')).toHaveTextContent(
      /None of them can sign in, and adding one creates no account/i,
    )

    // And the form that adds one offers no field that could become a credential — scope D1's rule
    // expressed where a reader can check it, not only in the contract.
    await userEvent.click(screen.getByTestId('Client.Contacts.Add'))
    const form = await screen.findByTestId('Client.Contacts.Form')
    expect(form).toHaveTextContent(/Name/)
    expect(form).not.toHaveTextContent(/password/i)
    expect(form).not.toHaveTextContent(/invite/i)
  })

  it('reserves the hours-and-invoices region for M8 rather than half-building it', async () => {
    mockReads()
    renderPage()

    // The slot exists and explains itself — that is the seam M8 lands into (018 D-g).
    expect(await screen.findByTestId('Client.Billing.Reserved')).toHaveTextContent(
      /invoices raised against them will appear here/i,
    )

    // But nothing on this page carries money, and nothing draws the contracts panel the mockup
    // shows: that one belongs to no module at all (018 D-h).
    const page = screen.getByTestId('Client.Billing').closest('div')
    expect(page).not.toBeNull()
    expect(screen.queryByText(/rate card/i)).toBeNull()
    expect(screen.queryByText(/^MSA/i)).toBeNull()
    expect(screen.queryByText(/€/)).toBeNull()
  })

  it('says so plainly when no project names the client', async () => {
    mockReads({ detail: aClientDetail({ client: DETAIL.client, contacts: [], projects: [] }) })
    renderPage()

    expect(await screen.findByTestId('Client.Projects.None')).toHaveTextContent(
      /No project names this client yet/i,
    )
    expect(screen.getByTestId('Client.Contacts.None')).toHaveTextContent(/never a login/i)
  })
})
