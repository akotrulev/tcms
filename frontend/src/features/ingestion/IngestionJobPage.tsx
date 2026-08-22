import {
  Alert,
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
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useIngestion, useProject } from '../../api/queries';
import type { UnmatchedTest } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { formatDateTime } from '../../lib/format';
import {
  ingestedOutcomeLabels,
  ingestionFormatLabels,
  ingestionStatusLabels,
} from '../../lib/labels';
import { BindTestDialog } from './BindTestDialog';
import { DownloadRawReportButton } from './DownloadRawReportButton';

/**
 * One posted report — **the unmatched-test binder**, and the screen §117 named on its way out.
 *
 * ## What this screen is for
 *
 * §6c offers a quarantine folder with auto-created case stubs for tests the ladder cannot place.
 * §117 declined it: a CI job that can mint cases turns every test *rename* into a permanent
 * duplicate of a case that already exists — the identity problem `automationKey` exists to solve,
 * reintroduced by the feature meant to use it. That refusal is a design and not a gap **only if a
 * person can do the binding**, which is this page.
 *
 * ## The outcome column is not decoration
 *
 * An unmatched **failure** is a red test nobody is being told about. An unmatched pass is only
 * unrecorded coverage. Both are rows to work through and they are not equally urgent, which is why
 * the contract carries `outcome` on `UnmatchedTest` at all — and why the failures are marked here
 * rather than left for the reader to notice.
 *
 * ## The name is shown, the normalized form is what is sent
 *
 * The row shows `className#name` as the report wrote it, because that is what a person recognises
 * from their test source. `POST /automation-mappings` takes the name **raw** and normalizes it
 * server-side; this screen never normalizes, because the rule has to agree exactly with the one the
 * parser applies and a second implementation is how every mapping silently stops matching.
 *
 * ## A capped list says so
 *
 * The server serves at most 50 names beside a true `unmatchedCount`. When they differ this page
 * says which, because a reader who binds all 50 and sees the count unchanged would otherwise
 * conclude the binding did not work.
 *
 * ## The posted file is downloadable, and that is the other half of binding
 *
 * The table says which names the server could not place. It cannot say whether a test the person
 * *expected* was in the report at all — a test that never ran is absent from `unmatched` and absent
 * from the run, and looks identical to one that was never written. `reportUrl` is the only way to
 * tell those apart, which is why the control sits on this screen rather than on the history list.
 */
export function IngestionJobPage() {
  const { projectId = '', jobId = '' } = useParams();
  const [binding, setBinding] = useState<UnmatchedTest | null>(null);

  const project = useProject(projectId);
  const job = useIngestion(projectId, jobId);

  const posted = job.data ? formatDateTime(job.data.createdAt) : '';

  return (
    <Box>
      <PageHeader
        testId="Ingestion.Header"
        title={posted ? `Report posted ${posted}` : 'Posted report'}
        documentTitle={posted ? `Ingestion · ${posted}` : 'Ingestion'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'CI ingestion', to: `/projects/${projectId}/ingestions` },
          { label: 'Report' },
        ]}
        subtitle="What this report matched, and the tests it could not place. Binding a test name here makes every later build find it."
        meta={
          job.data ? (
            <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
              <Chip
                size="small"
                variant="outlined"
                label={ingestionStatusLabels[job.data.status]}
              />
              <Chip
                size="small"
                variant="outlined"
                label={ingestionFormatLabels[job.data.format]}
              />
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                {job.data.matchedCount} matched · {job.data.unmatchedCount} unmatched
              </Typography>
              {job.data.runId && job.data.runKey && (
                <RouterLink
                  to={`/projects/${projectId}/runs/${job.data.runId}`}
                  data-testid="Ingestion.RunLink"
                >
                  <Identifier>{job.data.runKey}</Identifier>
                </RouterLink>
              )}
              {/*
                In the header rather than in the panel below, because the panel is a DataRegion that
                renders nothing when every test matched — and a report where everything matched is
                exactly when somebody wants to check what was actually posted. The control renders
                itself away when `reportUrl` is null, which is every job from before uploads were
                kept.
              */}
              <DownloadRawReportButton job={job.data} testId="Ingestion.DownloadReport" />
            </Stack>
          ) : undefined
        }
      />

      <Panel testId="Ingestion.Panel" dense>
        <DataRegion
          testId="Ingestion.Unmatched"
          state={{ ...job, refetch: () => void job.refetch() }}
          isEmpty={(one) => one.unmatchedCount === 0}
          empty={{
            kind: 'no-records',
            title: 'Every test found a case',
            body: 'Nothing in this report needs binding.',
          }}
        >
          {(one) => (
            <Box>
              {/*
                A report that matched NOTHING is its own state and is worth saying out loud: no run
                was opened, which is a designed outcome rather than a failure, and it is the shape
                of the FIRST report most teams post — while they are still deciding whether the
                integration works at all.
              */}
              {one.runId === null && (
                <Alert severity="info" sx={{ mb: 2 }} data-testid="Ingestion.NoRun">
                  Nothing in this report matched a case, so no run was opened — a run of zero cases
                  would report itself complete. Bind these tests and post again.
                </Alert>
              )}

              {one.unmatched.length < one.unmatchedCount && (
                <Alert severity="warning" sx={{ mb: 2 }} data-testid="Ingestion.Capped">
                  Showing the first {one.unmatched.length} of {one.unmatchedCount} unmatched tests.
                  Bind these and post the report again to see the rest.
                </Alert>
              )}

              <ScrollX>
                <Table size="small" data-testid="Ingestion.UnmatchedGrid">
                  <TableHead>
                    <TableRow>
                      <TableCell sx={{ color: 'text.secondary' }}>Test</TableCell>
                      <TableCell sx={{ color: 'text.secondary', width: 130 }}>Reported</TableCell>
                      <TableCell sx={{ width: 150 }} align="right" />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {one.unmatched.map((test) => (
                      <TableRow key={test.normalizedName} data-testid="Ingestion.UnmatchedRow" hover>
                        <TableCell>
                          <Identifier>{test.name}</Identifier>
                        </TableCell>
                        <TableCell>
                          <Outcome outcome={test.outcome} />
                        </TableCell>
                        <TableCell align="right">
                          {/*
                            `case:edit`, disabled with the reason rather than hidden (§31). It is
                            the same key that writes a case's automationKey, and Automation Service
                            deliberately does not hold it — so a leaked CI token cannot re-point
                            where anybody's results land.
                          */}
                          <PermissionGate projectId={projectId} permission="case:edit">
                            {({ allowed, reason }) => (
                              <Tooltip
                                title={allowed ? 'Say which case this test verifies' : reason}
                              >
                                <span>
                                  <Button
                                    size="small"
                                    variant="outlined"
                                    startIcon={<LinkOutlinedIcon />}
                                    disabled={!allowed}
                                    onClick={() => setBinding(test)}
                                    data-testid="Ingestion.Bind"
                                  >
                                    Bind
                                  </Button>
                                </span>
                              </Tooltip>
                            )}
                          </PermissionGate>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </ScrollX>
            </Box>
          )}
        </DataRegion>
      </Panel>

      {binding && (
        <BindTestDialog
          open
          projectId={projectId}
          test={binding}
          onClose={() => setBinding(null)}
        />
      )}
    </Box>
  );
}

/**
 * What the runner said, with the failures marked.
 *
 * `ERRORED` is kept distinct from `FAILED` rather than folded into it: the two land on the same
 * `StatusKind`, but a report separating `<failure>` from `<error>` is telling you that one test
 * lost an assertion and another threw before it could make one — usually the environment rather
 * than the thing under test. On the screen that shows what was *reported*, that distinction is the
 * whole point.
 */
function Outcome({ outcome }: { outcome: UnmatchedTest['outcome'] }) {
  const bad = outcome === 'FAILED' || outcome === 'ERRORED';
  return (
    <Typography
      component="span"
      variant="body2"
      sx={{ color: bad ? 'var(--color-danger)' : 'text.secondary', fontWeight: bad ? 600 : 400 }}
    >
      {ingestedOutcomeLabels[outcome]}
    </Typography>
  );
}
