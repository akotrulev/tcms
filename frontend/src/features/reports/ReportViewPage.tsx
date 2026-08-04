import { Alert, AlertTitle, Box, Button, CircularProgress, Stack, Typography } from '@mui/material';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { isJobFinished, useProject, useReportJob, useReportModel } from '../../api/queries';
import type { ReportJob } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { humanize, reportJobStatusLabels } from '../../lib/labels';
import { DownloadReportButton } from './DownloadReportButton';
import { ReportView } from './ReportView';

/**
 * One report, on screen — F2 of the M2.5 plan, and the pair to §89's download.
 *
 * ## Why this is a route and not a dialog on the reports screen
 *
 * `ui-rules` §7: a modal is for a short, self-contained task that needs the screen behind it. A
 * report is neither — it is a document somebody reads, scrolls, and refers back to, and it benefits
 * from a URL in the one way this feature badly needs. **A job id in the address bar is the only
 * durable handle a report has in this version**: nothing lists jobs, so §89's cards live in
 * component state and a reload loses them. A link to *this* URL survives that, and can be pasted to
 * a colleague who holds the project grant.
 *
 * That is **not** the client-side job register §89 refused. The refusal was about inventing a list
 * the server does not have; this URL names one job and is resolved by the server's own
 * `GET …/reports/jobs/{jobId}`, which is the operation that exists. A list endpoint is still the
 * missing piece and still the server's.
 *
 * ## The page is a job first and a report second, because the job is what exists
 *
 * Landing here on a PENDING job is the ordinary case — the link on a card is offered the moment the
 * model URL appears, and a pasted URL can arrive at any moment in the job's life. So this renders
 * the job's four states, and `useReportModel` is gated on `modelUrl` (`data-fetching`'s dependent-
 * query rule): while the job is running the page shows the job running rather than a skeleton for a
 * fetch that has not been made, and when the poll lands COMPLETED the model request fires by
 * itself. FAILED renders the worker's sentence and stops — the poll stops with it.
 *
 * ## The 403 is the slice's finding and is worded here rather than left generic
 *
 * The artifact route is gated on **`report:export`**, because it is the download route and the
 * stored model is served through it. So a persona holding `report:view` alone — the split §6e
 * describes and §87 built a custom role to make expressible, *"may read on screen, may not carry
 * off"* — can watch this job succeed and then be refused the report it just watched being made.
 * `describeError`'s generic 403 copy would tell them to ask for "the permission this view needs",
 * which is true and useless; the sentence below names the key and says plainly that the on-screen
 * view and the download are the same permission today. The fix belongs to the server and is
 * recorded in the handoff — this screen must not pretend the split works.
 */

const TestId = 'ReportView';

export function ReportViewPage() {
  const { projectId = '', jobId = '' } = useParams();
  const project = useProject(projectId);
  const job = useReportJob(projectId, jobId);
  const model = useReportModel(projectId, jobId, job.data?.modelUrl);

  // The report's own title once it is readable, and the job's subject before that. A tab reading
  // "Report" for the two minutes a job takes is the generic title `ui-rules` §6 forbids, and the
  // model's title is the one the exported document carries — the same words in both places.
  const title = model.data?.report.title ?? (job.data ? humanize(job.data.type) : 'Report');

  return (
    <Box>
      <PageHeader
        testId={`${TestId}.Header`}
        title={title}
        documentTitle={project.data ? `${title} · ${project.data.name}` : title}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Reports', to: `/projects/${projectId}/reports` },
          { label: title },
        ]}
        subtitle="What you see here is the model the exported file was rendered from — the same numbers, the same header."
        actions={job.data ? <DownloadReportButton job={job.data} testId={`${TestId}.Download`} /> : null}
      />

      <DataRegion
        testId={`${TestId}.Job`}
        state={{ ...job, refetch: () => void job.refetch() }}
        skeleton={<Typography variant="body2">Finding that report…</Typography>}
      >
        {(row) => <JobBody job={row} model={model} projectId={projectId} />}
      </DataRegion>
    </Box>
  );
}

function JobBody({
  job,
  model,
  projectId,
}: {
  job: ReportJob;
  model: ReturnType<typeof useReportModel>;
  projectId: string;
}) {
  if (!isJobFinished(job.status)) {
    return (
      <Panel testId={`${TestId}.Waiting`} dense>
        <Stack direction="row" sx={{ alignItems: 'center', gap: 1.5, p: 2 }}>
          <CircularProgress size={16} />
          <Typography variant="body2" data-testid={`${TestId}.Waiting.Text`}>
            {reportJobStatusLabels[job.status]} — this page updates itself when the report is ready.
          </Typography>
        </Stack>
      </Panel>
    );
  }

  if (job.status === 'FAILED') {
    // The worker's own sentence, for §89's reason: a report's parameters are validated inside the
    // worker, so this is the only place that refusal is ever seen. The way out is offered beside it
    // — an error naming a state the UI gives no path out of is the design bug `ui-rules` §4 names.
    return (
      <Alert severity="error" role="alert" data-testid={`${TestId}.Failed`}>
        <AlertTitle sx={{ fontSize: '0.875rem', fontWeight: 600 }}>
          This report was not produced
        </AlertTitle>
        <Typography variant="body2" sx={{ mb: 1.5 }}>
          {job.error ?? 'The report could not be generated, and the server did not say why.'}
        </Typography>
        <Button
          component={RouterLink}
          to={`/projects/${projectId}/reports`}
          size="small"
          variant="outlined"
          color="error"
          data-testid={`${TestId}.Failed.Back`}
        >
          Back to reports
        </Button>
      </Alert>
    );
  }

  /*
    COMPLETED with no `modelUrl` — reachable only from a deployment older than §88's JSON artifact,
    or from a store that lost the file. It is stated rather than left to the region, which would sit
    on a skeleton for ever: `useReportModel` is gated on the URL, so with no URL there is no request
    to succeed or fail, and an eternal spinner is the dependent-query failure `data-fetching` names.
    The download is still offered above, because the format that was asked for is a different file.
  */
  if (!job.modelUrl) {
    return (
      <Alert severity="info" data-testid={`${TestId}.NoModel`}>
        This report has no stored model, so it cannot be shown here. Download it in the format it was
        generated in.
      </Alert>
    );
  }

  if (model.error instanceof ApiError && model.error.status === 403) {
    return (
      <Alert severity="warning" data-testid={`${TestId}.Forbidden`}>
        <AlertTitle sx={{ fontSize: '0.875rem', fontWeight: 600 }}>
          You can watch this report being made, but not open it
        </AlertTitle>
        <Typography variant="body2">
          Reading a report on screen needs the same permission as downloading one
          (<strong>report:export</strong>), which you do not have on this project. Ask a project lead
          to grant it.
        </Typography>
      </Alert>
    );
  }

  return (
    <DataRegion
      testId={`${TestId}.Model`}
      state={{ ...model, refetch: () => void model.refetch() }}
      skeleton={<Typography variant="body2">Loading the report…</Typography>}
    >
      {(document) => <ReportView document={document} />}
    </DataRegion>
  );
}
