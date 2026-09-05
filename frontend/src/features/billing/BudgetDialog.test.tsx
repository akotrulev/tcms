import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { aBudget, aClientRef, money } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { BudgetDialog } from './BudgetDialog'

/**
 * The budget form, and the rule it exists to render as a *shape* rather than as a message:
 * **a `MONEY` basis requires a currency and an `HOURS` basis forbids one.** `ck_budget_shape` holds
 * the same rule in the database, so a form that sent `currency: 'EUR'` beside an hours ceiling
 * would be refused by Postgres after passing every type check on the way down.
 *
 * The second thing here is the warning nobody asked for: a money ceiling on a project with no
 * client burns at *unknown* rather than at zero, because there is no rate card to price its hours
 * with. Said before the press, since the state afterwards is indistinguishable from a project
 * nobody has worked on.
 */

const HOURS = aBudget({ projectId: 'p-1', projectName: 'Acme Storefront', client: aClientRef(), amountHours: 600 })
const UNSET = aBudget({
  projectId: 'p-2',
  projectName: 'Internal Platform',
  client: null,
  amountHours: null,
  amountMoney: null,
  burnPercent: null,
})

function mockPermissions() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['billing:read', 'billing:budget'], {}, null)
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderDialog(budget: typeof HOURS | null) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/billing">
      <BudgetDialog budget={budget} onClose={vi.fn()} />
    </TestProviders>,
  )
}

describe('BudgetDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends no currency with an hours ceiling', async () => {
    mockPermissions()
    const put = vi.spyOn(api, 'put').mockResolvedValue(HOURS as never)
    renderDialog(HOURS)

    expect(await screen.findByTestId('Billing.Budget.Dialog.Amount')).toHaveValue(600)
    // Absent, not disabled: a greyed currency box beside an hours ceiling invites somebody to fill
    // it in and find out at the end that they could not.
    expect(screen.queryByTestId('Billing.Budget.Dialog.Currency')).toBeNull()

    await userEvent.click(screen.getByTestId('Billing.Budget.Dialog.Submit'))

    expect(put).toHaveBeenCalledWith(
      '/projects/p-1/budget',
      { basis: 'HOURS', amount: 600, currency: null },
      expect.anything(),
    )
  })

  it('asks for the currency the moment the basis becomes money, and sends it', async () => {
    mockPermissions()
    const put = vi.spyOn(api, 'put').mockResolvedValue(
      aBudget({ basis: 'MONEY', amountHours: null, amountMoney: money({ amount: 40000 }) }) as never,
    )
    renderDialog(HOURS)

    await userEvent.selectOptions(await screen.findByTestId('Billing.Budget.Dialog.Basis'), 'MONEY')
    expect(screen.getByTestId('Billing.Budget.Dialog.Currency')).toHaveValue('EUR')

    await userEvent.clear(screen.getByTestId('Billing.Budget.Dialog.Amount'))
    await userEvent.type(screen.getByTestId('Billing.Budget.Dialog.Amount'), '40000')
    await userEvent.click(screen.getByTestId('Billing.Budget.Dialog.Submit'))

    expect(put).toHaveBeenCalledWith(
      '/projects/p-1/budget',
      { basis: 'MONEY', amount: 40000, currency: 'EUR' },
      expect.anything(),
    )
  })

  it('warns before the press that a money ceiling on a client-less project burns at unknown', async () => {
    mockPermissions()
    renderDialog(UNSET)

    await userEvent.selectOptions(await screen.findByTestId('Billing.Budget.Dialog.Basis'), 'MONEY')

    // 019 D-p: a project with no client is not in a deficient state — so this is a warning about
    // what the reader is about to choose, not a complaint about the project.
    const warning = screen.getByTestId('Billing.Budget.Dialog.NoClient')
    expect(warning).toHaveTextContent(/burn at .?unknown/)
    expect(warning).toHaveTextContent(/An hours ceiling measures this project perfectly well/)
  })

  it('offers no Remove on a project that has no budget to remove', async () => {
    mockPermissions()
    renderDialog(UNSET)

    expect(await screen.findByTestId('Billing.Budget.Dialog.Amount')).toBeInTheDocument()
    // Nothing to confirm and nothing to undo — a Remove here would be a destructive control over
    // an absence.
    expect(screen.queryByTestId('Billing.Budget.Dialog.Clear')).toBeNull()
  })

  it('confirms a removal by naming the project, and says the hours survive it', async () => {
    mockPermissions()
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderDialog(HOURS)

    await userEvent.click(await screen.findByTestId('Billing.Budget.Dialog.Clear'))
    expect(del).not.toHaveBeenCalled()

    const confirm = screen.getByTestId('Billing.Budget.Dialog.ConfirmClear')
    expect(confirm).toHaveTextContent('Remove the budget from Acme Storefront?')
    expect(confirm).toHaveTextContent(/keeps every hour anybody logged/)

    await userEvent.click(screen.getByTestId('Billing.Budget.Dialog.ConfirmClear.Confirm'))
    expect(del).toHaveBeenCalledWith('/projects/p-1/budget', expect.anything())
  })
})
