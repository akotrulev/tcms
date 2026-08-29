import { Tooltip, TooltipContent, TooltipTrigger } from '../../components/ui';
import { Link as RouterLink } from 'react-router-dom';
import type { TestExecutionSummary } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { QuarantineChip } from '../../components/StatusChip';
import { plural } from '../../lib/format';
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

  return (
    <nav aria-label="Cases in this run" data-testid="Execution.Queue">
      <div className="px-4 py-3 border-b border-white/8">
        <span className="m-0 text-xs text-ink-2" data-testid="Execution.Queue.Position">
          {position >= 0
            ? `Case ${position + 1} of ${ordered.length}`
            : `${plural(ordered.length, 'case')} in this run`}
        </span>
      </div>

      <div className="flex flex-col list-none m-0 p-0">
        {ordered.map((execution) => {
          const current = execution.id === currentExecutionId;
          const kind = execution.status?.kind;
          return (
            <li key={execution.id}>
              <RouterLink to={`/projects/${projectId}/runs/${runId}/executions/${execution.id}${suffix}`} data-testid={RowTestId} aria-current={current ? 'page' : undefined}>
                {/*
                  The dot carries the result and the row carries no other status text — the rail
                  is 300px and a chip per row would leave no room for the title. Colour is not
                  alone: the tooltip names the status, and `Untested` is the readable default
                  rather than an absent mark, so "not run yet" is a state rather than a blank.
                */}
                <Tooltip>
<TooltipTrigger asChild>
                  <span data-testid={`${RowTestId}.Status`} aria-label={kind ? statusLabels[kind] : statusLabels.UNTESTED} />
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
                  </div>
                </div>
              </RouterLink>
            </li>
          );
        })}
      </div>
    </nav>
  );
}
