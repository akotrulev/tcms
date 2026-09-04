import { Link as RouterLink } from 'react-router-dom';
import { Pencil, Trash2 } from 'lucide-react';
import type { TimeEntry } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { ScrollX } from '../../components/ScrollX';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { TypeIcon } from '../tracker/TypeIcon';
import { formatDate } from '../../lib/format';
import { timeSubjectLabels } from '../../lib/labels';

/**
 * The rows of one fortnight — `timesheets.html`'s Entries table, and the approvals queue's.
 *
 * <h2>`editable` is read off the row, never derived from the period's state</h2>
 *
 * The contract serves it on every entry precisely so that one rule has one home, and its own
 * description says so. A client that computed `state === 'OPEN' || state === 'REJECTED'` would be a
 * second implementation of the lock the whole feature turns on, and the two would disagree the day
 * a fifth state exists. So the edit and delete buttons are rendered per row on `entry.editable`,
 * which is what makes a SUBMITTED period render no controls without this component knowing why.
 *
 * <h2>The Against column is two different things and says which</h2>
 *
 * An ISSUE row is the issue — glyph, key, title — and links to it. Everything else is a **category
 * badge** and links nowhere, because a meeting and a day off have no page. `TimeEntry.subject` is
 * present only on the first, so the branch is on the payload rather than on the label.
 *
 * The project column is optional and off by default: the timesheet is one person's own fortnight
 * and repeating the project on every row is noise there, while the approvals queue is reading
 * across projects and it is the first thing an approver needs.
 */

export interface EntryTableProps {
  entries: readonly TimeEntry[];
  testId: string;
  /** The approver's view: a Project column first, no row controls whatever `editable` says. */
  withProject?: boolean;
  onEdit?: (entry: TimeEntry) => void;
  onDelete?: (entry: TimeEntry) => void;
  /** Disables both row controls while a write is in flight — the single-flight guard, per row set. */
  busy?: boolean;
}

export function EntryTable({
  entries,
  testId,
  withProject,
  onEdit,
  onDelete,
  busy,
}: EntryTableProps) {
  const controls = onEdit !== undefined || onDelete !== undefined;

  return (
    <ScrollX>
      <Table data-testid={testId}>
        <TableHead>
          <TableRow>
            <TableHeaderCell>Date</TableHeaderCell>
            {withProject && <TableHeaderCell>Project</TableHeaderCell>}
            <TableHeaderCell>Against</TableHeaderCell>
            <TableHeaderCell>Note</TableHeaderCell>
            <TableHeaderCell align="right">Hours</TableHeaderCell>
            {controls && (
              <TableHeaderCell align="right">
                <span className="sr-only">Row actions</span>
              </TableHeaderCell>
            )}
          </TableRow>
        </TableHead>
        <TableBody>
          {entries.map((entry) => (
            <TableRow key={entry.id} data-testid={`${testId}.Row`}>
              <TableCell>{formatDate(entry.spentOn)}</TableCell>
              {withProject && (
                <TableCell>
                  {entry.subject ? (
                    entry.subject.projectName
                  ) : (
                    // The contract is explicit that a project-less total omits its row rather than
                    // being called "—", and the same reasoning applies to a cell: a dash in a column
                    // of project names reads as a project called dash. Here it is the only thing
                    // available, so it is drawn in the ghost weight and given the sentence.
                    <span
                      className="text-ink-4"
                      title="Not against a project — a meeting, leave, training or internal work."
                    >
                      &mdash;
                    </span>
                  )}
                </TableCell>
              )}
              <TableCell>
                <Against entry={entry} testId={testId} />
              </TableCell>
              <TableCell>
                {entry.note ? (
                  <span className="text-ink-3">{entry.note}</span>
                ) : (
                  <span className="text-ink-4">&mdash;</span>
                )}
              </TableCell>
              <TableCell align="right">
                <Identifier variant="muted">{entry.hours.toFixed(1)}</Identifier>
              </TableCell>
              {controls && (
                <TableCell align="right">
                  {/*
                    Nothing at all on a locked row, rather than two disabled buttons. §31's
                    disable-don't-hide is about a *permission* the reader could be granted; this is a
                    state the reader has already been told about at the top of the screen, and a
                    greyed pencil on every one of twenty rows repeats it twenty times.
                  */}
                  {entry.editable && (
                    <span className="flex justify-end gap-1">
                      {onEdit && (
                        <Button
                          variant="quiet"
                          size="icon"
                          disabled={busy}
                          onClick={() => onEdit(entry)}
                          aria-label={`Edit ${entry.hours.toFixed(1)} hours on ${formatDate(entry.spentOn)}`}
                          data-testid={`${testId}.Row.Edit`}
                        >
                          <Pencil className="size-3.5" />
                        </Button>
                      )}
                      {onDelete && (
                        <Button
                          variant="quiet"
                          size="icon"
                          disabled={busy}
                          onClick={() => onDelete(entry)}
                          aria-label={`Remove ${entry.hours.toFixed(1)} hours on ${formatDate(entry.spentOn)}`}
                          data-testid={`${testId}.Row.Delete`}
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      )}
                    </span>
                  )}
                </TableCell>
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </ScrollX>
  );
}

/**
 * The issue, or the category — and the branch is on `subject`, not on `subjectType`.
 *
 * They agree today, and reading the payload is still the right test: `subject` is what the cell
 * needs, so a row whose type said ISSUE with no subject on it would render a broken link rather
 * than falling through to the badge.
 */
function Against({ entry, testId }: { entry: TimeEntry; testId: string }) {
  if (!entry.subject) {
    return (
      <Badge tone="plain" data-testid={`${testId}.Row.Category`}>
        {timeSubjectLabels[entry.subjectType]}
      </Badge>
    );
  }

  const { issueKey, title, typeLevel } = entry.subject;
  return (
    <span className="flex min-w-0 items-center gap-1.5">
      <TypeIcon type={{ level: typeLevel }} />
      <RouterLink
        to={`/issues/${encodeURIComponent(issueKey)}`}
        data-testid={`${testId}.Row.Issue`}
        className="font-mono text-inherit no-underline hover:text-steel hover:underline"
      >
        {issueKey}
      </RouterLink>
      <span className="truncate text-ink-2">{title}</span>
    </span>
  );
}
