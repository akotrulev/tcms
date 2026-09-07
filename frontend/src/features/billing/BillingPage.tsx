import { useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Ban } from 'lucide-react';
import { useBillingSummary } from '../../api/queries';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Button, Skeleton } from '../../components/ui';
import { formatHours } from '../../lib/format';
import { todayIso } from '../time/periods';
import { BudgetPanel } from './BudgetPanel';
import { InvoiceListPanel } from './InvoiceListPanel';
import { DraftInvoiceDialog } from './DraftInvoiceDialog';
import { RateCardPanel } from './RateCardPanel';
import { formatMoneyList } from './money';
import { formatMonth, monthContaining, nextMonth, previousMonth } from './months';

/**
 * `billing.html` — what the company's approved hours are worth, and what it has already billed.
 *
 * <h2>This screen is not in the rail for most of the company, and that is the module's shape</h2>
 *
 * The Billing entry is **absent** — not disabled — for anybody without `billing:read` (019 D-o),
 * which is the fifth recorded exception to this rail's disable-don't-hide rule. `AppShell` carries
 * the argument. The route stays reachable for everybody, as every readable screen in this app is,
 * and refuses with a sentence rather than with a 403 from four panels at once.
 *
 * <h2>Four tiles, and each may be more than one line</h2>
 *
 * `billing.html` draws `€52.4k` as one figure while its own rate-card table carries a EUR client
 * and a CHF client two rows apart. **Money is `{amount, currency}` and a company total is an array
 * of them** (019 D-l), so the two money tiles render one line per currency where the mockup renders
 * one — recorded in the plan as a disagreement rather than resolved silently, per plan 009's
 * precedence rule: on what a screen shows the mockup wins, on what the system does the plan wins,
 * and *"what is this company's invoiced total"* is a question about the system. `money.ts` has no
 * `sum` for the same reason.
 *
 * The other two tiles are a plain number and a count, because hours and clients have no currency.
 *
 * <h2>Five regions, not one</h2>
 *
 * The tiles, the rate cards, the budgets and the invoices are four independent reads and each has
 * its own `DataRegion` (017 D-m): a summary outage must not empty the invoice list, and a
 * rate-card outage must not empty the budget table. The period picker sits **above** all of them,
 * so a failed read leaves the control the reader needs in order to retry on the screen.
 *
 * <h2>The period is in the URL, because filter state lives in the URL</h2>
 *
 * `?on=` is any day inside the month the tiles are computed over — which is what the endpoint
 * takes. The two arrows step it with `months.ts`' arithmetic, and nothing on this screen labels a
 * range the server has already bounded.
 *
 * <h2>Two panels are refusals rather than data, and neither is a placeholder</h2>
 *
 * *Not billed* is `billing.html`'s own panel, rendered verbatim because it is a product statement:
 * an invoice line is approved time at a rate-card rate and nothing else, so fixed-fee and retainer
 * invoicing are absent on purpose, and an AI assistant's hours are a cost that is never charged to
 * a client (019 D-q). It is not a feature flag and not a settings link. The other is the refusal
 * this page shows a caller without the key, which names who does hold it.
 */

const TEST_ID = 'Billing';

export function BillingPage() {
  const [params, setParams] = useSearchParams();
  const on = params.get('on') ?? todayIso();
  const [drafting, setDrafting] = useState(false);

  const gate = useCanInCompany();
  const allowed = gate.can('billing:read');
  const summary = useBillingSummary(on, allowed);

  const step = (to: string) =>
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.set('on', to);
      return next;
    });

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Billing"
        documentTitle="Billing"
        subtitle={
          allowed ?
            `Rate cards, budgets and invoices · ${formatMonth(on)}`
          : 'What this company’s work is worth.'
        }
        actions={
          allowed ?
            <>
              <MonthPicker on={on} onStep={step} testId={`${TEST_ID}.Picker`} />
              {/*
                Disabled with a why-tooltip rather than hidden: `billing:invoice` is a separate key
                from `billing:read`, so a reader who can see this screen and cannot draft on it is a
                real caller, and an absent button would tell them the product cannot do this.
              */}
              <span title={gate.can('billing:invoice') ? undefined : gate.reason('billing:invoice')}>
                <Button
                  size="sm"
                  variant="primary"
                  disabled={!gate.can('billing:invoice')}
                  onClick={() => setDrafting(true)}
                  data-testid={`${TEST_ID}.Draft`}
                >
                  Draft an invoice
                </Button>
              </span>
            </>
          : undefined
        }
      />

      {!allowed ?
        <Panel testId={`${TEST_ID}.Denied`}>
          <p
            className="m-0 max-w-[74ch] text-[13px] leading-[1.6] text-ink-3"
            data-testid={`${TEST_ID}.DeniedText`}
          >
            {gate.pending ?
              'Checking your permissions…'
            : 'What an hour costs is Finance’s. Rate cards, budgets and invoices are visible to Finance and Company Admins — ask one of them if a project you run needs a budget, or a client needs a rate.'
            }
          </p>
        </Panel>
      : <>
          <DataRegion
            testId={`${TEST_ID}.Summary`}
            state={{ ...summary, refetch: () => void summary.refetch() }}
            skeleton={
              <div className="mb-4 grid gap-3 md:grid-cols-4">
                {[0, 1, 2, 3].map((slot) => (
                  <Skeleton key={slot} className="h-[92px] rounded-[8px]" />
                ))}
              </div>
            }
          >
            {(loaded) => (
              <div className="mb-4 grid gap-3 md:grid-cols-4">
                <MoneyTile
                  testId={`${TEST_ID}.Kpi.Invoiced`}
                  label="Invoiced, year to date"
                  figures={formatMoneyList(loaded.invoicedYearToDate)}
                  detail="issued invoices in this calendar year"
                />
                <MoneyTile
                  testId={`${TEST_ID}.Kpi.Draftable`}
                  label="Draftable now"
                  figures={formatMoneyList(loaded.draftableNow)}
                  detail={`approved, priceable, unbilled hours in ${formatMonth(on, 'short')}`}
                />
                <Tile
                  testId={`${TEST_ID}.Kpi.Unapproved`}
                  label="Unapproved"
                  value={
                    <span style={loaded.unapprovedHours > 0 ? { color: 'var(--color-warning)' } : undefined}>
                      {formatHours(loaded.unapprovedHours)}
                    </span>
                  }
                  detail="excluded from every draft — counted, never dropped"
                />
                <Tile
                  testId={`${TEST_ID}.Kpi.Clients`}
                  label="Clients billed"
                  value={String(loaded.clientsBilled)}
                  detail="with at least one issued invoice this year"
                />
              </div>
            )}
          </DataRegion>

          <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
            <div className="flex flex-col gap-4">
              <RateCardPanel
                canRead={gate.can('ratecard:read')}
                canManage={gate.can('ratecard:manage')}
                reason={gate.reason('ratecard:manage')}
              />
              <BudgetPanel
                canRead={allowed}
                canSet={gate.can('billing:budget')}
                reason={gate.reason('billing:budget')}
              />
            </div>

            <div className="flex flex-col gap-4">
              <InvoiceListPanel canRead={allowed} />

              {/*
                `billing.html`'s "Not billed" panel, rendered verbatim because it is a product
                statement rather than a data region. Scope D24 admits one line shape — approved time
                at a rate-card rate — and this panel is where a Finance user reads that the two
                obvious extensions are absent on purpose rather than missing. Do not turn it into a
                feature flag or a settings link.
              */}
              <Panel title="Not billed" testId={`${TEST_ID}.NotBilled`}>
                <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
                  <Refusal>Fixed-fee and retainer invoicing</Refusal>
                  <Refusal>
                    AI-assistant usage — recorded as a cost, never charged to a client
                  </Refusal>
                </ul>
              </Panel>
            </div>
          </div>
        </>
      }

      <DraftInvoiceDialog open={drafting} on={on} onClose={() => setDrafting(false)} />
    </div>
  );
}

/** One line of the *Not billed* panel — a thing this module does not do, and will not. */
function Refusal({ children }: { children: ReactNode }) {
  return (
    <li className="flex items-start gap-2">
      <Ban aria-hidden className="mt-0.5 size-3.5 shrink-0 text-ink-4" />
      <span className="text-[12.5px] leading-[1.5] text-ink-2">{children}</span>
    </li>
  );
}

/**
 * A tile whose figure is money — **one line per currency, and an em dash for none**.
 *
 * The whole reason this is a separate component from {@link Tile}: a company with a EUR client and
 * a CHF client has two totals and no third, and a tile that took a single string would be the place
 * somebody joined them with a `+`. An empty array is a real answer (nothing issued yet) and renders
 * as the em dash rather than as `0`, which would claim a figure was computed and came to nothing.
 */
function MoneyTile({
  label,
  figures,
  detail,
  testId,
}: {
  label: string;
  figures: string[];
  detail: string;
  testId: string;
}) {
  return (
    <section data-testid={testId} className="rounded-[8px] border border-line bg-panel px-[15px] py-[13px]">
      {figures.length === 0 ?
        <span className="block font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
          —
        </span>
      : figures.map((figure) => (
          <span
            key={figure}
            className="block font-mono text-[22px] leading-[1.2] font-medium tracking-[-.02em] tabular-nums text-ink"
            data-testid={`${testId}.Figure`}
          >
            {figure}
          </span>
        ))
      }
      <span className="mt-1.5 block text-[12.5px] text-ink-2">{label}</span>
      <span className="mt-[3px] block text-[11.5px] text-ink-4">{detail}</span>
    </section>
  );
}

/**
 * A tile whose figure has no currency — hours, and a count of clients.
 *
 * `kit/Tile` is deliberately not used, for `TimesheetPage`'s recorded reason: it is a `<button>`
 * that navigates to the rows behind it, and none of these has rows to navigate to.
 */
function Tile({
  label,
  value,
  detail,
  testId,
}: {
  label: string;
  value: ReactNode;
  detail: string;
  testId: string;
}) {
  return (
    <section data-testid={testId} className="rounded-[8px] border border-line bg-panel px-[15px] py-[13px]">
      <span className="block font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
        {value}
      </span>
      <span className="mt-1.5 block text-[12.5px] text-ink-2">{label}</span>
      <span className="mt-[3px] block text-[11.5px] text-ink-4">{detail}</span>
    </section>
  );
}

/** Two arrows and the month, on the URL. `This month` only once the picker has left it. */
function MonthPicker({
  on,
  onStep,
  testId,
}: {
  on: string;
  onStep: (to: string) => void;
  testId: string;
}) {
  const today = todayIso();
  const away = monthContaining(on).startsOn !== monthContaining(today).startsOn;

  return (
    <span className="flex items-center gap-1" data-testid={testId}>
      <Button
        size="icon"
        variant="quiet"
        onClick={() => onStep(previousMonth(on))}
        aria-label="Previous month"
        data-testid={`${testId}.Previous`}
      >
        <ChevronLeft className="size-4" />
      </Button>
      <Identifier variant="muted" testId={`${testId}.Label`}>
        {formatMonth(on, 'short')}
      </Identifier>
      <Button
        size="icon"
        variant="quiet"
        onClick={() => onStep(nextMonth(on))}
        aria-label="Next month"
        data-testid={`${testId}.Next`}
      >
        <ChevronRight className="size-4" />
      </Button>
      {away && (
        <Button size="sm" onClick={() => onStep(today)} data-testid={`${testId}.Today`}>
          This month
        </Button>
      )}
    </span>
  );
}
