import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, RateCardSlot } from '../../api/types'
import { aClientRef, aRateCard, aRateCardEntry, aRateCardSlot, aRole, aUser } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { RateCardDialog } from './RateCardDialog'

/**
 * The rate-card form, and the property worth a test of its own: **the `PUT` is a whole
 * replacement, entries included** — the entries sent are the entries the card then has, and one
 * omitted is one removed.
 *
 * That is the shape a per-row editor would silently break. A dialog that sent only the rung
 * somebody touched would type-check, look identical on screen, and delete the other four rungs on
 * every save; nothing else in the suite is pointed at the payload. So both directions are asserted
 * against what actually crosses the wire: a save with no edits sends the ladder back whole, and a
 * save after removing a rung sends the ladder without it.
 *
 * The payload is also compared against `openapi.yaml` by `contractDoubles.ts` on the way through,
 * which is the half this file does not have to write assertions for.
 */

const ACME = aClientRef({ id: 'cl-acme', name: 'Acme Retail' })

const CARD_SLOT: RateCardSlot = aRateCardSlot({
  client: ACME,
  card: aRateCard({
    id: 'rc-acme',
    client: ACME,
    currency: 'EUR',
    entries: [
      aRateCardEntry({ id: 'e1', scope: 'ALL_ROLES', hourlyRate: 90, effectiveFrom: '2026-01-01', appliesTo: 'All roles' }),
      aRateCardEntry({
        id: 'e2',
        scope: 'PERSON',
        personId: 'u-mira',
        hourlyRate: 110,
        effectiveFrom: '2026-07-01',
        appliesTo: 'Mira Sund',
      }),
    ],
  }),
})

const EMPTY_SLOT: RateCardSlot = aRateCardSlot({ client: ACME })

function mockReads(permissions: PermissionKey[] = ['ratecard:read', 'ratecard:manage']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (path === '/users') return [aUser({ id: 'u-mira', displayName: 'Mira Sund' })]
    if (path === '/roles') return [aRole({ id: 'r-lead', name: 'Test Lead' })]
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderDialog(slot: RateCardSlot | null, unset = [ACME]) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/billing">
      <RateCardDialog open slot={slot} unset={unset} onClose={vi.fn()} />
    </TestProviders>,
  )
}

describe('RateCardDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('seeds the whole ladder and sends every rung back on save', async () => {
    mockReads()
    const put = vi.spyOn(api, 'put').mockResolvedValue(CARD_SLOT.card as never)
    renderDialog(CARD_SLOT)

    /*
      Both rungs are on the form, seeded from the card — including the person rung, whose id has to
      resolve against `/users`. The ladder is deliberately withheld until that list lands: rendered
      earlier, the select would fall back to its placeholder and show *Choose a person…* over a rung
      that is in fact set. `findBy` is therefore load-bearing rather than habit.
    */
    expect(await screen.findByTestId('Billing.RateCard.Dialog.Entry.0.Rate')).toHaveValue(90)
    expect(screen.getByTestId('Billing.RateCard.Dialog.Entry.1.Person')).toHaveValue('u-mira')

    await userEvent.click(screen.getByTestId('Billing.RateCard.Dialog.Submit'))

    expect(put).toHaveBeenCalledWith(
      '/rate-cards/rc-acme',
      expect.objectContaining({
        currency: 'EUR',
        entries: [
          { scope: 'ALL_ROLES', personId: null, roleId: null, hourlyRate: 90, effectiveFrom: '2026-01-01' },
          { scope: 'PERSON', personId: 'u-mira', roleId: null, hourlyRate: 110, effectiveFrom: '2026-07-01' },
        ],
      }),
      expect.anything(),
    )
  })

  it('sends the ladder without a rung that was removed — one omitted is one removed', async () => {
    mockReads()
    const put = vi.spyOn(api, 'put').mockResolvedValue(CARD_SLOT.card as never)
    renderDialog(CARD_SLOT)

    await userEvent.click(await screen.findByRole('button', { name: 'Remove rate 1' }))
    await userEvent.click(screen.getByTestId('Billing.RateCard.Dialog.Submit'))

    // The person rung alone. A payload carrying both would mean the removal did nothing on the
    // server whatever the form showed.
    expect(put).toHaveBeenCalledWith(
      '/rate-cards/rc-acme',
      expect.objectContaining({
        entries: [
          { scope: 'PERSON', personId: 'u-mira', roleId: null, hourlyRate: 110, effectiveFrom: '2026-07-01' },
        ],
      }),
      expect.anything(),
    )
  })

  it('refuses to save a card with no rates, and says what an empty one would do', async () => {
    mockReads()
    const put = vi.spyOn(api, 'put').mockResolvedValue(CARD_SLOT.card as never)
    renderDialog(CARD_SLOT)

    await userEvent.click(await screen.findByRole('button', { name: 'Remove rate 1' }))
    await userEvent.click(screen.getByRole('button', { name: 'Remove rate 1' }))
    await userEvent.click(screen.getByTestId('Billing.RateCard.Dialog.Submit'))

    // The refusal names the consequence rather than saying "required": a card with no rungs is
    // saveable-looking and would put every approved hour into the NO_RATE bucket.
    expect(screen.getByTestId('Billing.RateCard.Dialog.Error')).toHaveTextContent(/no rate.*bucket/i)
    expect(put).not.toHaveBeenCalled()
  })

  it('confirms a removal by naming the client and both of its consequences', async () => {
    mockReads()
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderDialog(CARD_SLOT)

    await userEvent.click(await screen.findByTestId('Billing.RateCard.Dialog.Remove'))

    const confirm = within(screen.getByTestId('Billing.RateCard.Dialog.ConfirmRemove'))
    expect(confirm.getByText(/Remove the rate card for Acme Retail\?/)).toBeInTheDocument()
    // The consequence a reader expects...
    expect(confirm.getByText(/Invoices already issued keep their figures/)).toBeInTheDocument()
    // ...and the one they do not.
    expect(confirm.getByText(/no rate/)).toBeInTheDocument()
    // Nothing has happened yet — the first press opens the confirmation and does not delete.
    expect(del).not.toHaveBeenCalled()

    await userEvent.click(confirm.getByRole('button', { name: 'Remove the card for Acme Retail' }))
    expect(del).toHaveBeenCalledWith('/rate-cards/rc-acme', expect.anything())
  })

  it('asks which client a NEW card is for, and does not ask on a row that already named one', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue(CARD_SLOT.card as never)
    renderDialog(null)

    // The picker exists only when the reader pressed *New rate card*; a row's pencil put the client
    // in the title, and a disabled select repeating it would look like a field somebody could unlock.
    const picker = await screen.findByTestId('Billing.RateCard.Dialog.Client')
    await userEvent.selectOptions(picker, 'cl-acme')

    await userEvent.click(await screen.findByTestId('Billing.RateCard.Dialog.AddEntry'))
    await userEvent.type(screen.getByTestId('Billing.RateCard.Dialog.Entry.0.Rate'), '95')
    await userEvent.type(screen.getByTestId('Billing.RateCard.Dialog.Entry.0.From'), '2026-01-01')
    await userEvent.click(screen.getByTestId('Billing.RateCard.Dialog.Submit'))

    expect(post).toHaveBeenCalledWith(
      '/rate-cards',
      expect.objectContaining({ clientId: 'cl-acme', currency: 'EUR' }),
      expect.anything(),
    )
  })

  it('puts no client picker on a row that already names one', async () => {
    mockReads()
    renderDialog(EMPTY_SLOT)

    expect(await screen.findByTestId('Billing.RateCard.Dialog.Entries')).toBeInTheDocument()
    expect(screen.queryByTestId('Billing.RateCard.Dialog.Client')).toBeNull()
    // The client is in the title instead, and the title says this client has no card yet.
    expect(screen.getByRole('heading', { name: 'New rate card — Acme Retail' })).toBeInTheDocument()
  })
})
