import { Link as RouterLink, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { isJobFinished, useProject, useReportJob, useReportModel } from '../../api/queries';
import type { ReportJob } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Banner } from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { Button } from '../../components/ui';
import { humanize, reportJobStatusLabels } from '../../lib/labels';
import { DownloadReportButton } from './DownloadReportButton';
import { PinReportButton } from './PinReportButton';
import { ReportView } from './ReportView';

/**
 * One report, on screen — F2 of the M2.5 plan, and the pair to §89's download.
 *
 * ## Why this is a route and not a dialog on the reports screen
 *
 * `ui-rules` §7: a modal is for a short, self-contained task that needs the screen behind it. A
 * report is neither — it is a document somebody reads, scrolls, and refers back to, and it benefits
 * from a URL: a link to *this* address can be pasted to a colleague who holds the project grant.
 *
 * ~~A job id in the address bar is the only durable handle a report has in this version: nothing
 * lists jobs, so §89's cards live in component state and a reload loses them. … A list endpoint is
 * still the missing piece and still the server's.~~ → **§227 shipped it.** The reports screen reads
 * `GET …/reports/jobs`, so this URL is now a convenience rather than the only way back to a report.
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
 * ## Two things can be done with a finished report, and until C6 only one of them existed
 *
 * `DownloadReportButton` takes it away; `PinReportButton` keeps it. The second closes the loop
 * between two features that had shipped separately — `reporting` has produced artifacts since §87
 * and `V1` declared `artifact.report_artifact_id` in M0 — so "these were the numbers we signed the
 * cycle off on" was a claim the product could generate and could register and could not do both.
 *
 * ## The 403 is worded here rather than left generic, and what it means changed in §227
 *
 * ~~The artifact route is gated on **`report:export`**, because it is the download route and the
 * stored model is served through it. So a persona holding `report:view` alone — the split §6e
 * describes and §87 built a custom role to make expressible, *"may read on screen, may not carry
 * off"* — can watch this job succeed and then be refused the report it just watched being made.
 * The fix belongs to the server and is recorded in the handoff — this screen must not pretend the
 * split works.~~ → **the server fixed it.** `modelUrl` points at `GET …/reports/jobs/{jobId}/model`
 * and costs `report:view`, so this 403 is now the ordinary one: a caller who cannot read reports
 * here at all. The sentence still names the key, because `describeError`'s generic copy would say
 * "the permission this view needs", which is true and useless.
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
    <div>
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
        actions={
          job.data ? (
            <div className="flex items-start gap-2">
              <DownloadReportButton job={job.data} testId={`${TestId}.Download`} />
              {/*
                Beside the download, because the two are the same question asked over different
                time horizons: one takes the report away now, the other keeps it where the project
                will still find it. The title is the model's once it is readable — the same words
                the exported document carries — so the register's entry and the file agree.
              */}
              <PinReportButton
                job={job.data}
                projectId={projectId}
                reportTitle={title}
                testId={`${TestId}.Pin`}
              />
            </div>
          ) : null
        }
      />

      <DataRegion
        testId={`${TestId}.Job`}
        state={{ ...job, refetch: () => void job.refetch() }}
        skeleton={<p className="m-0 text-[13px] text-ink-2">Finding that report…</p>}
      >
        {(row) => <JobBody job={row} model={model} projectId={projectId} />}
      </DataRegion>
    </div>
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
      <section data-testid={`${TestId}.Waiting`}>
        <Banner tone="steel">
          <span data-testid={`${TestId}.Waiting.Text`}>
            {reportJobStatusLabels[job.status]} — this page updates itself when the report is ready.
          </span>
        </Banner>
      </section>
    );
  }

  if (job.status === 'FAILED') {
    // The worker's own sentence, for §89's reason: a report's parameters are validated inside the
    // worker, so this is the only place that refusal is ever seen. The way out is offered beside it
    // — an error naming a state the UI gives no path out of is the design bug `ui-rules` §4 names.
    return (
      <div role="alert" data-testid={`${TestId}.Failed`}>
        <Banner tone="bad">
          <span>
            <strong className="block font-medium text-ink">This report was not produced</strong>
            <span className="mt-1 block">
              {job.error ?? 'The report could not be generated, and the server did not say why.'}
            </span>
            <Button asChild size="sm" className="mt-3" data-testid={`${TestId}.Failed.Back`}>
              <RouterLink to={`/projects/${projectId}/reports`}>Back to reports</RouterLink>
            </Button>
          </span>
        </Banner>
      </div>
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
      <Banner tone="steel" data-testid={`${TestId}.NoModel`}>
        <span>
          This report has no stored model, so it cannot be shown here. Download it in the format it
          was generated in.
        </span>
      </Banner>
    );
  }

  if (model.error instanceof ApiError && model.error.status === 403) {
    return (
      <Banner tone="warn" data-testid={`${TestId}.Forbidden`}>
        <span>
          <strong className="block font-medium text-ink">
            You cannot read reports in this project
          </strong>
          <span className="mt-1 block">
            Opening a report needs <strong className="font-mono text-ink-2">report:view</strong>,
            which you do not have here. Ask a project lead to grant it.
          </span>
        </span>
      </Banner>
    );
  }

  /*
    THE ONE SURFACE IN THE PRODUCT THAT IS NOT DARK, and now actually not dark.

    007 D1 and design language §9.1: a PDF preview drawn dark misrepresents the file being
    downloaded. ~~So `ReportView` keeps its light surface and this task must not "fix" it.~~ →
    **it did not have one.** This comment stated the intent and the code centred the preview on
    `--field`, which is a THEME token — recessed in the light palette and dark in the dark one — so
    in dark mode the preview was as dark as everything else and the sentence above it was false.
    The 2026-08-30 renders audit measured it (2.5).

    `data-color-scheme="light"` on this subtree is what makes it true. It works because `@theme
    inline` emits `var(--…)` rather than copying values, so redeclaring the tokens on an ancestor
    moves every utility below it — and because `index.css` now writes its light palette under
    `:root, [data-color-scheme='light']`, so a nested element can ask for it. `app/colorScheme.tsx`
    still owns the app's choice and still writes the attribute on the root; this is a local,
    deliberate exception and the only one in the product.
  */
  return (
    <div
      data-color-scheme="light"
      data-testid={`${TestId}.Preview`}
      className="rounded-lg bg-field p-4 text-ink md:p-6"
    >
      <DataRegion
        testId={`${TestId}.Model`}
        state={{ ...model, refetch: () => void model.refetch() }}
        skeleton={<p className="m-0 text-[13px] text-ink-2">Loading the report…</p>}
      >
        {(document) => <ReportView document={document} />}
      </DataRegion>
    </div>
  );
}
