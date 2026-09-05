import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Client, PermissionKey } from '../../api/types'
import { aClient } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ClientsPage } from './ClientsPage'

/**
 * `clients.html` and `clients-empty.html`.
 *
 * **The empty case is the module's acceptance criterion**, not an edge of it, and it is the first
 * test here for that reason: zero clients is a complete, supported state (scope D1 rule 2), and this
 * page is the only trace of clients a company in that state ever sees. A test that only proved rows
 * render would pass on a build that told a one-person company it had unfinished setup.
 *
 * The list arm is anchored — `/^\/clients(\?|$)/` cannot catch `/clients/{clientId}`, which answers
 * a `ClientDetail` where this one answers an array. Third arm in the tree to pay that lesson.
 */

const ACME = aClient({ id: 'cl-acme', name: 'Acme Retail', projectCount: 2, contactCount: 3 })
const BERGSTROM = aClient({
  id: 'cl-berg',
  name: 'Bergstrom AG',
  location: 'Zürich',
  industry: 'logistics',
  projectCount: 1,
  contactCount: 2,
  status: 'ARCHIVED',
})

function mockReads({
  clients,
  permissions = ['client:read', 'client:create'] as PermissionKey[],
}: {
  clients: Client[]
  permissions?: PermissionKey[]
}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (/^\/clients(\?|$)/.test(path)) {
      return path.includes('status=ARCHIVED')
        ? clients.filter((client) => client.status === 'ARCHIVED')
        : clients.filter((client) => client.status === 'ACTIVE')
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(route = '/clients') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route={route}>
      <Routes>
        <Route path="/clients" element={<ClientsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Clients', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('explains what a client is for, and offers the first one, when the company has none', async () => {
    mockReads({ clients: [] })
    renderPage()

    expect(await screen.findByText(/No clients yet/i)).toBeInTheDocument()
    // The copy has to say never needing one is fine — a company building its own products is the
    // owner's own case, and this page must not read as unfinished setup to them.
    expect(screen.getByTestId('Clients.Region.Empty')).toHaveTextContent(
      /you may never need one/i,
    )
    expect(screen.getByTestId('Clients.Region.Empty')).toHaveTextContent(/never signs in/i)
    expect(screen.getByTestId('Clients.AddFirst')).toBeEnabled()

    // No table, and no status filter: a filter over an empty list is a control that can only ever
    // return to where it started.
    expect(screen.queryByTestId('Clients.Table')).toBeNull()
    expect(screen.queryByTestId('Clients.Filters')).toBeNull()
  })

  it('lists the clients and their project counts once one exists', async () => {
    mockReads({ clients: [ACME, BERGSTROM] })
    renderPage()

    expect(await screen.findByTestId('Clients.Row.cl-acme')).toHaveTextContent('Acme Retail')
    expect(screen.getByTestId('Clients.Row.cl-acme')).toHaveTextContent('2')
    expect(screen.getByTestId('Clients.Filters')).toBeInTheDocument()
    // The archived one is not in the default list — the server filters, and ACTIVE is the default.
    expect(screen.queryByTestId('Clients.Row.cl-berg')).toBeNull()
  })

  it('keeps the status filter in the URL, so the list can be sent to somebody', async () => {
    mockReads({ clients: [ACME, BERGSTROM] })
    renderPage('/clients?status=ARCHIVED')

    expect(await screen.findByTestId('Clients.Row.cl-berg')).toHaveTextContent('Bergstrom AG')
    expect(screen.queryByTestId('Clients.Row.cl-acme')).toBeNull()

    await userEvent.click(screen.getByTestId('Clients.Status.ACTIVE'))
    expect(await screen.findByTestId('Clients.Row.cl-acme')).toBeInTheDocument()
  })

  it('refuses a reader without client:read rather than showing them an empty list', async () => {
    mockReads({ clients: [], permissions: [] })
    renderPage()

    // An empty list would be a claim about the company told to somebody who may not ask — the
    // same argument that gates the rail entry. The refusal names who does hold the key.
    // findByText, not findByTestId: the gate renders 'Checking your permissions…' while
    // /me/permissions is in flight, so a testid query resolves on the PENDING copy and asserts
    // nothing about the refusal.
    expect(await screen.findByText(/Account Managers/i)).toBeInTheDocument()
    expect(screen.queryByTestId('Clients.Region')).toBeNull()
    expect(screen.queryByTestId('Clients.Table')).toBeNull()
  })
})
