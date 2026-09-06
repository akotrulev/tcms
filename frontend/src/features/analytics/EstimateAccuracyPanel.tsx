import { useQueries } from '@tanstack/react-query';
import { Link } from 'react-router-dom';

import { TIMEOUTS, api } from '../../api/client';
import { useIssues } from '../../api/queries';
import { keys } from '../../api/queries/keys';
import type { EpicTimeRollup } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { FigurePanel } from '../../components/kit/FigurePanel';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit/Table';

/**
 * Estimate against approved hours, per epic — `reports.html`'s fourth panel.
 *
 * <h2>Composed here, because both halves already belong to somebody</h2>
 *
 * `reporting` computes nothing for this (020 D-i). The epics are the tracker's own list, and the
 * estimate and the approved hours both come from `time`'s `GET /time/rollup/epics/{key}`, which
 * carries them already. An analytics endpoint would have meant `reporting` reading
 * `issue_time_rollup` — which 016 D-c placed off-limits to every module but `time` — or re-deriving
 * what *approved* means, which is a second definition of the word in a second table.
 *
 * <h2>Approved hours only, and the panel says so</h2>
 *
 * `approvedHours` and `pendingHours` are separate on the wire and nothing in this product sums them
 * (016 D-d). An unapproved timesheet is not evidence about what a piece of work cost, so this table
 * counts approved hours and prints that where the reader can see it.
 *
 * <h2>N+1, stated rather than discovered</h2>
 *
 * One request per epic. `reports.html` shows three rows, and the alternative is a bulk epic-grain
 * read in `time` that this module has no standing to add. A rollup that fails or is refused leaves
 * its row without figures rather than taking the table off the screen — the epic and its title are
 * the tracker's and are already on screen.
 */
export function EstimateAccuracyPanel({ projectId }: { projectId: string }) {
  const epics = useIssues(projectId, { level: 'EPIC' }, 'key');

  const rollups = useQueries({
    queries: (epics.data ?? []).map((epic) => ({
      queryKey: keys.time.epicRollup(epic.key),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api.get<EpicTimeRollup>(`/time/rollup/epics/${encodeURIComponent(epic.key)}`, {
          signal,
          timeoutMs: TIMEOUTS.interactive,
        }),
    })),
  });

  const rows = (epics.data ?? []).map((epic, index) => ({
    key: epic.key,
    title: epic.title,
    rollup: rollups[index]?.data ?? null,
  }));

  return (
    <FigurePanel title="Estimate accuracy" subtitle="by epic, against approved hours">
      {rows.length === 0 ? (
        <p className="py-3 text-[12.5px] text-ink-4">No epics in this project yet.</p>
      ) : (
        <Table data-testid="estimate-accuracy">
          <TableHead>
            <TableRow>
              <TableHeaderCell>Epic</TableHeaderCell>
              <TableHeaderCell align="right">Est</TableHeaderCell>
              <TableHeaderCell align="right">Logged</TableHeaderCell>
              <TableHeaderCell align="right">Drift</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.key} data-testid={`estimate-accuracy-${row.key}`}>
                <TableCell>
                  <Link
                    to={`/issues/${row.key}`}
                    className="text-steel no-underline hover:underline"
                  >
                    <Identifier>{row.key}</Identifier> {row.title}
                  </Link>
                </TableCell>
                <TableCell align="right">{hours(row.rollup?.estimateHours)}</TableCell>
                <TableCell align="right">{hours(row.rollup?.approvedHours)}</TableCell>
                <TableCell align="right">
                  <Drift rollup={row.rollup} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <p className="mt-2.5 border-t border-line pt-2 text-[11.5px] leading-[1.5] text-ink-4">
        Approved hours only — an unapproved timesheet is not evidence.
      </p>
    </FigurePanel>
  );
}

/** An hours figure, or an em dash. Never `0h` for a figure that has not arrived. */
function hours(value: number | null | undefined) {
  return value == null ? (
    <span className="text-ink-4">—</span>
  ) : (
    <span className="font-mono tabular-nums">{value}h</span>
  );
}

/**
 * `(approved − estimate) / estimate`, or nothing.
 *
 * **An epic with no estimate renders no drift**, and that is the decision worth stating: `0%` there
 * would claim the estimate was exactly right, where the truth is that nobody made one. A zero
 * estimate is the same case for the same reason, and would also divide by zero.
 *
 * The colour is the outcome tier and that is correct here — over budget is a bad outcome and under
 * is a good one, which is what those tokens mean. It is not a chart series.
 */
function Drift({ rollup }: { rollup: EpicTimeRollup | null }) {
  if (rollup == null || rollup.estimateHours === 0) {
    return <span className="text-ink-4">—</span>;
  }
  const drift = Math.round(
    ((rollup.approvedHours - rollup.estimateHours) / rollup.estimateHours) * 100,
  );
  return (
    <span
      className="font-mono tabular-nums"
      style={{ color: drift > 0 ? 'var(--color-failed)' : 'var(--color-passed)' }}
    >
      {drift > 0 ? '+' : drift < 0 ? '−' : ''}
      {Math.abs(drift)}%
    </span>
  );
}
