import { Link as RouterLink } from 'react-router-dom';
import { Receipt } from 'lucide-react';
import { useInvoices } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { Badge } from '../../components/kit';
import { Skeleton } from '../../components/ui';
import { formatDate, formatHours, joinMeta, plural } from '../../lib/format';
import { invoiceStatusLabels, invoiceStatusTones } from '../../lib/labels';
import { formatMoney } from './money';
import { formatMonth } from './months';

/**
 * `billing.html`'s *Invoices* panel — drafts and issued invoices in one list.
 *
 * <h2>The draft is in the list, outlined rather than filtered away</h2>
 *
 * `GET /invoices` defaults to `ALL` and the mockup renders it that way: two issued invoices and one
 * draft, the draft carrying the accent border. A separate "drafts" section would be two lists of
 * one kind of thing, and the question a Finance user opens this panel with — *what is outstanding
 * for this client* — is answered by both rows together.
 *
 * <h2>A draft has no number, and the row says so rather than showing a blank</h2>
 *
 * `Invoice.number` is `null` until issuing assigns it (019 D-k), so the row reads the word
 * **Draft** where an issued row reads `INV-2026-041`. That is what the mockup draws, and it is the
 * one place on this screen where an absent value is a *state* rather than a gap.
 *
 * <h2>Its own `DataRegion`, and no cross-currency total in the foot</h2>
 *
 * Each row carries its own `Money`, and there is no footer summing them: this list can hold a EUR
 * invoice and a CHF one two rows apart, and one number over both would be wrong in both (019 D-l).
 * The per-currency totals live in the tiles above, one line each.
 */

const TEST_ID = 'Billing.Invoices';

export interface InvoiceListPanelProps {
  /** `billing:read`. Gated rather than allowed to 403. */
  canRead: boolean;
}

export function InvoiceListPanel({ canRead }: InvoiceListPanelProps) {
  const invoices = useInvoices({ enabled: canRead });

  return (
    <Panel title="Invoices" testId={TEST_ID}>
      <DataRegion
        testId={`${TEST_ID}.Region`}
        state={{ ...invoices, refetch: () => void invoices.refetch() }}
        isEmpty={(data) => data.length === 0}
        skeleton={
          <div className="flex flex-col gap-2">
            <Skeleton className="h-12 rounded-lg" />
            <Skeleton className="h-12 rounded-lg" />
          </div>
        }
        empty={{
          icon: Receipt,
          kind: 'no-records',
          title: 'Nothing invoiced yet',
          body: 'Draft one from the button above. A draft is a live report over this period’s approved hours — it has no lines of its own until you issue it, so nothing here can be out of date.',
        }}
      >
        {(rows) => (
          <ul className="m-0 flex list-none flex-col gap-2 p-0" data-testid={`${TEST_ID}.List`}>
            {rows.map((invoice) => (
              <li key={invoice.id}>
                <RouterLink
                  to={`/billing/invoices/${invoice.id}`}
                  data-testid={`${TEST_ID}.Row.${invoice.id}`}
                  className={
                    'flex items-center gap-2.5 rounded-lg border bg-paper px-2.5 py-2 no-underline ' +
                    (invoice.status === 'DRAFT' ?
                      'border-steel-line hover:border-steel'
                    : 'border-line hover:border-line-strong')
                  }
                >
                  <Receipt aria-hidden className="size-4 shrink-0 text-ink-4" />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="truncate text-[12.5px] text-ink">
                        {invoice.number ?? invoiceStatusLabels.DRAFT}
                      </span>
                      <span className="flex-1" />
                      <span className="shrink-0 font-mono text-[12px] tabular-nums text-ink-2">
                        {formatMoney(invoice.total)}
                      </span>
                    </span>
                    <span className="mt-0.5 flex items-center gap-2 text-[11px] text-ink-4">
                      <span className="truncate">
                        {joinMeta(
                          invoice.client.name,
                          formatMonth(invoice.periodStart, 'short'),
                          invoice.issuedAt ?
                            `issued ${formatDate(invoice.issuedAt)}`
                          : `${plural(invoice.lineCount, 'line')} · ${formatHours(invoice.approvedHours)}`,
                        )}
                      </span>
                      <span className="flex-1" />
                      {/*
                        The badge is on both rows rather than only on the draft. The word `Draft`
                        stands in for the number above, so without a badge an issued row would be
                        the only one carrying no statement of its state at all — and *what is a
                        draft and what is final* is the single question this panel answers.
                      */}
                      <Badge tone={invoiceStatusTones[invoice.status]}>
                        {invoiceStatusLabels[invoice.status]}
                      </Badge>
                    </span>
                  </span>
                </RouterLink>
              </li>
            ))}
          </ul>
        )}
      </DataRegion>
    </Panel>
  );
}
