import type { InvoiceEntry } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Badge, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../components/kit';
import { formatDate, formatDateTime } from '../../lib/format';

/**
 * `invoice.html`'s *Time entries behind these lines* — the hours a line is made of, one row each.
 *
 * <h2>The approval column is headed `Period approved`, and the wording is the whole point</h2>
 *
 * A timesheet period is per person, per fortnight, and it **spans projects**, while `time:approve`
 * is a PROJECT-scope grant. A submitted period reaches an approver when at least one of its entries
 * names a project they may approve for, and approving it approves the **whole** period — including
 * entries against projects that approver cannot see (016 D-i, restated as 019 D-f).
 *
 * So *"these hours were approved"* is true and *"somebody with authority over this project approved
 * these hours"* is **not supported by the data**, and nothing in this table makes the second claim.
 * The header says `Period approved`, its `title` says why, and the cell reads *3 Sep · Rae* — which
 * is exactly what the row is: the decision on that person's period.
 *
 * The rejected alternative is recorded in the plan rather than only here: evaluating `time:approve`
 * for the deciding approver against each entry's project at invoice time computes a claim about the
 * past from the grants of the present, so a person who has since left the project would make last
 * month's approved hours retroactively suspect.
 *
 * <h2>Every row here is approved, which is why there is no state column</h2>
 *
 * An entry only reaches a line if its period was approved, so the badge carries the decision rather
 * than a status — the mockup's dimmed *awaiting* row is an unapproved hour, and unapproved hours
 * are in the `NOT_APPROVED` exclusion bucket on the Lines table above, counted and named, not here.
 */

export interface InvoiceEntriesProps {
  entries: readonly InvoiceEntry[];
  testId: string;
}

export function InvoiceEntries({ entries, testId }: InvoiceEntriesProps) {
  if (entries.length === 0) {
    return (
      <p className="m-0 text-[12.5px] text-ink-3" data-testid={`${testId}.None`}>
        No approved hours behind this invoice yet. Anything logged in the period that nobody has
        signed for is counted on the Lines table above, with the reason it is out.
      </p>
    );
  }

  return (
    <Table data-testid={testId}>
      <TableHead>
        <TableRow>
          <TableHeaderCell>Date</TableHeaderCell>
          <TableHeaderCell>Person</TableHeaderCell>
          <TableHeaderCell>Against</TableHeaderCell>
          <TableHeaderCell align="right">Hours</TableHeaderCell>
          {/*
            019 D-f. The heading names the decision the data actually holds, and the tooltip says
            why it cannot name a stronger one.
          */}
          <TableHeaderCell title="A timesheet period is per person and spans projects, so this is the decision on that person’s period — not an approval of this project’s hours (019 D-f).">
            Period approved
          </TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {entries.map((entry) => (
          <TableRow key={entry.timeEntryId} data-testid={`${testId}.Row.${entry.timeEntryId}`}>
            <TableCell>{formatDate(entry.spentOn)}</TableCell>
            <TableCell>{entry.personName}</TableCell>
            <TableCell>
              <Identifier variant="muted">{entry.subjectKey}</Identifier>
              <span className="ml-2 text-ink-2">{entry.subjectTitle}</span>
            </TableCell>
            <TableCell align="right" className="font-mono tabular-nums">
              {entry.hours.toFixed(1)}
            </TableCell>
            <TableCell>
              <Badge tone="passed">
                {`${formatDate(entry.periodApprovedAt)} · ${entry.periodApprovedBy}`}
              </Badge>
              <span className="sr-only">{` (decided ${formatDateTime(entry.periodApprovedAt)})`}</span>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
