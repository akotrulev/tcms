/**
 * Billing (019 M8) — rate cards, budgets, invoices, and the one read that has no money on it.
 *
 * <h2>Seven money keys, and one endpoint that deliberately costs none of them</h2>
 *
 * Everything here except {@link useProjectBudgetBurn} costs one of `ratecard:read` /
 * `ratecard:manage` / `billing:read` / `billing:budget` / `billing:invoice` / `billing:issue`,
 * which Instance Admin, Company Admin and Finance hold and nobody else does. The exception is the
 * project-scope burn: it is gated on `project:read`, and it is safe **because there is nothing on
 * it to withhold** — its schema has no `amount` and no `currency` member at all (019 D-h). Gating
 * a project's own ceiling behind a money key would hide it from the people delivering against it.
 *
 * {@link useClientBilling} is the second endpoint here whose gate is not a money key: it is
 * `client:read`, so an Account Manager reads it in full, and the **server** decides whether the
 * answer carries amounts and says so in `amountsVisible` (019 D-i). Nothing on this surface
 * computes that from the local permission cache — a client that did would be one stale
 * `/me/permissions` away from rendering a column of nulls and calling it money.
 *
 * <h2>Every read is `enabled`-gated on the permission rather than allowed to 403</h2>
 *
 * `clients.ts` established the reason one module over and it is sharper here: the rail entry into
 * `/billing` is **absent** for a caller without `billing:read` (019 D-o), so a screen that fired
 * these anyway would raise a refusal notification through `app/queryClient.ts` for a control the
 * reader was never meant to see — a failure report about the product rather than about the request.
 *
 * <h2>A draft is a query, so every write sweeps the root</h2>
 *
 * A `DRAFT` invoice stores no lines; they are recomputed on every read from the approved entries
 * that exist *now* (019 D-c). So approving a timesheet moves a draft, issuing one moves the four
 * summary tiles and the budget table's priced figures, and discarding one gives its hours back to
 * the next draft. None of that can be invalidated key by key with any confidence, which is why
 * {@link keys.billing}'s comment puts the sweep on `billingRoot` and why the two invoice writes
 * sweep `clientsRoot` as well — the client page's own rollup is under that root, not this one.
 *
 * <h2>There are no expense hooks, and that is 019 D-j rather than an omission</h2>
 *
 * `POST` / `GET` / `DELETE /expenses` exist and are reachable from the CLI and the MCP server. An
 * expense is a recorded cost and **never an invoice line**, no mockup draws an expenses panel, and
 * plan 009's rule is that no module ports a screen it has no endpoint for — this is that rule in
 * the other direction, which is allowed. A hook with no caller would be the only thing here
 * claiming a screen is coming. `keys.billing.expenses` exists for the day one is.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type {
  Budget,
  BudgetBurn,
  BudgetInput,
  BillingSummary,
  ClientBillingSummary,
  Invoice,
  InvoiceDetail,
  InvoiceDraftInput,
  InvoiceStatus,
  RateCard,
  RateCardInput,
  RateCardSlot,
} from '../types';
import { keys } from './keys';

/** Which invoices the list asks for. `ALL` is what `billing.html`'s panel renders. */
export type InvoiceFilter = InvoiceStatus | 'ALL';

/**
 * Every billing list a rate-card or budget write must refresh.
 *
 * One root, because a rate card is the input to every priced figure in the module: change a rate
 * and the summary's *Draftable now*, every `MONEY`-basis budget's burn, and every open draft's
 * lines all move together. There is no narrower key that is honestly enough.
 */
function invalidateBilling(client: QueryClient) {
  void client.invalidateQueries({ queryKey: keys.billingRoot });
}

/**
 * What an invoice write refreshes — **two roots, and neither is a prefix of the other**.
 *
 * `billingRoot` covers this module's own four lists. `clientsRoot` covers `client.html`'s hours-
 * and-invoices table, which is keyed under the client for the reason `keys.clientsRoot` gives, so
 * issuing an invoice on the billing screen must reach a table on a screen the writer is not
 * looking at. Stated in one function rather than at three call sites, which is how one of them
 * ends up updated and the other two do not.
 */
function invalidateInvoices(client: QueryClient) {
  invalidateBilling(client);
  void client.invalidateQueries({ queryKey: keys.clientsRoot });
}

/**
 * `billing.html`'s four tiles — `billing:read`.
 *
 * `on` is any day inside the period *Draftable now* is computed over, and the year-to-date figure
 * is always the calendar year containing it. It is in the key because it is in the request: a
 * summary for September and one for August are two different answers, and a key without it would
 * serve the first under the second's heading the moment the picker moved.
 */
export function useBillingSummary(on: string, enabled = true): UseQueryResult<BillingSummary> {
  return useQuery({
    queryKey: keys.billing.summary(on),
    queryFn: ({ signal }) =>
      api.get<BillingSummary>(`/billing/summary${qs({ on })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && on !== '',
  });
}

/**
 * Every active client and the card it has, **or has not** — `ratecard:read`.
 *
 * The answer is a `RateCardSlot[]`, so a client with no card is a row rather than a missing one:
 * `billing.html`'s *Kessler Health · no rate card yet · Set*. That is the state every new client
 * is in, and a list that only carried the cards would render a company's newest customers as
 * though they did not exist.
 */
export function useRateCards(enabled = true): UseQueryResult<RateCardSlot[]> {
  return useQuery({
    queryKey: keys.billing.rateCards,
    queryFn: ({ signal }) =>
      api.get<RateCardSlot[]>('/rate-cards', { signal, timeoutMs: TIMEOUTS.interactive }),
    enabled,
  });
}

export function useCreateRateCard() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: RateCardInput) =>
      api.post<RateCard>('/rate-cards', input, { timeoutMs: TIMEOUTS.interactive }),
    /*
      The 409 names a client that already has a card — `uq_rate_card_client` is one per client
      (019 D-d) — and the dialog renders that sentence beside the picker, where the reader's next
      action is. `useCreateClient` carries `meta.handled` for the same reason one module over.
    */
    meta: { handled: true },
    onSuccess: () => invalidateBilling(client),
  });
}

/**
 * Replace a card **entries included** — `ratecard:manage`.
 *
 * `PUT /rate-cards/{id}` is a full replacement: the entries sent are the entries the card then
 * has, and one omitted is one removed. The dialog therefore always submits the whole ladder, and
 * `clientId` is not sent as a change — a card does not move between clients, and moving one would
 * silently reprice every open draft on both sides.
 */
export function useUpdateRateCard(rateCardId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: RateCardInput) =>
      api.put<RateCard>(`/rate-cards/${rateCardId}`, input, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateBilling(client),
  });
}

export function useDeleteRateCard() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (rateCardId: string) =>
      api.del(`/rate-cards/${rateCardId}`, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateBilling(client),
  });
}

/**
 * `billing.html`'s *Project budgets* table — the **money** view, `billing:read`.
 *
 * It lists every project, not only the ones with a budget (019 D-t): the mockup draws
 * *Internal Platform · None (internal) · budget — · 126h approved · no budget set* in the same
 * table as three billed projects, with no warning tone and no call to action. A project with no
 * client cannot be invoiced and nothing here marks that as a deficiency (019 D-p).
 */
export function useBudgets(
  options: { clientId?: string; enabled?: boolean } = {},
): UseQueryResult<Budget[]> {
  const { clientId = '', enabled = true } = options;
  return useQuery({
    queryKey: keys.billing.budgets(clientId),
    queryFn: ({ signal }) =>
      api.get<Budget[]>(`/budgets${qs({ clientId })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled,
  });
}

/**
 * Set or replace a project's budget — `billing:budget`, at **company** scope.
 *
 * Not a project grant: a budget is a money decision and the seven money keys are company-wide, so
 * a Project Manager sets no budget and reads the burn. The body is the target state rather than a
 * verb, so sending one for a project that already has a budget replaces it.
 *
 * Sweeps the project's own subtree as well as this module's, because the burn lives there — see
 * {@link keys.budgetBurn}.
 */
export function useSetProjectBudget(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: BudgetInput) =>
      api.put<Budget>(`/projects/${projectId}/budget`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => {
      invalidateBilling(client);
      void client.invalidateQueries({ queryKey: keys.project(projectId) });
    },
  });
}

/**
 * Remove a project's budget — `billing:budget`.
 *
 * A project with no budget is not a project in a deficient state: `settings-general.html`'s Budget
 * row reads *no budget set* and the overview draws no panel at all.
 */
export function useClearProjectBudget() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (projectId: string) =>
      api.del(`/projects/${projectId}/budget`, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: (_data, projectId) => {
      invalidateBilling(client);
      void client.invalidateQueries({ queryKey: keys.project(projectId) });
    },
  });
}

/**
 * The project-scope burn — **`project:read`, and there is no money on it** (019 D-h).
 *
 * `BudgetBurn` carries `basis`, `approvedHours`, `budgetHours` and `burnPercent`, and has no
 * `amount` and no `currency` member at all. A `MONEY`-basis budget therefore reaches this endpoint
 * as a percentage alone, and where the project's client has no rate card that percentage is
 * `null` — *unknown*, never zero, because a zero would read as *nothing spent* on a project that
 * has spent a quarter.
 *
 * Its consumer is the project overview's Budget burn panel, which is T6's.
 */
export function useProjectBudgetBurn(
  projectId: string | undefined,
  enabled = true,
): UseQueryResult<BudgetBurn> {
  return useQuery({
    queryKey: keys.budgetBurn(projectId ?? ''),
    queryFn: ({ signal }) =>
      api.get<BudgetBurn>(`/projects/${projectId}/budget-burn`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && projectId !== undefined && projectId !== '',
  });
}

/**
 * The company's invoices, drafts included — `billing:read`.
 *
 * `ALL` is the default and is what the mockup's panel renders: two issued invoices and one draft
 * in one list, the draft outlined rather than filtered away. Both parameters are in the key
 * because both are in the request.
 */
export function useInvoices(
  options: { clientId?: string; status?: InvoiceFilter; enabled?: boolean } = {},
): UseQueryResult<Invoice[]> {
  const { clientId = '', status = 'ALL', enabled = true } = options;
  return useQuery({
    queryKey: keys.billing.invoices(clientId, status),
    queryFn: ({ signal }) =>
      api.get<Invoice[]>(`/invoices${qs({ clientId, status })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled,
  });
}

/**
 * One invoice in full — header, lines, the entries behind them, what was excluded, and the budgets
 * it moves. Nothing on it is paged; the screen renders all of it together.
 *
 * **For a draft this is a computation, not a record.** Every read recalculates the lines from the
 * approved entries that exist now, which is what makes `invoice.html`'s central promise —
 * *"Approve them and this draft recalculates"* — true without a recalculate endpoint, a staleness
 * flag, or a window in which the draft disagrees with the timesheets behind it.
 */
export function useInvoice(invoiceId: string | undefined): UseQueryResult<InvoiceDetail> {
  return useQuery({
    queryKey: keys.billing.invoice(invoiceId ?? ''),
    queryFn: ({ signal }) =>
      api.get<InvoiceDetail>(`/invoices/${invoiceId}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: invoiceId !== undefined && invoiceId !== '',
  });
}

/**
 * Draft an invoice for a client and a period — `billing:invoice`.
 *
 * A client and a period and nothing else: the lines are the approved hours that exist, and there
 * is no field on the input that could add one by hand. Billing is a report, not a spreadsheet.
 */
export function useDraftInvoice() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: InvoiceDraftInput) =>
      api.post<InvoiceDetail>('/invoices', input, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateInvoices(client),
  });
}

/**
 * Discard a draft — `billing:invoice`, and **`409` on an issued invoice**.
 *
 * That refusal is one of 019's two negatives and it is not a generic failure to report: an issued
 * invoice is a record, its lines are written rows, and there is no operation anywhere in this
 * module that can change one. The caller renders the refusal in place rather than as a toast that
 * scrolls away, which is why the notification handler stays out of it.
 */
export function useDiscardInvoice() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (invoiceId: string) =>
      api.del(`/invoices/${invoiceId}`, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateInvoices(client),
  });
}

/**
 * Issue a draft — `billing:issue`, **its own key**, separate from `billing:invoice`.
 *
 * This is the one transaction in the module that ever writes an `invoice_line`, and it assigns the
 * number. After it, the invoice renders read-only: no Issue, no Discard, no edit affordance, and
 * the lines carry the rate **as applied**, so a card edited next month does not reprice an invoice
 * sent last month.
 *
 * It sweeps both roots — see {@link invalidateInvoices}. The draft it replaces was a live query
 * over the company's approved hours, so issuing changes the summary tiles, the budget table's
 * priced figures, the invoice list **and** the named client's own rollup at the same moment.
 */
export function useIssueInvoice() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (invoiceId: string) =>
      api.post<InvoiceDetail>(`/invoices/${invoiceId}/issue`, undefined, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateInvoices(client),
  });
}

/**
 * `client.html`'s two tiles and the hours-and-invoices table under them — **`client:read`**.
 *
 * Not a money key, which is why an Account Manager reads it at all. `amountsVisible` on the answer
 * is the server's verdict on `billing:read`, evaluated once, and it is a field rather than an
 * omission because a `null` amount would otherwise mean either *no invoice for this period* or
 * *not your business* — indistinguishable to a renderer (019 D-i).
 *
 * Its consumer is `clients/BillingSlot.tsx`, which is T6's.
 */
export function useClientBilling(
  clientId: string | undefined,
  enabled = true,
): UseQueryResult<ClientBillingSummary> {
  return useQuery({
    queryKey: keys.clientBilling(clientId ?? ''),
    queryFn: ({ signal }) =>
      api.get<ClientBillingSummary>(`/clients/${clientId}/billing`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && clientId !== undefined && clientId !== '',
  });
}
