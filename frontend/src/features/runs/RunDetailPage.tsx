import { Box, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { TestRunDetail } from '../../api/types';
import { useProject, useRun } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PassRateSummary, ResultBar } from '../../components/ResultBar';
import { StatusChip } from '../../components/StatusChip';
import { formatCount, formatDateTime, formatDuration } from '../../lib/format';
import { statusLabels } from '../../lib/labels';

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
        meta={
          run.data && (
            <>
              <Identifier variant="strong">{run.data.key}</Identifier>
              <Chip size="small" variant="outlined" label={run.data.status.replace(/_/g, ' ').toLowerCase()} />
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
   */
  const sorted = [...filtered].sort((a, b) => {
    const rank = (kind: string | undefined) => (kind === undefined ? 0 : kind === 'FAILED' || kind === 'BLOCKED' ? 1 : 2);
    return rank(a.status?.kind) - rank(b.status?.kind) || a.caseKey.localeCompare(b.caseKey);
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
                <TableRow key={execution.id} data-testid="RunDetail.Executions.Row" hover>
                  <TableCell>
                    <Stack direction="row" sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}>
                      <RouterLink
                        to={`/projects/${projectId}/cases/${execution.caseId}`}
                        data-testid="RunDetail.Executions.Row.CaseLink"
                      >
                        <Identifier>{execution.caseKey}</Identifier>
                      </RouterLink>
                      {/* The row→detail affordance: opens this execution, not the case. */}
                      <Tooltip title={execution.caseTitle}>
                        <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          <RouterLink
                            to={`/projects/${projectId}/runs/${runId}/executions/${execution.id}`}
                            data-testid="RunDetail.Executions.Row.Link"
                          >
                            {execution.caseTitle}
                          </RouterLink>
                        </Box>
                      </Tooltip>
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
                      <StatusChip kind={execution.status.kind} testId="RunDetail.Executions.Row.Status" />
                    ) : (
                      <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="RunDetail.Executions.Row.Status">
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
                            data-testid="RunDetail.Executions.Row.DefectLink"
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
        )}
      </Panel>
    </Stack>
  );
}
