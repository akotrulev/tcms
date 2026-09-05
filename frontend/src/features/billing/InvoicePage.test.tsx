import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { InvoiceDetail, PermissionKey } from '../../api/types'
import {
  aBudget,
  aClientContact,
  aClientRef,
  anInvoice,
  anInvoiceDetail,
  anInvoiceEntry,
  anInvoiceLine,
  excludedHours,
  money,
} from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { InvoicePage } from './InvoicePage'

/**
 * `invoice.html`, both states.
 *
 * The four claims this file pins, and none of them is visible to any other test in the suite:
 *
 * 1. **An issued invoice has no Issue and no Discard** — *absent*, not disabled. This is the one
 *    place in the app that deliberately does not follow §31, and the reason is that the operations
 *    do not exist rather than being refused: there is no endpoint anywhere in this module that can
 *    change an issued line (019 D-c). A greyed *Issue invoice* would be inventing a state machine
 *    the server does not have.
 * 2. **`Preview PDF` is nowhere in either state.** The mockup draws it, there is no endpoint behind
 *    it, and a control that does nothing is worse than one that is not there (019 D-q).
 * 3. **Both exclusion buckets render.** The second — approved hours no rate card can price — is the
 *    one a naive implementation loses, because from inside a join it looks exactly like a priced
 *    hour (019 D-g). It is asserted on a draft whose Lines table is otherwise complete, which is
 *    the case where losing it would be invisible.
 * 4. **The entries table claims only what the data supports.** The column is `Period approved`, not
 *    "approved by the project lead" — a period spans projects while `time:approve` is project
 *    scope, so the stronger claim is one this product declines to make (019 D-f).
 */

const ACME = aClientRef({ id: 'cl-acme', name: 'Acme Retail' })

const LINES = [
  anInvoiceLine({
    projectId: 'p-store',
    projectName: 'Acme Storefront',
    appliesTo: 'Mira Sund',
    scope: 'PERSON',
    hours: 18,
    rate: 110,
    amount: money({ amount: 1980 }),
  }),
  anInvoiceLine({
    projectId: 'p-loyalty',
    projectName: 'Acme Loyalty',
    appliesTo: 'Test Engineer',
    scope: 'ROLE',
    hours: 24,
    rate: 78,
    amount: money({ amount: 1872 }),
  }),
]

const ENTRIES = [
  anInvoiceEntry({
    timeEntryId: 'te-1',
    hours: 6,
    periodApprovedAt: '2026-09-03T08:00:00Z',
    periodApprovedBy: 'Rae Lindqvist',
  }),
]

const DRAFT: InvoiceDetail = anInvoiceDetail({
  invoice: anInvoice({ id: 'inv-1', client: ACME, total: money({ amount: 3852 }), lineCount: 2, approvedHours: 42 }),
  billTo: aClientContact({ name: 'Anna Krogh', email: 'invoices@acme.test' }),
  lines: LINES,
  entries: ENTRIES,
  excluded: [
    excludedHours({ reason: 'NOT_APPROVED', hours: 38, detail: 'Tomas Ek 24h · Jun Park 14h' }),
    // The bucket the mockup does not draw, because its example company has a card for every client.
    excludedHours({ reason: 'NO_RATE', hours: 12, detail: 'Dana Petrov 12h' }),
  ],
  budgetEffect: [
    aBudget({ projectId: 'p-store', projectName: 'Acme Storefront', amountHours: 600, approvedHours: 418, burnPercent: 70 }),
  ],
})

const ISSUED: InvoiceDetail = anInvoiceDetail({
  invoice: anInvoice({
    id: 'inv-1',
    number: 'INV-2026-041',
    client: ACME,
    status: 'ISSUED',
    total: money({ amount: 3852 }),
    lineCount: 2,
    approvedHours: 42,
    issuedAt: '2026-09-02T10:00:00Z',
    issuedBy: 'Rae Lindqvist',
  }),
  billTo: aClientContact({ name: 'Anna Krogh', email: 'invoices@acme.test' }),
  lines: LINES,
  entries: ENTRIES,
})

const ALL_KEYS: PermissionKey[] = ['billing:read', 'billing:invoice', 'billing:issue']

function mockReads(detail: InvoiceDetail, permissions: PermissionKey[] = ALL_KEYS) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    // Anchored: `/invoices` is a prefix of `/invoices/{id}` and answers a list where this answers a
    // document.
    if (/^\/invoices\/[^/]+$/.test(path)) return detail
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/billing/invoices/inv-1">
      <Routes>
        <Route path="/billing/invoices/:invoiceId" element={<InvoicePage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Invoice — a draft', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('says the number is assigned on issue, and offers both writes', async () => {
    mockReads(DRAFT)
    renderPage()

    expect(await screen.findByTestId('Invoice.Number')).toHaveTextContent('assigned on issue')
    expect(screen.getByTestId('Invoice.Issue')).toBeEnabled()
    expect(screen.getByTestId('Invoice.Discard')).toBeEnabled()
  })

  it('counts and names BOTH exclusion buckets (019 D-g)', async () => {
    mockReads(DRAFT)
    renderPage()

    const notApproved = within(await screen.findByTestId('Invoice.Excluded.NOT_APPROVED'))
    expect(notApproved.getByText(/38h are excluded because they are not approved/)).toBeInTheDocument()
    expect(notApproved.getByText(/Tomas Ek 24h · Jun Park 14h/)).toBeInTheDocument()
    expect(notApproved.getByRole('link', { name: 'Approve them' })).toHaveAttribute(
      'href',
      '/timesheets/approvals',
    )

    /*
      The second bucket, and the reason this test exists. From inside a join an unpriceable hour
      looks exactly like a priced one, so a build that lost it would render a complete-looking Lines
      table and twelve hours would be gone with nothing on screen to say so.
    */
    const noRate = within(screen.getByTestId('Invoice.Excluded.NO_RATE'))
    expect(noRate.getByText(/12h are approved but cannot be priced/)).toBeInTheDocument()
    expect(noRate.getByText(/Dana Petrov 12h/)).toBeInTheDocument()

    // Both are rows in the Lines table too, so the hours reconcile in one column — and neither row
    // carries an amount, because neither has one.
    const row = within(screen.getByTestId('Invoice.Lines.Table.Excluded.NO_RATE'))
    expect(row.getByText('12.0')).toBeInTheDocument()
  })

  it('heads the approval column `Period approved` rather than claiming project authority (019 D-f)', async () => {
    mockReads(DRAFT)
    renderPage()

    const header = await screen.findByRole('columnheader', { name: 'Period approved' })
    expect(header).toHaveAttribute('title', expect.stringContaining('spans projects'))
    // The cell states the decision on that person's period, and nothing anywhere reads "approved
    // by the project lead" — the claim the data does not support.
    expect(screen.getByTestId('Invoice.Entries.Table.Row.te-1')).toHaveTextContent('Rae Lindqvist')
    expect(screen.queryByText(/project lead/i)).toBeNull()
  })

  it('confirms a discard by naming the client, and says what is and is not lost', async () => {
    mockReads(DRAFT)
    renderPage()

    await userEvent.click(await screen.findByTestId('Invoice.Discard'))

    const confirm = within(screen.getByTestId('Invoice.ConfirmDiscard'))
    expect(confirm.getByText(/Discard this draft for Acme Retail\?/)).toBeInTheDocument()
    // A draft holds no lines of its own, so nothing anybody logged is lost — which is the half a
    // reader most needs and the half a generic "are you sure?" cannot say.
    expect(confirm.getByText(/a draft holds no lines of its own/i)).toBeInTheDocument()
    expect(
      confirm.getByRole('button', { name: 'Discard the draft for Acme Retail' }),
    ).toBeInTheDocument()
  })

  it('disables Issue for a caller holding billing:read but not billing:issue', async () => {
    // `billing:issue` is its own key, separate from `billing:invoice`. This caller can see the
    // draft and may not issue it, so the control is disabled with a reason rather than absent.
    mockReads(DRAFT, ['billing:read', 'billing:invoice'])
    renderPage()

    expect(await screen.findByTestId('Invoice.Issue')).toBeDisabled()
    expect(screen.getByTestId('Invoice.Discard')).toBeEnabled()
  })
})

describe('Invoice — issued', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders read-only: no Issue, no Discard, no edit affordance anywhere', async () => {
    mockReads(ISSUED)
    renderPage()

    expect(await screen.findByTestId('Invoice.Number')).toHaveTextContent('INV-2026-041')

    /*
      ABSENT, not disabled — and this is the assertion, not the number above it. §31's
      disable-don't-hide does not apply because these operations do not exist: no endpoint in this
      module can change an issued line (019 D-c). A greyed button would be inventing a state
      machine the server does not have.
    */
    expect(screen.queryByTestId('Invoice.Issue')).toBeNull()
    expect(screen.queryByTestId('Invoice.Discard')).toBeNull()
    expect(screen.queryByRole('button', { name: /issue|discard|edit/i })).toBeNull()

    // The lock, stated on the screen rather than only enforced.
    expect(screen.getByTestId('Invoice.Entries.Note')).toHaveTextContent(/These lines are locked/)
    expect(screen.getByTestId('Invoice.Issued')).toHaveTextContent('Rae Lindqvist')
  })

  it('draws no Preview PDF, in either state (019 D-q)', async () => {
    // The mockup draws the button. There is no endpoint behind it and none is planned, so it is
    // absent rather than drawn-and-inert — `ApprovalsPage` records the same for `Approve all`.
    mockReads(ISSUED)
    const issued = renderPage()
    expect(await screen.findByTestId('Invoice.Number')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /pdf|preview|download/i })).toBeNull()
    issued.unmount()

    mockReads(DRAFT)
    renderPage()
    expect(await screen.findByTestId('Invoice.Issue')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /pdf|preview|download/i })).toBeNull()
  })

  it('refuses a reader without billing:read, and fires no invoice read at all', async () => {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['client:read'], {}, null)
      throw new Error(`an invoice read fired for a caller without billing:read: ${path}`)
    }) as never)

    renderPage()

    expect(
      await screen.findByText('Invoices are visible to Finance and Company Admins.'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('Invoice.Region')).toBeNull()
  })
})
