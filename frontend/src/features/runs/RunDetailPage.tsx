import {
  Box,
  Button,
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { RunStatus, TestRunDetail } from '../../api/types';
import { useProject, useRun, useSetRunStatus } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PassRateSummary, ResultBar } from '../../components/ResultBar';
import { PermissionGate } from '../../components/PermissionGate';
import { StatusChip } from '../../components/StatusChip';
import { describeError } from '../../lib/describeError';
import { compareCaseKeys, formatCount, formatDateTime, formatDuration } from '../../lib/format';
import { isRunClosed, runStatusColors, runStatusLabels, statusLabels } from '../../lib/labels';
import { ScrollX } from '../../components/ScrollX';
import { CaseKeyLink, ExecuteCaseLink } from './ExecutionRowLinks';

/** The row's static locator — never interpolated with an execution id (`test-ids` rule 6). */
const RowTestId = 'RunDetail.Executions.Row';

/**
 * A run's execution list — the tester's work queue.
 *
 * The default view puts unexecuted cases first, because the question someone opening a live
 * run is asking is "what's left for me to do", not "what happened". A filter row switches to
 * the retrospective reading.
 */
export function RunDetailPage() {
  const { projectId = '', runId = '' } = useParams();
  const project = useProject(projectId);
  const run = useRun(projectId, runId);

  return (
    <Box>
      <PageHeader
        testId="RunDetail.Header"
        title={run.data?.name ?? 'Test run'}
        documentTitle={run.data ? `${run.data.key} ${run.data.name}` : 'Test run'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test runs', to: `/projects/${projectId}/runs` },
          { label: run.data?.key ?? 'Run' },
        ]}
        actions={
          run.data && <RunLifecycleActions projectId={projectId} runId={runId} status={run.data.status} />
        }
        meta={
          run.data && (
            <>
              <Identifier variant="strong">{run.data.key}</Identifier>
              <Chip
                size="small"
                variant="outlined"
                data-testid="RunDetail.Status"
                label={runStatusLabels[run.data.status]}
                sx={{ color: runStatusColors[run.data.status] }}
              />
              {run.data.configuration && (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  {run.data.configuration.name}
                </Typography>
              )}
              {run.data.releaseVersion && (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Release <Identifier variant="muted">{run.data.releaseVersion}</Identifier>
                  {run.data.buildNumber && (
                    <>
                      {' '}
                      build <Identifier variant="muted">{run.data.buildNumber}</Identifier>
                    </>
                  )}
                </Typography>
              )}
              {run.data.planKey && (
                <RouterLink to={`/projects/${projectId}/plans/${run.data.planId}`} data-testid="RunDetail.PlanLink">
                  <Identifier variant="muted">{run.data.planKey}</Identifier>
                </RouterLink>
              )}
            </>
          )
        }
      />

      <DataRegion testId="RunDetail.Content" state={{ ...run, refetch: () => void run.refetch() }}>
        {(data) => <RunBody data={data} projectId={projectId} runId={runId} />}
      </DataRegion>
    </Box>
  );
}

/**
 * Complete, Abort, Reopen — the run's whole lifecycle, and the first affordance in the product for
 * a column that had no writer at all until §72.
 *
 * <h3>Three decisions worth reading</h3>
 *
 * **The controls swap rather than accumulate.** A closed run offers Reopen and nothing else,
 * because the server refuses completed → aborted outright: showing both endings on an ended run
 * would offer a move that cannot succeed, which is the thing §31 exists to stop.
 *
 * **Complete is disabled on a run nobody has recorded anything in**, with the reason on the
 * tooltip, rather than left enabled for the server to refuse. The rule is knowable from the status
 * alone, so §31's disable-don't-hide applies in full — and the sentence points at Abort, which is
 * the control beside it.
 *
 * **The refusal renders here, not as a toast.** `useSetRunStatus` marks its errors handled: "abort
 * it instead" and "reopen it first" are answers about the button that was just pressed, and a
 * corner notification detaches them from it.
 */
function RunLifecycleActions({
  projectId,
  runId,
  status,
}: {
  projectId: string;
  runId: string;
  status: RunStatus;
}) {
  const setStatus = useSetRunStatus(projectId, runId);
  const [error, setError] = useState<string | null>(null);
  const closed = isRunClosed(status);

  const move = (target: RunStatus) => {
    setError(null);
    setStatus.mutate(target, { onError: (failure) => setError(describeError(failure).body) });
  };

  return (
    <Stack sx={{ gap: 0.5, alignItems: 'flex-end' }}>
      <Stack direction="row" sx={{ gap: 1 }}>
        <PermissionGate projectId={projectId} permission="run:close">
          {({ allowed }) =>
            closed ? (
              <Button
                size="small"
                variant="outlined"
                data-testid="RunDetail.Reopen"
                disabled={!allowed || setStatus.isPending}
                // IN_PROGRESS, always — the server derives NOT_STARTED for a run that never began,
                // so the client holds no second copy of that rule and cannot get it wrong for a run
                // whose startedAt it is reading from a stale cache.
                onClick={() => move('IN_PROGRESS')}
              >
                Reopen run
              </Button>
            ) : (
              <>
                <Tooltip
                  title={
                    allowed && status === 'NOT_STARTED'
                      ? 'Nothing has been recorded in this run yet. Abort it instead.'
                      : ''
                  }
                >
                  {/* A disabled MUI Button fires no events, so the tooltip needs the wrapper. */}
                  <span>
                    <Button
                      size="small"
                      variant="outlined"
                      data-testid="RunDetail.Complete"
                      disabled={!allowed || status === 'NOT_STARTED' || setStatus.isPending}
                      onClick={() => move('COMPLETED')}
                    >
                      Complete run
                    </Button>
                  </span>
                </Tooltip>
                <Button
                  size="small"
                  variant="outlined"
                  color="error"
                  data-testid="RunDetail.Abort"
                  disabled={!allowed || setStatus.isPending}
                  onClick={() => move('ABORTED')}
                >
                  Abort run
                </Button>
              </>
            )
          }
        </PermissionGate>
      </Stack>
      {error && (
        <Typography variant="caption" sx={{ color: 'var(--color-failed)' }} data-testid="RunDetail.StatusError">
          {error}
        </Typography>
      )}
    </Stack>
  );
}

type Filter = 'ALL' | 'TODO' | 'FAILED';

function RunBody({ data, projectId, runId }: { data: TestRunDetail; projectId: string; runId: string }) {
  const [filter, setFilter] = useState<Filter>('ALL');
  const executions = data.executions ?? [];

  const filtered = executions.filter((execution) => {
    if (filter === 'TODO') return !execution.status;
    if (filter === 'FAILED') return execution.status?.kind === 'FAILED' || execution.status?.kind === 'BLOCKED';
    return true;
  });

  /**
   * Unexecuted first, then failures, then everything else — and always with the case key as
   * a unique tiebreaker so the order is stable across refetches. Without the tiebreaker a
   * tester's list reshuffles under them every time a result posts.
   *
   * The tiebreaker was a bare `caseKey.localeCompare` until §100, which sorts `TC-10` ahead of
   * `TC-9` — the bug `compareReleaseVersions` had already named as "the same class as ordering by
   * case key" without anyone checking whether the case existed. It does not show on a fixture set
   * whose keys never cross a power of ten, and on a real run it reads as the list being corrupt.
   *
   * This screen keeps its needs-doing rank; Split mode's rail deliberately does not (see
   * `queueOrder.ts` for why a triage view and a position view want different orders).
   */
  const sorted = [...filtered].sort((a, b) => {
    const rank = (kind: string | undefined) => (kind === undefined ? 0 : kind === 'FAILED' || kind === 'BLOCKED' ? 1 : 2);
    return rank(a.status?.kind) - rank(b.status?.kind) || compareCaseKeys(a.caseKey, b.caseKey);
  });

  return (
    <Stack sx={{ gap: 3 }}>
      <Panel testId="RunDetail.Summary">
        <Stack sx={{ gap: 2 }}>
          <PassRateSummary counts={data.counts} testId="RunDetail.PassRate" />
          <ResultBar counts={data.counts} height={14} showLegend testId="RunDetail.ResultBar" />
          <Stack direction="row" sx={{ gap: 3, flexWrap: 'wrap' }}>
            {/*
              Three states, not two. Until runs could be created, every fixture was IN_PROGRESS
              with a `startedAt`, so the un-started case had never been rendered — and the first
              live run created through the new endpoint read "Started — · Still running", which
              is two false claims about a run nobody has opened yet.

              A run starts when somebody records the first result, not when it is named (see
              `TestRun.create`), so a null `startedAt` is an ordinary and common state rather
              than missing data.
            */}
            {data.startedAt ? (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Started {formatDateTime(data.startedAt)}
              </Typography>
            ) : (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Not started yet
              </Typography>
            )}
            {data.completedAt ? (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Finished {formatDateTime(data.completedAt)}
              </Typography>
            ) : (
              data.startedAt && (
                <Typography variant="caption" sx={{ color: 'var(--color-info)' }}>
                  Still running
                </Typography>
              )
            )}
          </Stack>
        </Stack>
      </Panel>

      <Panel
        title="Executions"
        testId="RunDetail.Executions"
        dense
        actions={
          <Stack direction="row" sx={{ gap: 0.5 }}>
            {(
              [
                { value: 'ALL' as const, label: `All ${formatCount(executions.length)}` },
                { value: 'TODO' as const, label: `Not run ${formatCount(data.counts.notRun)}` },
                {
                  value: 'FAILED' as const,
                  label: `Needs attention ${formatCount(data.counts.failed + data.counts.blocked)}`,
                },
              ]
            ).map((option) => (
              <Chip
                key={option.value}
                size="small"
                label={option.label}
                onClick={() => setFilter(option.value)}
                variant={filter === option.value ? 'filled' : 'outlined'}
                color={filter === option.value ? 'primary' : 'default'}
                aria-pressed={filter === option.value}
                data-testid={`RunDetail.Filter.${option.value}`}
                sx={{ cursor: 'pointer' }}
              />
            ))}
          </Stack>
        }
      >
        {sorted.length === 0 ? (
          <Box sx={{ p: 3 }}>
            <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="RunDetail.Executions.Empty">
              {filter === 'TODO'
                ? 'Everything in this run has been executed.'
                : filter === 'FAILED'
                  ? 'Nothing failed or was blocked in this run.'
                  : 'This run has no cases in scope.'}
            </Typography>
          </Box>
        ) : (
          <ScrollX>
            <Table size="small" data-testid="RunDetail.Executions.Grid">
              <TableHead>
                <TableRow>
                  {/*
                    There was an "Execution" column here showing TE_0001. Executions no longer
                    carry a display key (plan §6t) — they are reached through their run or case —
                    so the column had no data to show. The row→detail affordance moved onto the
                    case title below, which is what a tester actually recognises.
                  */}
                  <TableCell sx={{ color: 'text.secondary' }}>Case</TableCell>
                  <TableCell sx={{ color: 'text.secondary', width: 116 }}>Result</TableCell>
                  <TableCell sx={{ color: 'text.secondary', width: 130 }}>Defects</TableCell>
                  <TableCell sx={{ color: 'text.secondary', width: 90 }} align="right">
                    Duration
                  </TableCell>
                  <TableCell sx={{ color: 'text.secondary', width: 150 }}>Executed by</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {sorted.map((execution) => (
                  <TableRow key={execution.id} data-testid={RowTestId} hover>
                    <TableCell>
                      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}>
                        {/*
                          Both links come from `ExecutionRowLinks`, shared with Grid mode on the
                          execution screen — the key opens the case, the title opens this
                          execution. This screen had that pair right and Grid mode had it
                          backwards; one module now decides it for both.
                        */}
                        <CaseKeyLink
                          execution={execution}
                          projectId={projectId}
                          testId={RowTestId}
                        />
                        <ExecuteCaseLink
                          execution={execution}
                          projectId={projectId}
                          runId={runId}
                          testId={RowTestId}
                        />
                        {/*
                          The pinned case version. This is what makes past results honest: the
                          case may have been edited five times since, and this row still says
                          which text was executed.
                        */}
                        <Tooltip title="The case version this execution ran against">
                          <Box sx={{ flexShrink: 0 }}>
                            <Identifier variant="muted">v{execution.caseVersionNo}</Identifier>
                          </Box>
                        </Tooltip>
                      </Stack>
                    </TableCell>
                    <TableCell>
                      {execution.status?.kind ? (
                        <StatusChip kind={execution.status.kind} testId={`${RowTestId}.Status`} />
                      ) : (
                        <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid={`${RowTestId}.Status`}>
                          {statusLabels.UNTESTED}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      {execution.defects.length === 0 ? (
                        <Box sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>—</Box>
                      ) : (
                        <Stack sx={{ gap: 0.25 }}>
                          {execution.defects.map((defect) => (
                            <Box
                              key={defect.id}
                              component="a"
                              href={defect.url}
                              target="_blank"
                              rel="noopener"
                              sx={{ fontSize: '0.75rem' }}
                              data-testid={`${RowTestId}.DefectLink`}
                            >
                              <Identifier>{defect.externalKey}</Identifier>
                              <Box component="span" aria-hidden="true" sx={{ ml: 0.5, color: 'text.secondary' }}>
                                ↗
                              </Box>
                              <Box component="span" className="sr-only">
                                (opens in a new tab)
                              </Box>
                            </Box>
                          ))}
                        </Stack>
                      )}
                    </TableCell>
                    <TableCell align="right" sx={{ fontSize: '0.75rem' }}>
                      <Identifier variant="muted">{formatDuration(execution.durationMs)}</Identifier>
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                      {execution.executedAt ? (
                        <Tooltip title={formatDateTime(execution.executedAt)}>
                          <Box component="span">{execution.executedBy?.displayName ?? 'CI'}</Box>
                        </Tooltip>
                      ) : (
                        <Box component="span">
                          {execution.assignee ? `assigned to ${execution.assignee.displayName}` : 'unassigned'}
                        </Box>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </ScrollX>
        )}
      </Panel>
    </Stack>
  );
}
