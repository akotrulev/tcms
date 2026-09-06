import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { ClientBillingSummary, ClientDetail, PermissionKey, RateCardSlot } from '../../api/types'
import {
  aClient,
  aClientBillingRow,
  aClientBillingSummary,
  aClientContact,
  aClientDetail,
  aClientProject,
  aClientRef,
  aRateCard,
  aRateCardSlot,
  money,
} from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ClientPage } from './ClientPage'

/**
 * `client.html`.
 *
 * ~~Two of the assertions here are about what is **absent**, and both are boundaries rather than
 * omissions: the hours-and-invoices figures are M8's (018 D-g) and the contracts panel belongs to
 * no module at all (018 D-h).~~ → **half superseded 2026-09-05 (019 T6): M8 landed**, so the
 * hours-and-invoices table is asserted rather than its absence. The contracts panel still belongs
 * to no module and its absence is still pinned here.
 *
 * What replaces the reserved-slot assertion is **the boundary itself, from both sides** (019 D-i):
 * an Account Manager's table has **no** value column and no currency anywhere in it, and Finance's
 * has both. A test that only checked Finance's view would prove nothing about the one reader this
 * flag exists for — and one that only checked the Account Manager's would go green on a build that
 * had dropped the column entirely.
 *
 * The third assertion is scope D1's, and it is the one that module rests on: **a contact is not an
 * account**, and the panel has to say so where somebody adding one will read it.
 */

const DETAIL: ClientDetail = aClientDetail({
  client: aClient({ id: 'cl-acme', name: 'Acme Retail', location: 'Stockholm', industry: 'retail' }),
  contacts: [aClientContact({ id: 'cc-1', name: 'Hanna Lund', role: 'Product owner' })],
  projects: [aClientProject({ id: 'p-1', key: 'ACME', name: 'Acme Storefront' })],
})

/** An Account Manager's answer: the hours, the invoices they are on, and no amount anywhere. */
const AM_BILLING = aClientBillingSummary({
  approvedHoursToDate: 1284,
  approvedHoursThisQuarter: 418,
  invoiceCount: 6,
  lastIssuedAt: '2026-09-02T10:00:00Z',
  rows: [
    aClientBillingRow({
      periodLabel: 'Aug 2026',
      periodStart: '2026-08-01',
      approvedHours: 168,
      invoiceId: 'inv-41',
      invoiceNumber: 'INV-2026-041',
      invoiceStatus: 'ISSUED',
    }),
    aClientBillingRow({
      periodLabel: 'Sep 2026',
      periodStart: '2026-09-01',
      approvedHours: 44,
      invoiceId: 'inv-42',
      invoiceStatus: 'DRAFT',
    }),
  ],
})

/** The same answer for Finance — the flag is the SERVER's verdict, so the rows gain their amounts. */
const FINANCE_BILLING = aClientBillingSummary({
  ...AM_BILLING,
  amountsVisible: true,
  rows: AM_BILLING.rows.map((row, index) => ({
    ...row,
    amount: money({ amount: index === 0 ? 18480 : 4840 }),
  })),
})

function mockReads({
  detail = DETAIL,
  permissions = ['client:read', 'client:edit'] as PermissionKey[],
  billing = AM_BILLING,
  rateCards = [],
}: {
  detail?: ClientDetail
  permissions?: PermissionKey[]
  billing?: ClientBillingSummary
  rateCards?: RateCardSlot[]
} = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    // Anchored ahead of the client detail arm, so the billing sub-resource is not caught by the
    // `/clients/{id}` prefix — the collision the harness rules name explicitly.
    if (/^\/clients\/[^/]+\/billing$/.test(path)) return billing
    if (path === '/rate-cards') return rateCards
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

  it('renders no value column and no currency at all for an Account Manager', async () => {
    /*
      019 D-i, and the assertion is the ABSENCE of a column rather than an empty one. `client.html`
      says it from the other side: *"Amounts are absent for an Account Manager. Switch the top bar
      to Finance and this table gains a value column."* An empty or dashed column would be the
      renderer guessing, which is what the server-evaluated flag exists to prevent.
    */
    mockReads()
    renderPage()

    // The panel's heading renders while the read is in flight — the tiles are what waiting on the
    // billing read actually means here, so the `find` goes on one of those.
    expect(await screen.findByTestId('Client.Billing.Kpi.Hours')).toHaveTextContent('1284h')
    expect(screen.getByText('Hours and invoices to date')).toBeInTheDocument()
    expect(screen.getByTestId('Client.Billing.Kpi.Invoices')).toHaveTextContent('6')
    expect(screen.getByText('168h')).toBeInTheDocument()
    expect(screen.getByText('INV-2026-041')).toBeInTheDocument()

    expect(screen.queryByRole('columnheader', { name: 'Value' })).toBeNull()
    // And the boundary rather than only the column: no currency anywhere on the rendered page.
    expect(screen.queryByText(/€|CHF|\$/)).toBeNull()

    // The rate card panel is the mockup's refusal for this reader, not a placeholder for a feature.
    expect(screen.getByTestId('Client.RateCard.RefusedBody')).toHaveTextContent(
      /Rates are Finance’s\. An Account Manager knows the client and the hours; never the money\./,
    )

    // Still nobody's: the contracts panel belongs to no module at all (018 D-h).
    expect(screen.queryByText(/^MSA/i)).toBeNull()
  })

  it('gains the value column, and the rate card, when the server says amounts are visible', async () => {
    mockReads({
      permissions: ['client:read', 'billing:read', 'ratecard:read'],
      billing: FINANCE_BILLING,
      rateCards: [
        aRateCardSlot({
          client: aClientRef({ id: 'cl-acme', name: 'Acme Retail' }),
          card: aRateCard({ currency: 'EUR', entries: [] }),
        }),
      ],
    })
    renderPage()

    expect(await screen.findByRole('columnheader', { name: 'Value' })).toBeInTheDocument()
    expect(screen.getByTestId('Client.Billing.Value.2026-08-01.p1')).toHaveTextContent('€18,480.00')

    // One place edits a rate card, and it is the Billing screen — this panel is a link into it.
    expect(screen.getByTestId('Client.RateCard.Currency')).toHaveTextContent('EUR')
    expect(screen.getByTestId('Client.RateCard.Open')).toHaveAttribute('href', '/billing')
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
