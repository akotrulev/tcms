import type { DroppedField } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { formatCount } from '../../lib/format';

/**
 * The dropped-field list, on the preview step and on the report — **one component, because it is
 * one list** (D-f).
 *
 * The decision is usually read as being about the wire: `DroppedFields` on the server is a single
 * class holding a single map, the preview serves what it returns and the run persists it row by
 * row, and `ImportReportMatchesPreviewTest` asserts the two responses are equal element for
 * element. That is half of it. The other half is here — two table components that happen to render
 * the same four columns today are the drift the decision exists to prevent, and they would drift on
 * the first change that only one screen's author knew to make. The module's done-when is *"the
 * report matches the preview word for word"*, and a word is as easy to lose in a `<td>` as on the
 * wire.
 *
 * **Nothing here computes, re-orders or re-words a row.** `reason` is the sentence the server said
 * at the time and is stored per row precisely so that editing the catalogue later cannot rewrite a
 * report retroactively; `position` is the source's own field order and arrives already applied.
 * A client that sorted this table would break an equality it cannot see.
 */
export function DroppedFieldTable({
  fields,
  testId,
}: {
  fields: readonly DroppedField[];
  testId: string;
}) {
  return (
    <Table data-testid={`${testId}.Table`}>
      <TableHead>
        <TableRow>
          <TableHeaderCell>Source field</TableHeaderCell>
          <TableHeaderCell>Type</TableHeaderCell>
          <TableHeaderCell align="right">Values held</TableHeaderCell>
          <TableHeaderCell>Why</TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {fields.map((field) => (
          <TableRow key={field.name} data-testid={`${testId}.Row`}>
            <TableCell>{field.name}</TableCell>
            <TableCell>
              <span className="text-ink-4">{field.fieldType}</span>
            </TableCell>
            <TableCell align="right">
              <Identifier variant="muted">{formatCount(field.valueCount)}</Identifier>
            </TableCell>
            <TableCell>
              <span className="text-ink-4">{field.reason}</span>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
