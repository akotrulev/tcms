import { Alert, Box, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import { Fragment, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { SuiteRunSummary, VersionMarker } from '../../api/types';
import { useProject, useSuiteHistory, useSuites } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { DenominatorToggle, ResultBar, ResultCountsCell } from '../../components/ResultBar';
import { executedCount, type Denominator } from '../../lib/results';
import { formatCount, formatDateTime } from '../../lib/format';

/**
 * Suite execution history (plan §6p).
 *
 * The trap this screen is built around: a suite's pass rate dropping between two runs looks
 * like a regression, but if four cases were *added* in between, the drop is new coverage
 * finding new problems — a different situation entirely, and arguably a good one.
 *
 * So suite version changes appear inline, and any comparison spanning a membership change is
 * called out explicitly rather than left for the reader to notice from the total column.
 */
export function SuiteHistoryPage() {
  const { projectId = '', suiteId = '' } = useParams();
  const project = useProject(projectId);
  const suites = useSuites(projectId);
  const history = useSuiteHistory(projectId, suiteId);

  const suite = suites.data?.find((s) => s.id === suiteId);

  return (
    <Box>
      <PageHeader
        testId="SuiteHistory.Header"
        title={`${suite?.name ?? 'Suite'} — execution history`}
        documentTitle={suite ? `History · ${suite.key}` : 'Suite history'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test cases', to: `/projects/${projectId}/cases` },
          ...(suite ? [{ label: suite.name, to: `/projects/${projectId}/cases?suiteId=${suite.id}` }] : []),
          { label: 'History' },
        ]}
        meta={
          suite && (
            <>
              <Identifier variant="strong">{suite.key}</Identifier>
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                <Identifier variant="muted">{formatCount(suite.caseCount)}</Identifier> cases now
              </Typography>
            </>
          )
        }
      />

      <DataRegion testId="SuiteHistory.Content" state={{ ...history, refetch: () => void history.refetch() }}>
        {(data) => <SuiteHistoryBody runs={data.runs} markers={data.versionMarkers} projectId={projectId} />}
      </DataRegion>
    </Box>
  );
}

function SuiteHistoryBody({
  runs,
  markers,
  projectId,
}: {
  runs: SuiteRunSummary[];
  markers: VersionMarker[];
  projectId: string;
}) {
  const [denominator, setDenominator] = useState<Denominator>('EXECUTED');

  // Newest first, with the run key as a unique tiebreaker.
  const sorted = [...runs].sort(
    (a, b) => b.executedAt.localeCompare(a.executedAt) || a.runKey.localeCompare(b.runKey),
  );

  const versions = new Set(sorted.map((run) => run.suiteVersionNo));
  const spansVersions = versions.size > 1;

  if (sorted.length === 0) {
    return (
      <Panel testId="SuiteHistory.Empty">
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          This suite has not been executed as part of any run yet.
        </Typography>
      </Panel>
    );
  }

  return (
    <Stack sx={{ gap: 3 }}>
      {/*
        The caveat that makes the table honest. It fires from the data rather than being
        written by hand, so it appears whenever it is true and never when it isn't.
      */}
      {spansVersions && (
        <Alert severity="info" data-testid="SuiteHistory.MembershipNotice">
          <Typography variant="body2">
            The suite's membership changed during this period — it spans{' '}
            {formatCount(versions.size)} versions. Compare rows within one version; across a
            version change, a movement in the pass rate can be new coverage rather than a
            regression. The totals column shows the case count each run actually covered.
          </Typography>
        </Alert>
      )}

      <Panel
        title="Runs"
        testId="SuiteHistory.Runs"
        dense
        actions={<DenominatorToggle value={denominator} onChange={setDenominator} testId="SuiteHistory.Denominator" />}
      >
        <Table size="small" data-testid="SuiteHistory.Grid">
          <TableHead>
            <TableRow>
              <TableCell sx={{ color: 'text.secondary' }}>Run</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 90 }}>Suite version</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 150 }}>Configuration</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 90 }}>Release</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 170 }}>Result</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 130 }} align="right">
                Pass rate
              </TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 90 }} align="right">
                Cases
              </TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 150 }}>Executed</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sorted.map((run, index) => {
              const previous = sorted[index - 1];
              // A version boundary gets its own row so the change is impossible to miss.
              const boundary =
                previous !== undefined && previous.suiteVersionNo !== run.suiteVersionNo
                  ? markers.find((m) => m.versionNo === previous.suiteVersionNo)
                  : undefined;

              return (
                <Fragment key={run.runId}>
                  {boundary && (
                    <TableRow key={`marker-${boundary.versionNo}`} data-testid="SuiteHistory.VersionMarker">
                      <TableCell colSpan={8} sx={{ bgcolor: 'action.hover', py: 1 }}>
                        <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                          <Chip
                            size="small"
                            label={`Suite changed to v${boundary.versionNo}`}
                            sx={{
                              bgcolor: 'color-mix(in srgb, var(--color-action-primary) 12%, transparent)',
                              color: 'var(--color-action-primary)',
                              border: '1px solid color-mix(in srgb, var(--color-action-primary) 40%, transparent)',
                              fontWeight: 600,
                            }}
                          />
                          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                            {/* Nullable for CaseHistoryPage's recorded reason. */}
                            {boundary.author?.displayName ?? 'Unknown author'} ·{' '}
                            {formatDateTime(boundary.occurredAt)}
                          </Typography>
                          {boundary.changeComment && (
                            <Typography variant="caption" sx={{ color: 'text.primary' }}>
                              {boundary.changeComment}
                            </Typography>
                          )}
                        </Stack>
                      </TableCell>
                    </TableRow>
                  )}

                  <TableRow data-testid="SuiteHistory.Row" hover>
                    <TableCell>
                      <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1, minWidth: 0 }}>
                        <RouterLink to={`/projects/${projectId}/runs/${run.runId}`} data-testid="SuiteHistory.Row.RunLink">
                          <Identifier>{run.runKey}</Identifier>
                        </RouterLink>
                        <Typography variant="body2" sx={{ color: 'text.secondary', minWidth: 0 }}>
                          {run.runName}
                        </Typography>
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Identifier variant="muted">v{run.suiteVersionNo}</Identifier>
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.75rem' }}>{run.configuration ?? '—'}</TableCell>
                    <TableCell>
                      {run.releaseVersion ? <Identifier variant="muted">{run.releaseVersion}</Identifier> : '—'}
                    </TableCell>
                    <TableCell>
                      <Tooltip
                        title={`${formatCount(run.counts.passed)} passed · ${formatCount(
                          run.counts.failed,
                        )} failed · ${formatCount(run.counts.blocked)} blocked`}
                      >
                        <Box>
                          <ResultBar counts={run.counts} testId="SuiteHistory.Row.ResultBar" />
                        </Box>
                      </Tooltip>
                    </TableCell>
                    <TableCell align="right">
                      <ResultCountsCell
                        counts={run.counts}
                        denominator={denominator}
                        testId="SuiteHistory.Row.PassRate"
                      />
                    </TableCell>
                    <TableCell align="right">
                      {/* The count each run actually covered, so a changed total is visible
                          next to the rate it moved. */}
                      <Tooltip title={`${formatCount(executedCount(run.counts))} executed`}>
                        <Box>
                          <Identifier>{formatCount(run.counts.total)}</Identifier>
                        </Box>
                      </Tooltip>
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                      {formatDateTime(run.executedAt)}
                    </TableCell>
                  </TableRow>
                </Fragment>
              );
            })}
          </TableBody>
        </Table>
      </Panel>
    </Stack>
  );
}
