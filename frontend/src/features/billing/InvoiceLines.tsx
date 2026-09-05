import type { ExcludedHours, InvoiceLine } from '../../api/types';
import { Table, TableBody, TableCell, TableFoot, TableHead, TableHeaderCell, TableRow } from '../../components/kit';
import { formatHours, plural } from '../../lib/format';
import { rateCardScopeLabels } from '../../lib/labels';
import { formatMoney, formatRate } from './money';

/**
 * `invoice.html`'s *Lines* table — one project, one rate, the hours at it.
 *
 * <h2>The excluded rows are in the table, not under it</h2>
 *
 * The mockup puts *Excluded — not approved · 38.0 · — · —* as a dimmed row inside the same tbody as
 * the priced lines, and that placement is the decision (019 D-g): an hour that is out is out **of
 * this table**, so the reader sees the whole population in one column of hours and never has to
 * reconcile a footer against a note. Both buckets get a row; neither carries an amount, because
 * neither has one.
 *
 * The second bucket — approved hours no rate card can price — is the one a naive implementation
 * loses, because from inside a join it looks exactly like a priced hour. It renders here whether or
 * not the mockup's example company happens to have it.
 *
 * <h2>`rate` is a bare number and the currency comes off the line's own `amount`</h2>
 *
 * That is the one place in this feature a figure travels without its currency attached, and it is
 * legitimate: a rate is an input to a product rather than a total. `money.ts`'s `formatRate` is
 * where the two are put back together, so no call site does it twice.
 *
 * <h2>The footer totals hours and one currency, because an invoice has exactly one</h2>
 *
 * A cross-client invoice is structurally impossible — the currency lives on the client's rate card
 * (019 D-d) — so this is the one total in the module that is a single figure rather than a list.
 */

export interface InvoiceLinesProps {
  lines: readonly InvoiceLine[];
  excluded: readonly ExcludedHours[];
  /** The invoice's own total, which is `Money` and never a sum this component computes. */
  total: { amount: number; currency: string };
  testId: string;
}

/** What each bucket is called, in the mockup's own words. */
const EXCLUSION_LABELS = {
  NOT_APPROVED: 'Excluded — not approved',
  NO_RATE: 'Excluded — no rate to price them at',
} as const satisfies Record<ExcludedHours['reason'], string>;

export function InvoiceLines({ lines, excluded, total, testId }: InvoiceLinesProps) {
  const pricedHours = lines.reduce((sum, line) => sum + line.hours, 0);

  return (
    <>
      <Table data-testid={testId}>
        <TableHead>
          <TableRow>
            <TableHeaderCell>Project</TableHeaderCell>
            <TableHeaderCell>Who / role</TableHeaderCell>
            <TableHeaderCell align="right">Approved h</TableHeaderCell>
            <TableHeaderCell align="right">Rate</TableHeaderCell>
            <TableHeaderCell align="right">Amount</TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {lines.map((line) => (
            <TableRow
              key={`${line.projectId}:${line.scope}:${line.appliesTo}:${line.rate}`}
              data-testid={`${testId}.Line.${line.projectId}`}
            >
              <TableCell>{line.projectName}</TableCell>
              <TableCell>
                {line.appliesTo}
                {line.scope !== 'ALL_ROLES' && (
                  <span className="ml-1.5 text-ink-4">{`(${rateCardScopeLabels[line.scope]} rate)`}</span>
                )}
              </TableCell>
              <TableCell align="right" className="font-mono tabular-nums">
                {line.hours.toFixed(1)}
              </TableCell>
              <TableCell align="right" className="font-mono tabular-nums">
                {formatRate(line.rate, line.amount.currency)}
              </TableCell>
              <TableCell align="right" className="font-mono tabular-nums">
                {formatMoney(line.amount)}
              </TableCell>
            </TableRow>
          ))}

          {excluded.map((bucket) => (
            <TableRow
              key={bucket.reason}
              className="opacity-60"
              data-testid={`${testId}.Excluded.${bucket.reason}`}
            >
              <TableCell colSpan={2}>
                <em>{EXCLUSION_LABELS[bucket.reason]}</em>
                {bucket.detail && <span className="ml-2 text-ink-4">{bucket.detail}</span>}
              </TableCell>
              <TableCell align="right" className="font-mono tabular-nums">
                {bucket.hours.toFixed(1)}
              </TableCell>
              {/*
                No amount, and the em dash rather than a zero: these hours have no price, which is
                not the same claim as their price being nothing.
              */}
              <TableCell align="right" className="text-ink-4">
                —
              </TableCell>
              <TableCell align="right" className="text-ink-4">
                —
              </TableCell>
            </TableRow>
          ))}

          {lines.length === 0 && excluded.length === 0 && (
            <TableRow>
              <TableCell colSpan={5}>
                <span className="text-[12.5px] text-ink-3" data-testid={`${testId}.Nothing`}>
                  Nothing in this period. No approved hours were logged against this client’s
                  projects between these two dates — approve some and this recalculates.
                </span>
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <TableFoot
        data-testid={`${testId}.Foot`}
        summary={
          <>
            {`${plural(lines.length, 'line')} · ${formatHours(pricedHours)} approved and priced · Total `}
            <strong className="font-mono text-[13px] font-medium text-ink-2">
              {formatMoney(total)}
            </strong>
          </>
        }
      />
    </>
  );
}
