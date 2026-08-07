import {
  Box,
  Chip,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  useAutomationMappings,
  useIngestions,
  useProject,
} from '../../api/queries';
import type { AutomationMapping } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { formatDateTime } from '../../lib/format';
import { ingestionFormatLabels, ingestionStatusLabels } from '../../lib/labels';
import { UnbindMappingDialog } from './UnbindMappingDialog';

/**
 * **CI ingestion** — what has been posted to this project, and the test names somebody has bound.
 *
 * §117 built the endpoint a CI job posts to and closed by saying that declining §6c's auto-created
 * case stubs *"is only defensible for a caller who can read JSON"* until this screen exists. It is
 * the reason the ladder's fourth rung — a test that matched nothing — is a design and not a gap.
 *
 * ## Two regions, two gates, and they must not be collapsed into one
 *
 * The **history** needs only project membership: anybody who may read this project's runs may read
 * how their verdicts got there. The **mappings** are `case:read` to list and `case:edit` to change,
 * because confirming that a test name means a case decides where that test's results land.
 *
 * They are on one screen because they are one job — *"my tests are not landing anywhere"* — and
 * they are two `DataRegion`s because they can fail independently: a Test Engineer with no
 * `case:read` (there is no such role today, but the gate is the server's to change) gets the
 * history and a refusal in the second panel, not a broken screen.
 *
 * ## The empty state is the one that matters
 *
 * A project with no ingestions is the **normal** state for most of this product's life, and the
 * empty text is the only place the product ever says how to start posting. It names the endpoint
 * rather than apologising, because the reader of an empty ingestion history is somebody setting CI
 * up.
 */
export function IngestionsPage() {
  const { projectId = '' } = useParams();
  const [unbinding, setUnbinding] = useState<AutomationMapping | null>(null);

  const project = useProject(projectId);
  const jobs = useIngestions(projectId);
  const mappings = useAutomationMappings(projectId);

  return (
    <Box>
      <PageHeader
        testId="Ingestions.Header"
        title="CI ingestion"
        documentTitle={project.data ? `CI ingestion · ${project.data.name}` : 'CI ingestion'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'CI ingestion' },
        ]}
        subtitle="Reports your pipeline has posted, and what each one matched. A test that matched nothing recorded no result — bind it to a case here and every later build finds it."
      />

      <Panel testId="Ingestions.Panel" dense>
        <DataRegion
          testId="Ingestions.List"
          state={{ ...jobs, refetch: () => void jobs.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No reports posted yet',
            body: 'A CI job posts its results to POST /projects/…/ingestions with an API token. Issue one under Teams & roles → API tokens, and give it the Automation Service role.',
          }}
        >
          {(rows) => (
            <ScrollX>
              <Table size="small" data-testid="Ingestions.Grid">
                <TableHead>
                  <TableRow>
                    <TableCell sx={{ color: 'text.secondary', width: 180 }}>Posted</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 140 }}>Format</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 120 }}>State</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 110 }} align="right">
                      Matched
                    </TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 130 }} align="right">
                      Unmatched
                    </TableCell>
                    <TableCell sx={{ color: 'text.secondary' }}>Run</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((job) => (
                    <TableRow key={job.id} data-testid="Ingestions.Row" hover>
                      <TableCell>
                        {/*
                          The link is on the timestamp because it is the row's identity — a job has
                          no name and its id is a UUID nobody reads. `formatDateTime` gives the
                          reader something to match against their build log.
                        */}
                        <RouterLink
                          to={`/projects/${projectId}/ingestions/${job.id}`}
                          data-testid="Ingestions.Open"
                        >
                          {formatDateTime(job.createdAt)}
                        </RouterLink>
                      </TableCell>
                      <TableCell>{ingestionFormatLabels[job.format]}</TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          variant="outlined"
                          label={ingestionStatusLabels[job.status]}
                        />
                      </TableCell>
                      <TableCell align="right">{job.matchedCount}</TableCell>
                      <TableCell align="right">
                        {/*
                          Emphasised when non-zero, and NOT coloured as an error. An unmatched test
                          is not a failure — it is a result with nowhere to go, which is a thing to
                          act on rather than a thing that went wrong. Zero is left plain so the
                          eye lands on the rows that need work.
                        */}
                        <Typography
                          component="span"
                          variant="body2"
                          sx={{ fontWeight: job.unmatchedCount > 0 ? 600 : 400 }}
                        >
                          {job.unmatchedCount}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <RunCell projectId={projectId} runId={job.runId} runKey={job.runKey} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </ScrollX>
          )}
        </DataRegion>
      </Panel>

      <Panel testId="Ingestions.MappingsPanel" title="Test name mappings" dense>
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
          A confirmed binding survives renaming the case, the suite and the project — it is a fact
          about the pair. Only renaming the test itself breaks it.
        </Typography>
        <DataRegion
          testId="Ingestions.Mappings"
          state={{ ...mappings, refetch: () => void mappings.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'Nothing bound by hand',
            body: 'Open a report above and bind the tests it could not place. Cases that carry an automation key, or whose key appears in the test name, need no binding.',
          }}
        >
          {(rows) => (
            <ScrollX>
              <Table size="small" data-testid="Ingestions.MappingsGrid">
                <TableHead>
                  <TableRow>
                    <TableCell sx={{ color: 'text.secondary' }}>Test</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 140 }}>Case</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 180 }}>Confirmed</TableCell>
                    <TableCell sx={{ width: 60 }} />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((mapping) => (
                    <TableRow key={mapping.id} data-testid="Ingestions.MappingRow" hover>
                      <TableCell>
                        <Identifier>{mapping.testName}</Identifier>
                      </TableCell>
                      <TableCell>
                        {mapping.caseKey ? (
                          <RouterLink to={`/projects/${projectId}/cases/${mapping.caseId}`}>
                            <Identifier>{mapping.caseKey}</Identifier>
                          </RouterLink>
                        ) : (
                          /*
                            The case was deleted. The row is deliberately still here and says so
                            rather than being filtered out: this binding still consumes the test
                            name, so every build still resolves that test to a case that is gone —
                            and hiding the row would hide the only thing that can be removed to fix
                            it.
                          */
                          <Tooltip title="The case this points at has been deleted, so results for this test land nowhere. Remove the mapping.">
                            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                              Deleted case
                            </Typography>
                          </Tooltip>
                        )}
                      </TableCell>
                      <TableCell>{formatDateTime(mapping.confirmedAt)}</TableCell>
                      <TableCell align="right">
                        {/* Disable-don't-hide (§31): the row is readable by anyone who can read
                            cases, and only `case:edit` may unbind. */}
                        <PermissionGate projectId={projectId} permission="case:edit">
                          {({ allowed, reason }) => (
                            <Tooltip title={allowed ? 'Remove this binding' : reason}>
                              <span>
                                <IconButton
                                  size="small"
                                  disabled={!allowed}
                                  onClick={() => setUnbinding(mapping)}
                                  aria-label={`Remove the binding for ${mapping.testName}`}
                                  data-testid="Ingestions.Unbind"
                                >
                                  <DeleteOutlineIcon fontSize="small" />
                                </IconButton>
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
          )}
        </DataRegion>
      </Panel>

      {unbinding && (
        <UnbindMappingDialog
          open
          projectId={projectId}
          mapping={unbinding}
          onClose={() => setUnbinding(null)}
        />
      )}
    </Box>
  );
}

/**
 * The run cell — and the three states it has to tell apart.
 *
 * `runId` null is *"this report matched nothing, so no run was opened"*, which is a designed
 * outcome and not an error: a run of zero cases would report 0 of 0, 100%, complete. `runId` with
 * no `runKey` is a run that has since been **deleted** — the server withholds the key rather than
 * offering a link that 404s. Only the third state is a link.
 *
 * Collapsing the first two into one dash is the tempting version, and it would tell somebody whose
 * run was deleted that their report matched nothing.
 */
function RunCell({
  projectId,
  runId,
  runKey,
}: {
  projectId: string;
  runId: string | null;
  runKey: string | null;
}) {
  if (!runId) {
    return (
      <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75 }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          No run
        </Typography>
        <Tooltip title="Nothing in this report matched a case, so no run was opened. A run of zero cases would report itself complete.">
          <Typography variant="body2" sx={{ color: 'text.disabled' }}>
            —
          </Typography>
        </Tooltip>
      </Stack>
    );
  }
  if (!runKey) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        Run deleted
      </Typography>
    );
  }
  return (
    <RouterLink to={`/projects/${projectId}/runs/${runId}`}>
      <Identifier>{runKey}</Identifier>
    </RouterLink>
  );
}
