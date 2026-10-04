import { Link as RouterLink } from 'react-router-dom';
import { useClientBilling } from '../../api/queries';
import type { ClientBillingSummary } from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Skeleton } from '../../components/ui';
import { formatDate, formatHours } from '../../lib/format';
import { invoiceStatusLabels, invoiceStatusTones } from '../../lib/labels';
import { formatMoney } from '../billing/money';

const TEST_ID = 'Client.Billing';

/**
 * `client.html`'s two KPI tiles and the *"Hours and invoices to date"* table under them.
 *
 * ~~The region M8 fills. It draws no data and reads no endpoint — what it is instead is the
 * **seam**: M8 lands its table here, and this component's existence is what let 018's close-out
 * hand that module a defined slot rather than an unresolved layout decision three modules
 * later.~~ → **superseded 2026-09-05 (019 T6): M8 landed and this is the table.** The seam held:
 * one file changed, `ClientPage` gained a prop, and nothing else on that screen moved.
 *
 * <h2>The whole table, and never three of its five columns</h2>
 *
 * 018 D-g's argument, now spent rather than restated: the columns are
 * `Period · Project · Approved hours · Invoice · Status`, and building the two this page could
 * already source would have cost more than building all five once. It reads
 * `GET /clients/{clientId}/billing` — **`client:read`, not a money key** — which is why an Account
 * Manager reads it at all.
 *
 * <h2>The value column is ABSENT for a caller without `billing:read`, not empty and not dashed</h2>
 *
 * 019 D-i. `client.html`'s own footer states it from the other side: *"Amounts are absent for an
 * Account Manager. Switch the top bar to **Finance** and this table gains a value column."*
 *
 * The flag comes from the server, evaluated once against `billing:read`, and is deliberately
 * **not** computed from the local permission cache: a client that decided this itself would be one
 * stale `/me/permissions` away from rendering a column of nulls and calling it money. It is a
 * field rather than an omission for the reason the contract gives — `amount: null` would otherwise
 * mean either *no invoice for this period* or *not your business*, which a renderer cannot tell
 * apart.
 *
 * <h2>The invoice is a link only for a reader who can open one</h2>
 *
 * The mockup links its Account Manager straight into `invoice.html`. That screen is gated on
 * `billing:read` and refuses in place with a sentence (019 D-o), so a link would take the one
 * reader this table was widened for to a page that tells them no. The number still renders — story
 * M2 requires that the hours say which invoice they are on — as an identifier rather than as a
 * promise. `amountsVisible` is exactly the gate on the invoice read, so the two cannot disagree.
 */
export function BillingSlot({ clientId }: { clientId: string }) {
  const billing = useClientBilling(clientId);

  return (
    <div className="flex flex-col gap-4">
      {/*
        Two regions over one read, and the duplication on failure is deliberate. They are one
        request, so a failure reaches both — and the alternative, tiles that simply disappear while
        the panel beneath them reports the outage, is the failed-read-renders-as-empty defect this
        app has a whole component to prevent. Two honest reports beat one silent absence.
      */}
      <DataRegion
        testId={`${TEST_ID}.Tiles`}
        state={{ ...billing, refetch: () => void billing.refetch() }}
        skeleton={
          <div className="grid gap-3 sm:grid-cols-2">
            {[0, 1].map((slot) => (
              <Skeleton key={slot} className="h-[92px] rounded-lg" />
            ))}
          </div>
        }
      >
        {(summary) => (
          <div className="grid gap-3 sm:grid-cols-2">
            <Tile
              testId={`${TEST_ID}.Kpi.Hours`}
              value={formatHours(summary.approvedHoursToDate)}
              label="Approved hours to date"
              detail={`${formatHours(summary.approvedHoursThisQuarter)} this quarter`}
            />
            <Tile
              testId={`${TEST_ID}.Kpi.Invoices`}
              value={String(summary.invoiceCount)}
              label="Invoices"
              /*
                `lastIssuedAt` is null until one is issued, and a client whose only invoice is a
                draft is the ordinary state a month into a contract. `formatDate`'s em dash would
                read as a failed lookup; the words say which of the two it is.
              */
              detail={
                summary.lastIssuedAt ?
                  `last issued ${formatDate(summary.lastIssuedAt)}`
                : 'none issued yet'
              }
            />
          </div>
        )}
      </DataRegion>

      <Panel title="Hours and invoices to date" testId={TEST_ID}>
        <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...billing, refetch: () => void billing.refetch() }}
          isEmpty={(summary) => summary.rows.length === 0}
          skeleton={<GridSkeleton rows={3} />}
          empty={{
            kind: 'no-records',
            title: 'No approved hours yet',
            body: 'A period appears here once somebody’s timesheet covering this client’s projects is approved. Hours are recorded from the day they are logged — approval is what makes them billable, and an invoice is drafted from them afterwards.',
          }}
        >
          {(summary) => (
            <Table data-testid={`${TEST_ID}.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Period</TableHeaderCell>
                  <TableHeaderCell>Project</TableHeaderCell>
                  <TableHeaderCell align="right">Approved hours</TableHeaderCell>
                  <TableHeaderCell>Invoice</TableHeaderCell>
                  <TableHeaderCell>Status</TableHeaderCell>
                  {/* 019 D-i — absent, not empty. See this file's header. */}
                  {summary.amountsVisible && <TableHeaderCell align="right">Value</TableHeaderCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {summary.rows.map((row) => (
                  <TableRow
                    key={`${row.periodStart}:${row.projectId}`}
                    data-testid={`${TEST_ID}.Row.${row.periodStart}.${row.projectId}`}
                  >
                    <TableCell>{row.periodLabel}</TableCell>
                    <TableCell>
                      <RouterLink
                        className="text-ink underline-offset-2 hover:underline"
                        to={`/projects/${row.projectId}`}
                      >
                        {row.projectName}
                      </RouterLink>
                    </TableCell>
                    <TableCell align="right" className="font-mono tabular-nums">
                      {formatHours(row.approvedHours)}
                    </TableCell>
                    <TableCell>
                      <InvoiceCell row={row} linked={summary.amountsVisible} />
                    </TableCell>
                    <TableCell>
                      {row.invoiceStatus ?
                        <Badge tone={invoiceStatusTones[row.invoiceStatus]}>
                          {invoiceStatusLabels[row.invoiceStatus]}
                        </Badge>
                      : <span className="text-[12px] text-ink-4">Not invoiced</span>}
                    </TableCell>
                    {summary.amountsVisible && (
                      <TableCell
                        align="right"
                        className="font-mono tabular-nums"
                        data-testid={`${TEST_ID}.Value.${row.periodStart}.${row.projectId}`}
                      >
                        {formatMoney(row.amount)}
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DataRegion>

        <p
          className="m-0 border-t border-line px-3.5 py-2.5 text-[11.5px] leading-[1.55] text-ink-4"
          data-testid={`${TEST_ID}.Note`}
        >
          {billing.data?.amountsVisible ?
            'A draft recalculates from the approved hours that exist now. Issuing one writes its lines and stops the figures moving.'
          : 'What those hours are worth is Finance’s. This table carries the client, the periods and the hours; never a rate.'}
        </p>
      </Panel>
    </div>
  );
}

/**
 * The invoice a period's hours are on — a link for Finance, an identifier for everybody else.
 *
 * A draft has no number, and the mockup renders the word *Draft* in this column rather than an em
 * dash: the hours are on something, and what they are on is a draft. A period on no invoice at all
 * is the em dash, and it is not a deficiency — it is every period before somebody drafts one.
 */
function InvoiceCell({
  row,
  linked,
}: {
  row: ClientBillingSummary['rows'][number];
  linked: boolean;
}) {
  if (row.invoiceId === null) return <span className="text-ink-4">—</span>;
  const label = row.invoiceNumber ?? invoiceStatusLabels.DRAFT;
  if (!linked) return <Identifier variant="muted">{label}</Identifier>;
  return (
    <RouterLink
      className="text-ink underline-offset-2 hover:underline"
      to={`/billing/invoices/${row.invoiceId}`}
      data-testid={`${TEST_ID}.Invoice.${row.invoiceId}`}
    >
      {label}
    </RouterLink>
  );
}

/**
 * Two of `client.html`'s three tiles.
 *
 * The third is *Projects*, and it stays in the projects panel's own title where 018 put it — a
 * count is a fact about the list under it, and moving it up here now would leave that panel's
 * heading saying one thing and a tile above it saying the same thing twice.
 *
 * `kit/Tile` is deliberately not used, for `BillingPage`'s recorded reason: it is a `<button>`
 * that navigates to the rows behind it, and neither of these has rows to navigate to.
 */
function Tile({
  value,
  label,
  detail,
  testId,
}: {
  value: string;
  label: string;
  detail: string;
  testId: string;
}) {
  return (
    <section
      data-testid={testId}
      className="rounded-lg border border-line bg-raised px-[15px] py-[13px] shadow-e1"
    >
      <span className="block font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
        {value}
      </span>
      <span className="mt-1.5 block text-[12.5px] text-ink-2">{label}</span>
      <span className="mt-[3px] block text-[11.5px] text-ink-4">{detail}</span>
    </section>
  );
}
