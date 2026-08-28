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
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import type { TestRun } from '../../api/types';
import { useProject, useRuns } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ResultBar } from '../../components/ResultBar';
import { RunCreateDialog } from './RunCreateDialog';
import { executedCount, passRate } from '../../lib/results';
import { EM_DASH, formatAge, formatCount, formatDateTime, formatDuration, formatPercent } from '../../lib/format';
import { runSourceLabels, runStatusColors, runStatusLabels } from '../../lib/labels';
import { ScrollX } from '../../components/ScrollX';
import { useAnnouncedResults } from '../../a11y/useAnnouncedResults';

/**
 * Test runs. A run is one execution pass of a plan on one configuration, and the two facts a
 * reader needs first are whether it finished and how it went — so status and the result bar
 * lead, and the metadata follows.
 *
 * Manual and ingested runs sit in the same list on purpose: a nightly CI run and a hand-driven
 * Safari sweep are both evidence about the same release, and splitting them into two screens
 * is how a team ends up with two different views of its own quality.
 */
export function RunListPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const project = useProject(projectId);
  const runs = useRuns(projectId);
  useAnnouncedResults(
    runs,
    `${formatCount(runs.data?.length ?? 0)} ${(runs.data?.length ?? 0) === 1 ? 'run' : 'runs'} shown`,
  );
  const [creating, setCreating] = useState(false);

  return (
    <Box>
      <PageHeader
        testId="Runs.Header"
        title="Test runs"
        documentTitle={project.data ? `Test runs · ${project.data.name}` : 'Test runs'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test runs' },
        ]}
        subtitle="Every pass over a plan, manual or ingested from CI."
        actions={
          /*
            `run:create` is the widest write gate in the product — every role but Viewer holds it,
            including Automation Service, because a CI job opens the run it is about to post into.
            Gated all the same: the one role that does not hold it is the one most likely to be
            looking at this screen.
          */
          <PermissionGate projectId={projectId} permission="run:create">
            {({ allowed }) => (
              <Button
                variant="contained"
                disabled={!allowed}
                onClick={() => setCreating(true)}
                data-testid="Runs.New"
              >
                New run
              </Button>
            )}
          </PermissionGate>
        }
      />

      {creating && (
        <RunCreateDialog
          projectId={projectId}
          open
          onClose={() => setCreating(false)}
          /* Straight to the checklist — the run was created in order to work through it. */
          onCreated={(run) => navigate(`/projects/${projectId}/runs/${run.id}`)}
        />
      )}

      <Panel testId="Runs.Panel" dense>
        <DataRegion
          testId="Runs.List"
          state={{ ...runs, refetch: () => void runs.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No runs yet',
            body: 'Start a run against a plan, or point your CI job at the results endpoint.',
          }}
        >
          {(rows) => <RunTable rows={rows} projectId={projectId} />}
        </DataRegion>
      </Panel>
    </Box>
  );
}

function RunTable({ rows, projectId }: { rows: TestRun[]; projectId: string }) {
  return (
    <ScrollX>
      <Table size="small" data-testid="Runs.Grid">
        <TableHead>
          <TableRow>
            <TableCell sx={{ color: 'text.secondary' }}>Run</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 120 }}>Status</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 160 }}>Configuration</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 130 }}>Release</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 180 }}>Result</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 90 }} align="right">
              Pass rate
            </TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 130 }}>Started</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((run) => {
            const executed = executedCount(run.counts);
            return (
              <TableRow key={run.id} data-testid="Runs.Row" hover>
                <TableCell>
                  <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1, minWidth: 0 }}>
                    <RouterLink to={`/projects/${projectId}/runs/${run.id}`} data-testid="Runs.Row.Link">
                      <Identifier>{run.key}</Identifier>
                    </RouterLink>
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" data-testid="Runs.Row.Name">
                        {run.name}
                      </Typography>
                      {run.planKey && (
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          <Identifier variant="muted">{run.planKey}</Identifier>
                        </Typography>
                      )}
                    </Box>
                  </Stack>
                </TableCell>
                <TableCell>
                  <Stack sx={{ gap: 0.25 }}>
                    <Chip
                      size="small"
                      label={runStatusLabels[run.status]}
                      data-testid="Runs.Row.Status"
                      sx={{
                        bgcolor: 'transparent',
                        color: runStatusColors[run.status],
                        border: `1px solid color-mix(in srgb, ${runStatusColors[run.status]} 45%, transparent)`,
                        alignSelf: 'flex-start',
                      }}
                    />
                    {/* Where the results came from changes how much a reader trusts them and
                        who to ask about a failure, so it's shown rather than implied. */}
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      {runSourceLabels[run.source]}
                    </Typography>
                  </Stack>
                </TableCell>
                <TableCell sx={{ fontSize: '0.75rem' }}>{run.configuration?.name ?? EM_DASH}</TableCell>
                <TableCell sx={{ fontSize: '0.75rem' }}>
                  {run.releaseVersion ? (
                    <>
                      <Identifier>{run.releaseVersion}</Identifier>
                      {run.buildNumber && (
                        <Box sx={{ color: 'text.secondary' }}>
                          <Identifier variant="muted">{run.buildNumber}</Identifier>
                        </Box>
                      )}
                    </>
                  ) : (
                    <Box sx={{ color: 'text.secondary' }}>—</Box>
                  )}
                </TableCell>
                <TableCell>
                  <Tooltip
                    title={`${formatCount(run.counts.passed)} passed · ${formatCount(run.counts.failed)} failed · ${formatCount(
                      run.counts.blocked,
                    )} blocked · ${formatCount(run.counts.notRun)} not run`}
                  >
                    <Box>
                      <ResultBar counts={run.counts} testId="Runs.Row.ResultBar" />
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        {formatCount(executed)} of {formatCount(run.counts.total)} executed
                      </Typography>
                    </Box>
                  </Tooltip>
                </TableCell>
                <TableCell align="right">
                  {/* Of what ran — an in-progress run at 12 passed of 15 executed is at 80%,
                      not at 29% of its 42-case scope. Both readings are available on the run. */}
                  <Identifier>{formatPercent(passRate(run.counts, 'EXECUTED'), 0)}</Identifier>
                </TableCell>
                <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                  <Tooltip title={formatDateTime(run.startedAt)}>
                    <Box component="span">{formatAge(run.startedAt)}</Box>
                  </Tooltip>
                  {run.completedAt && run.startedAt && (
                    <Box>
                      took{' '}
                      <Identifier variant="muted">
                        {formatDuration(new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime())}
                      </Identifier>
                    </Box>
                  )}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </ScrollX>
  );
}
