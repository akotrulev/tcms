import { cn } from '../../lib/cn';
import { Tooltip, TooltipContent, TooltipTrigger } from '../../components/ui';
import { Link as RouterLink } from 'react-router-dom';
import type { StatusKind, TestExecutionSummary } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { QuarantineChip } from '../../components/StatusChip';
import { ResultBar } from '../../components/kit';
import { formatDuration, plural } from '../../lib/format';
import { statusVar } from '../../lib/statusColors';
import { statusLabels } from '../../lib/labels';
import { orderedQueue } from './queueOrder';

/**
 * The run's remaining work, beside the case being executed — Split mode's left rail.
 *
 * <h3>Rows are real links</h3>
 *
 * Each row is a `<RouterLink>` to that execution's own route, not an `onClick` that swaps a
 * component's state. The execution screen is already URL-addressed; keeping the queue on the same
 * mechanism means ⌘-click opens a case in a second tab (a genuinely useful thing mid-run — check
 * one case against another), the back button walks the tester back through what they marked, and
 * the address bar always names the case on screen. A click handler would have none of that and
 * would additionally need its own copy of the mode parameter to preserve it.
 *
 * <h3>No query of its own</h3>
 *
 * The rows come from `useRun`'s `executions`, which the page already holds — the execution screen
 * has read the run since §72 for the one bit of whether it is closed. So Split costs no request
 * that Focus does not already make, and the rail cannot show a queue that disagrees with the run
 * header above it.
 *
 * <h3>It says what has been recorded — fixed 2026-08-30 (renders audit 3.4)</h3>
 *
 * The render's left rail carries a result bar for the whole run and, per case, a status dot and a
 * duration (`● TC-188 Refund a settled… 3m 04s`). The audit read the build as listing "titles and
 * keys only", and the cause was smaller and worse than a missing feature: the status dot was
 * <em>already here</em> — a `<span>` with a `data-testid` and an `aria-label` and <strong>no size,
 * no colour and no class at all</strong>. It rendered nothing. A screen reader was told the status
 * of every row and a sighted reader was told none, which is the inverse of the usual failure and
 * invisible to every gate: the element existed, the test id resolved, the label was correct.
 *
 * So the dot has dimensions now, the run's own bar sits above the list, and each recorded row
 * carries its duration — which is the question the queue is actually asked: what is left.
 */

const RowTestId = 'Execution.Queue.Row';

export function ExecutionQueue({
  executions,
  currentExecutionId,
  projectId,
  runId,
  modeParam,
}: {
  executions: TestExecutionSummary[];
  currentExecutionId: string;
  projectId: string;
  runId: string;
  /** The `?mode=` to carry onto each row's link, or null when the mode is the default. */
  modeParam: string | null;
}) {
  const ordered = orderedQueue(executions);
  const position = ordered.findIndex((execution) => execution.id === currentExecutionId);
  const suffix = modeParam ? `?mode=${modeParam}` : '';

  /*
    The run's own totals, counted from the rows the queue already has rather than read from the
    run's `counts` — the two would be the same number twice, and the one that cannot disagree with
    the list underneath it is the one counted from the list.
  */
  const tally = (kind: StatusKind) =>
    ordered.filter((execution) => execution.status?.kind === kind).length;
  const untested = ordered.filter((execution) => !execution.status).length;

  return (
    <nav aria-label="Cases in this run" data-testid="Execution.Queue">
      <div className="border-b border-line px-4 py-3">
        <span className="m-0 text-xs text-ink-2" data-testid="Execution.Queue.Position">
          {position >= 0
            ? `Case ${position + 1} of ${ordered.length}`
            : `${plural(ordered.length, 'case')} in this run`}
        </span>
        {/* Wrapped rather than given a `data-testid` prop: `kit/ResultBar` deliberately takes
            counts and a className and nothing else, and widening a kit component so one call site
            can be located is the wrong direction. */}
        <span className="mt-2 block" data-testid="Execution.Queue.ResultBar">
          <ResultBar
            className="h-1.5"
            passed={tally('PASSED')}
            failed={tally('FAILED')}
            blocked={tally('BLOCKED')}
            skipped={tally('SKIPPED')}
            untested={untested}
          />
        </span>
      </div>

      <ul className="m-0 flex list-none flex-col p-0">
        {ordered.map((execution) => {
          const current = execution.id === currentExecutionId;
          const kind = execution.status?.kind;
          return (
            <li key={execution.id}>
              <RouterLink
                to={`/projects/${projectId}/runs/${runId}/executions/${execution.id}${suffix}`}
                data-testid={RowTestId}
                aria-current={current ? 'page' : undefined}
                className={cn(
                  'flex items-start gap-2 border-b border-line px-4 py-2 no-underline',
                  'hover:bg-wash-1',
                  current && 'bg-wash-1',
                )}
              >
                {/*
                  The dot carries the result and the row carries no other status text — the rail
                  is 300px and a chip per row would leave no room for the title. Colour is not
                  alone: the tooltip names the status, and `Untested` is the readable default
                  rather than an absent mark, so "not run yet" is a state rather than a blank.
                */}
                <Tooltip>
                  <TooltipTrigger asChild>
                    <span
                      data-testid={`${RowTestId}.Status`}
                      data-status={kind ?? 'UNTESTED'}
                      aria-label={kind ? statusLabels[kind] : statusLabels.UNTESTED}
                      className="mt-1.5 size-2 shrink-0 rounded-full"
                      style={{ backgroundColor: statusVar[kind ?? 'UNTESTED'] }}
                    />
                  </TooltipTrigger>
                  <TooltipContent>{kind ? statusLabels[kind] : statusLabels.UNTESTED}</TooltipContent>
                </Tooltip>
                <div className="min-w-0 flex-1">
                  <p
                    data-testid={`${RowTestId}.Title`}
                    className={cn(
                      'm-0 overflow-hidden text-sm text-ellipsis whitespace-nowrap',
                      current ? 'font-semibold text-ink' : 'font-normal text-ink-2',
                    )}
                  >
                    {execution.caseTitle}
                  </p>
                  <div className="flex items-center gap-1.5">
                    <Identifier variant="muted" className="text-[0.6875rem]">
                      {execution.caseKey}
                    </Identifier>
                    {/* The rail is 260px, so this is the one surface where the chip has to earn
                        its width. It does: the queue is where a tester CHOOSES the next case, and
                        a badge that waited for the detail screen would arrive after the choice. */}
                    {execution.quarantined && (
                      <QuarantineChip testId={`${RowTestId}.Quarantined`} />
                    )}
                    {/*
                      How long it took, on the rows that have been recorded. Right-aligned and in
                      the mono face because it is a number read down a column: the render draws
                      `● TC-188 Refund a settled… 3m 04s`, and the duration is what tells a tester
                      whether the case left is a two-minute one or a twenty-minute one.
                    */}
                    {execution.durationMs !== null && (
                      <span
                        className="ml-auto font-mono text-[0.6875rem] tabular-nums text-ink-4"
                        data-testid={`${RowTestId}.Duration`}
                      >
                        {formatDuration(execution.durationMs)}
                      </span>
                    )}
                  </div>
                </div>
              </RouterLink>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
