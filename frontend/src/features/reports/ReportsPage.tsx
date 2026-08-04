import { Box, Button, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import AssessmentOutlinedIcon from '@mui/icons-material/AssessmentOutlined';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useProject, useReportDefinitions } from '../../api/queries';
import type { ReportDefinition, ReportJob } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { humanize } from '../../lib/labels';
import { GenerateReportDialog } from './GenerateReportDialog';
import { ReportJobCard } from './ReportJobCard';

/**
 * §6e's reports screen — F1 of the M2.5 plan, and the first client for §87's pipeline.
 *
 * ## The screen has to remember what it asked for, because nothing else does
 *
 * `report_job` rows are per-request and there is **no operation that lists them**: `POST
 * /reports/generate` hands back a job and `GET /reports/jobs/{jobId}` resolves one by id, and that
 * is the whole surface. So the set of jobs this screen shows is the set *this tab* created, held
 * in component state, and a reload loses the download links — the reports themselves are still
 * generated and their artifacts still exist, and nothing in this version can find them again.
 *
 * That is a real gap and it is stated here rather than papered over with copy that implies
 * otherwise (the card says it too). It is **not** worked around by writing job ids into the URL or
 * into storage: a list endpoint is the fix, it belongs to the server, and a client-side register of
 * ids would be a second, drifting copy of a table that already exists — the exact shape §81 found
 * when the mock invented a feature the server did not have.
 *
 * ## Two panels, and the empty one is deliberate
 *
 * Saved definitions (§6e's "the same cycle report regenerates next release with one click") have a
 * declared, served `GET` that answers `[]` on every deployment until R5 gives it a writer. The
 * panel is built anyway and says exactly that — there is **no Save button**, because `ui-rules`
 * forbids an Add control whose endpoint does not exist, and a screen that only learns to render
 * the list once rows appear is one written against a shape nobody has seen.
 *
 * ## What is gated and what is not
 *
 * **Generate** asks `report:export` and is disabled-with-a-reason (§31), never hidden. Nothing else
 * here is gated: the definition list is a project-visibility read, and the job poll needs
 * `report:view`, which every seeded role but Automation Service holds — and that role is API-token
 * only, so a gate on it would refuse nobody who can reach this page. **The rail item is likewise
 * ungated**, which is a deliberate departure from the plan's line ("rail item gated on
 * `report:view`"): no rail item in this app is gated, the `Review` item's own comment records why
 * — hiding an entry leaves a reader with no way to find out why they cannot see something — and a
 * gate that refuses nobody is decorative until the day it silently is not.
 */
export function ReportsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const definitions = useReportDefinitions(projectId);
  const [generating, setGenerating] = useState(false);

  /*
    Newest first, and the whole job rather than the id alone would be a second copy of a row the
    query cache already holds — `useGenerateReport` seeds it from the 202, so the card renders the
    real first state from one source.
  */
  const [jobIds, setJobIds] = useState<string[]>([]);
  const remember = (job: ReportJob) => setJobIds((ids) => [job.jobId, ...ids]);

  return (
    <Box>
      <PageHeader
        testId="Reports.Header"
        title="Reports"
        documentTitle={project.data ? `Reports · ${project.data.name}` : 'Reports'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Reports' },
        ]}
        subtitle="Generated in the background and downloaded as a file. Every report carries a header saying what it covers, who asked for it and when."
        actions={
          <PermissionGate projectId={projectId} permission="report:export">
            {({ allowed }) => (
              <Button
                variant="contained"
                startIcon={<AssessmentOutlinedIcon />}
                disabled={!allowed}
                onClick={() => setGenerating(true)}
                data-testid="Reports.GenerateButton"
              >
                Generate
              </Button>
            )}
          </PermissionGate>
        }
      />

      {generating && (
        <GenerateReportDialog
          open
          projectId={projectId}
          onClose={() => setGenerating(false)}
          onQueued={remember}
        />
      )}

      {jobIds.length > 0 && (
        <Panel testId="Reports.JobsPanel" title="Requested in this tab">
          <Stack sx={{ gap: 1.5 }} data-testid="Reports.Jobs">
            {jobIds.map((jobId) => (
              <ReportJobCard key={jobId} projectId={projectId} jobId={jobId} />
            ))}
          </Stack>
        </Panel>
      )}

      <Panel testId="Reports.DefinitionsPanel" title="Saved reports" dense>
        <DataRegion
          testId="Reports.Definitions"
          state={{ ...definitions, refetch: () => void definitions.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No saved reports',
            body: 'A saved report keeps its settings so the same one can be regenerated next release. Nothing can create one in this version yet — use Generate above for a one-off.',
          }}
        >
          {(rows) => <DefinitionTable rows={rows} />}
        </DataRegion>
      </Panel>
    </Box>
  );
}

function DefinitionTable({ rows }: { rows: ReportDefinition[] }) {
  // By name, which is how somebody looks for one. A unique tiebreaker on the id, so two
  // definitions sharing a name cannot swap places between renders.
  const sorted = [...rows].sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));

  return (
    <ScrollX>
      <Table size="small" data-testid="Reports.Definitions.Grid">
        <TableHead>
          <TableRow>
            <TableCell sx={{ color: 'text.secondary' }}>Report</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 200 }}>Kind</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 200 }}>Owner</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {sorted.map((definition) => (
            <TableRow key={definition.id} data-testid="Reports.Definitions.Row" hover>
              <TableCell>
                <Typography variant="body2" sx={{ fontWeight: 500 }}>
                  {definition.name}
                </Typography>
              </TableCell>
              <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                {humanize(definition.type)}
              </TableCell>
              <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                {definition.owner.displayName}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </ScrollX>
  );
}
