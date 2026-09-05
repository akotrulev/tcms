import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { aClientRef, aRateCard, aRateCardSlot, anInvoiceDetail } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { DraftInvoiceDialog } from './DraftInvoiceDialog'

/**
 * *Draft an invoice* — a client, a period, and deliberately nothing else.
 *
 * The assertion that carries the module's whole argument is the negative one: **there is no field
 * on this form that could add a line.** The lines are the approved hours that exist, computed on
 * every read (019 D-c), so a build that grew an amount box here would have turned a report into a
 * spreadsheet — and no other test would notice, because the extra field would submit happily and
 * the server would ignore it.
 *
 * The second is the warning: a client with no rate card can still be drafted for, every hour lands
 * in the `NO_RATE` bucket, and the total is zero. That is a legible answer and the option stays —
 * but the consequence is stated *before* the press, because a zero total afterwards is
 * indistinguishable from a client nobody worked for.
 */

const ACME = aClientRef({ id: 'cl-acme', name: 'Acme Retail' })
const KESSLER = aClientRef({ id: 'cl-kess', name: 'Kessler Health' })

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['billing:read', 'billing:invoice'], {}, null)
    }
    if (/^\/rate-cards(\?|$)/.test(path)) {
      return [
        aRateCardSlot({ client: ACME, card: aRateCard({ client: ACME }) }),
        // The client with no card — a row rather than an omission, here as in the panel.
        aRateCardSlot({ client: KESSLER }),
      ]
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderDialog(on = '2026-09-17') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/billing">
      <DraftInvoiceDialog open on={on} onClose={vi.fn()} />
    </TestProviders>,
  )
}

describe('DraftInvoiceDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('seeds the period from the month the screen is on, and sends both ends explicitly', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue(anInvoiceDetail() as never)
    renderDialog('2026-09-17')

    // The server takes a range rather than a month, so both ends travel — and `months.ts` bounds
    // them so February needs no case of its own.
    expect(await screen.findByTestId('Billing.Draft.Dialog.From')).toHaveValue('2026-09-01')
    expect(screen.getByTestId('Billing.Draft.Dialog.To')).toHaveValue('2026-09-30')

    // The options arrive with `/rate-cards`, which lands after the two date fields above — so the
    // wait is on the option rather than on the select, which renders empty and disabled first.
    await screen.findByRole('option', { name: 'Acme Retail' })
    await userEvent.selectOptions(screen.getByTestId('Billing.Draft.Dialog.Client'), 'cl-acme')
    await userEvent.click(screen.getByTestId('Billing.Draft.Dialog.Submit'))

    expect(post).toHaveBeenCalledWith(
      '/invoices',
      { clientId: 'cl-acme', periodStart: '2026-09-01', periodEnd: '2026-09-30', termsDays: 30 },
      expect.anything(),
    )
  })

  it('offers no way to add a line, at all', async () => {
    /*
      The negative that carries 019 D-c. `InvoiceDraftInput` is a client, two dates and the terms;
      the lines are what the approved hours already are. A field here would be the one change that
      turns this module from a report into a spreadsheet, and it would submit happily.
    */
    mockReads()
    renderDialog()

    await screen.findByRole('option', { name: 'Acme Retail' })
    expect(screen.queryByRole('button', { name: /add (a )?line/i })).toBeNull()
    expect(screen.queryByLabelText(/amount|rate|hours|description/i)).toBeNull()
    // Four inputs and no fifth: client, from, to, terms.
    expect(screen.getByTestId('Billing.Draft.Dialog.Note')).toHaveTextContent(
      /Its lines are this period’s approved hours/,
    )
  })

  it('says what a draft for a client with no rate card will do, before the press', async () => {
    mockReads()
    renderDialog()

    await screen.findByRole('option', { name: /Kessler Health/ })
    await userEvent.selectOptions(screen.getByTestId('Billing.Draft.Dialog.Client'), 'cl-kess')

    const warning = screen.getByTestId('Billing.Draft.Dialog.NoRateCard')
    expect(warning).toHaveTextContent(/nothing on the draft can be priced/)
    expect(warning).toHaveTextContent(/excluded bucket with its reason/)
    // The option is still selectable — filtering it out would be this dialog deciding a Finance
    // user may not look.
    expect(screen.getByTestId('Billing.Draft.Dialog.Client')).toHaveValue('cl-kess')

    // ...and it goes away again for a client that has one, so the warning is about the choice
    // rather than a banner the dialog always shows.
    await userEvent.selectOptions(screen.getByTestId('Billing.Draft.Dialog.Client'), 'cl-acme')
    expect(screen.queryByTestId('Billing.Draft.Dialog.NoRateCard')).toBeNull()
  })

  it('refuses to draft without a client', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue(anInvoiceDetail() as never)
    renderDialog()

    await screen.findByRole('option', { name: 'Acme Retail' })
    await userEvent.click(screen.getByTestId('Billing.Draft.Dialog.Submit'))

    expect(screen.getByText('Choose the client this invoice is for.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })
})
