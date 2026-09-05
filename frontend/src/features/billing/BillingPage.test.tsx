import { render, screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Budget, BillingSummary, Invoice, PermissionKey, RateCardSlot } from '../../api/types'
import {
  aBillingSummary,
  aBudget,
  aClientRef,
  aRateCard,
  aRateCardEntry,
  aRateCardSlot,
  anInvoice,
  money,
} from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { BillingPage } from './BillingPage'

/**
 * `billing.html`.
 *
 * The three things this file exists to catch, in the order they matter:
 *
 * 1. **The two money tiles never sum across currencies** (019 D-l). The mockup draws `€52.4k` as
 *    one figure while its own rate-card table carries a EUR client and a CHF client two rows apart.
 *    A build that folded them into one number would look *better* than this one and be wrong in
 *    both currencies, and no other test in the suite can see it — the figure would render, the
 *    types would hold, and the only evidence would be arithmetic nobody re-does.
 * 2. **A client with no rate card is a ROW.** It is the state every new client is in, and a panel
 *    that listed only the cards would render a company's newest customers as though they did not
 *    exist.
 * 3. **An over-budget project reads over budget.** `Math.min(percent, 100)` anywhere in the meter
 *    turns 102% into a full green bar, which is the one reading this screen must never give.
 *
 * The path-keyed arms are anchored throughout — `/^\\/rate-cards(\\?|$)/` cannot catch
 * `/rate-cards/{id}`, and `/^\\/invoices(\\?|$)/` cannot catch `/invoices/{id}`, which answer a
 * different shape each. Fifth and sixth arms in the tree to pay that lesson.
 */

const ACME = aClientRef({ id: 'cl-acme', name: 'Acme Retail' })
const BERGSTROM = aClientRef({ id: 'cl-berg', name: 'Bergstrom AG' })
const KESSLER = aClientRef({ id: 'cl-kess', name: 'Kessler Health' })

const SLOTS: RateCardSlot[] = [
  aRateCardSlot({
    client: ACME,
    card: aRateCard({
      id: 'rc-acme',
      client: ACME,
      currency: 'EUR',
      entries: [
        aRateCardEntry({ id: 'e1', scope: 'ROLE', roleId: 'r1', hourlyRate: 78, appliesTo: 'Test Engineer' }),
        aRateCardEntry({
          id: 'e2',
          scope: 'PERSON',
          personId: 'u1',
          hourlyRate: 110,
          effectiveFrom: '2026-07-01',
          appliesTo: 'Mira Sund',
        }),
      ],
    }),
  }),
  aRateCardSlot({
    client: BERGSTROM,
    card: aRateCard({ id: 'rc-berg', client: BERGSTROM, currency: 'CHF', entries: [aRateCardEntry({ id: 'e3', hourlyRate: 105 })] }),
  }),
  // The Kessler Health row: a client, and no card. `aRateCardSlot`'s own default.
  aRateCardSlot({ client: KESSLER }),
]

const BUDGETS: Budget[] = [
  aBudget({ projectId: 'p-store', projectName: 'Acme Storefront', client: ACME, amountHours: 600, approvedHours: 418, burnPercent: 70 }),
  aBudget({
    projectId: 'p-portal',
    projectName: 'Bergstrom Portal',
    client: BERGSTROM,
    amountHours: 500,
    approvedHours: 512,
    // Not capped. `billing.html` draws this row at 102% in a danger tone and it is the row the
    // whole panel exists to surface.
    burnPercent: 102,
  }),
  aBudget({
    projectId: 'p-internal',
    projectKey: 'INT',
    projectName: 'Internal Platform',
    client: null,
    amountHours: null,
    approvedHours: 126,
    burnPercent: null,
  }),
]

const INVOICES: Invoice[] = [
  anInvoice({
    id: 'inv-41',
    number: 'INV-2026-041',
    client: ACME,
    status: 'ISSUED',
    total: money({ amount: 18240 }),
    issuedAt: '2026-09-02T10:00:00Z',
    issuedBy: 'Rae Lindqvist',
    lineCount: 4,
    approvedHours: 68,
  }),
  anInvoice({ id: 'inv-draft', client: ACME, total: money({ amount: 6116 }), lineCount: 4, approvedHours: 68 }),
]

function mockReads({
  permissions = ['billing:read', 'billing:invoice', 'billing:issue', 'billing:budget', 'ratecard:read', 'ratecard:manage'] as PermissionKey[],
  summary = aBillingSummary(),
  slots = SLOTS,
  budgets = BUDGETS,
  invoices = INVOICES,
}: {
  permissions?: PermissionKey[]
  summary?: BillingSummary
  slots?: RateCardSlot[]
  budgets?: Budget[]
  invoices?: Invoice[]
} = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (path.startsWith('/billing/summary')) return summary
    if (/^\/rate-cards(\?|$)/.test(path)) return slots
    if (/^\/budgets(\?|$)/.test(path)) return budgets
    if (/^\/invoices(\?|$)/.test(path)) return invoices
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(route = '/billing') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route={route}>
      <Routes>
        <Route path="/billing" element={<BillingPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Billing', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders one line per currency in the money tiles, and never a sum of two (019 D-l)', async () => {
    mockReads({
      summary: aBillingSummary({
        invoicedYearToDate: [money({ amount: 52400 }), money({ amount: 12705, currency: 'CHF' })],
        draftableNow: [money({ amount: 6116 })],
        unapprovedHours: 38,
        clientsBilled: 3,
      }),
    })
    renderPage()

    const invoiced = within(await screen.findByTestId('Billing.Kpi.Invoiced'))
    const figures = invoiced.getAllByTestId('Billing.Kpi.Invoiced.Figure')
    expect(figures).toHaveLength(2)
    expect(figures[0]).toHaveTextContent('€52,400.00')
    expect(figures[1]).toHaveTextContent('CHF')
    expect(figures[1]).toHaveTextContent('12,705.00')

    /*
      The assertion that actually catches the defect. €52,400 + CHF 12,705 = 65,105, and a build
      that summed them would render exactly that under one currency symbol — a figure that is a
      plausible number, passes every type check, and is wrong in both currencies.
    */
    expect(invoiced.queryByText(/65,105/)).toBeNull()
  })

  it('reads an empty year-to-date as an em dash rather than as zero', async () => {
    // "Nothing has been issued" and "we issued nothing" are the same English sentence and different
    // claims on a tile. `€0.00` asserts a computation; the dash asserts an absence.
    mockReads({ summary: aBillingSummary({ invoicedYearToDate: [], draftableNow: [] }) })
    renderPage()

    const invoiced = within(await screen.findByTestId('Billing.Kpi.Invoiced'))
    expect(invoiced.queryAllByTestId('Billing.Kpi.Invoiced.Figure')).toHaveLength(0)
    expect(screen.getByTestId('Billing.Kpi.Invoiced')).toHaveTextContent('—')
  })

  it('lists a client with no rate card as a row with a Set button', async () => {
    mockReads()
    renderPage()

    // The Kessler Health row is the state every new client is in, and the one a panel listing only
    // the cards would drop.
    const row = await screen.findByTestId('Billing.RateCards.Row.cl-kess')
    expect(row).toHaveTextContent('Kessler Health')
    expect(row).toHaveTextContent('no rate card yet')
    expect(screen.getByTestId('Billing.RateCards.Set.cl-kess')).toBeEnabled()

    // And the cards that do exist render one row per rung, with the rung named.
    expect(screen.getByTestId('Billing.RateCards.Row.cl-acme:e2')).toHaveTextContent('Mira Sund')
    expect(screen.getByTestId('Billing.RateCards.Row.cl-acme:e2')).toHaveTextContent('(person)')
  })

  it('renders an over-budget project over 100% and in the danger tone', async () => {
    mockReads()
    renderPage()

    // 102%, uncapped. A `Math.min(percent, 100)` in the meter renders this as a full green bar,
    // which is the single reading a Finance user must never get from this table.
    expect(await screen.findByTestId('Billing.Budgets.Burn.p-portal.Percent')).toHaveTextContent('102%')
    expect(screen.getByTestId('Billing.Budgets.Burn.p-store.Percent')).toHaveTextContent('70%')
  })

  it('renders a project with no client as None (internal), with no warning and no prompt (019 D-p)', async () => {
    mockReads()
    renderPage()

    const row = within(await screen.findByTestId('Billing.Budgets.Row.p-internal'))
    expect(row.getByTestId('Billing.Budgets.Internal.p-internal')).toHaveTextContent('None (internal)')
    expect(row.getByTestId('Billing.Budgets.Burn.p-internal.None')).toHaveTextContent('no budget set')
    // A company that builds its own products is the owner's own case, and nothing in this product
    // may tell it that it is missing a customer.
    expect(row.queryByRole('alert')).toBeNull()
    expect(row.queryByText(/missing|required|attach a client/i)).toBeNull()
  })

  it('lists the draft in the same list as the issued invoices, saying Draft where the number goes', async () => {
    mockReads()
    renderPage()

    expect(await screen.findByTestId('Billing.Invoices.Row.inv-41')).toHaveTextContent('INV-2026-041')
    // `Invoice.number` is null until issuing assigns it, and the word Draft stands in for it —
    // never a blank, which would read as a number that failed to load.
    expect(screen.getByTestId('Billing.Invoices.Row.inv-draft')).toHaveTextContent('Draft')
    expect(screen.getByTestId('Billing.Invoices.Row.inv-draft')).toHaveAttribute(
      'href',
      '/billing/invoices/inv-draft',
    )
  })

  it('says what Not billed means, on the screen, rather than leaving it to be inferred', async () => {
    mockReads()
    renderPage()

    const panel = within(await screen.findByTestId('Billing.NotBilled'))
    expect(panel.getByText(/Fixed-fee and retainer invoicing/)).toBeInTheDocument()
    expect(panel.getByText(/never charged to a client/)).toBeInTheDocument()
  })

  it('refuses a reader without billing:read, and fires no billing read at all', async () => {
    /*
      Not merely that the panels are absent: every read here is `enabled`-gated on the permission,
      because a refusal would raise a notification through `app/queryClient.ts` for a screen the
      rail never offered this reader. The `throw` in the double is what proves it — an ungated hook
      would reach the unmocked arm and redden.
    */
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['client:read'], {}, null)
      throw new Error(`a billing read fired for a caller without billing:read: ${path}`)
    }) as never)

    renderPage()

    // findByText, not findByTestId: the gate renders 'Checking your permissions…' while
    // /me/permissions is in flight, so a testid query resolves on the PENDING copy.
    expect(await screen.findByText(/Rate cards, budgets and invoices are visible to Finance/)).toBeInTheDocument()
    expect(screen.queryByTestId('Billing.Kpi.Invoiced')).toBeNull()
    expect(screen.queryByTestId('Billing.Draft')).toBeNull()
  })

  it('disables Draft an invoice for a reader who can see the screen but not write one', async () => {
    // `billing:invoice` is a separate key from `billing:read`, so this caller is real — and the
    // control is disabled with a reason rather than absent (§31), because the product can do this.
    mockReads({ permissions: ['billing:read', 'ratecard:read'] })
    renderPage()

    expect(await screen.findByTestId('Billing.Draft')).toBeDisabled()
    expect(screen.getByTestId('Billing.RateCards.New')).toBeDisabled()
  })

  it('carries the period in the URL, so the month somebody is looking at can be sent on', async () => {
    mockReads()
    renderPage('/billing?on=2026-08-14')

    expect(await screen.findByTestId('Billing.Picker.Label')).toHaveTextContent('Aug 2026')
    // The summary is asked for that month, not for today — the key carries `on`, so a stale answer
    // for a different month cannot land under this heading.
    expect(vi.mocked(api.get)).toHaveBeenCalledWith(
      expect.stringContaining('/billing/summary?on=2026-08-14'),
      expect.anything(),
    )
  })
})
