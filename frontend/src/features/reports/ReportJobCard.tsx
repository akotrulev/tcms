import { buttonVariants } from '../../components/ui/buttonVariants';
import { Eye } from 'lucide-react';
import { Badge, Banner } from '../../components/kit';
import { Link as RouterLink } from 'react-router-dom';
import { isJobFinished } from '../../api/queries';
import type { ReportJob } from '../../api/types';
import { formatBytes, formatDateTime } from '../../lib/format';
import {
  humanize,
  reportFormatLabels,
  reportJobStatusColors,
  reportJobStatusLabels,
} from '../../lib/labels';
import { DownloadReportButton } from './DownloadReportButton';

/**
 * One generation request, watched to its end.
 *
 * ## It takes the row and fetches nothing — changed in §227
 *
 * ~~The card polls its own job by id.~~ It used to, because the screen above it had no list: each
 * card was the only thing that knew a job existed, so each held its own `useReportJob`. With
 * `GET …/reports/jobs` serving the whole set, one poll answers every card, and keeping the
 * per-card fetch would mean *n* requests every two seconds for rows the page already has — plus
 * two ages of one job on screen whenever a card's poll and the list's landed apart.
 *
 * So this is presentational. The page owns the fetching, which is also what lets the region's
 * loading, empty and failed states be stated once for the panel instead of once per card.
 *
 * ## The four states are four renderings, and FAILED is the one that gets built wrong
 *
 * `PENDING` and `RUNNING` are a spinner with the state's own word — the pair is what makes the
 * wait legible, so they are not collapsed into "Working…". `COMPLETED` is the only state with a
 * download. `FAILED` carries the server's sentence in an error alert and **must not be
 * indistinguishable from a report with nothing in it** — that is §85's 404-versus-`200 []`
 * argument one screen over, and the specific defect the plan names for this screen.
 *
 * ## `downloadUrl` is required-and-nullable, and the null is not a rare case
 *
 * It is null for the whole of PENDING and RUNNING and null **for ever** on a FAILED job — so it is
 * null on most renders of this card, and a link rendered from it unconditionally is a link to
 * `undefined` on the majority of them. The button is keyed on the URL being present rather than on
 * the status being COMPLETED: the two agree today, and the one that cannot produce a broken link
 * is the URL.
 */

const TestId = 'Reports.Job';

/**
 * `ReportJob.format` is a bare string on the wire, so this is a lookup with a fallback rather than
 * an index — and the fallback matters: a job generated before a format was retired, or by a
 * deployment ahead of this client, echoes back a value `reportFormatLabels` has no key for, and
 * `undefined` rendered as the file's type is worse than the raw word.
 */
const formatLabel = (format: string): string =>
  reportFormatLabels[format as keyof typeof reportFormatLabels] ?? format;

export function ReportJobCard({ projectId, job }: { projectId: string; job: ReportJob }) {
  return (
    <div className="border border-line rounded-lg p-4" data-testid={TestId}>
      <JobBody job={job} projectId={projectId} />
    </div>
  );
}

function JobBody({ job, projectId }: { job: ReportJob; projectId: string }) {
  const running = !isJobFinished(job.status);

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2 flex-wrap">
        {/*
          Humanized, not the wire value: the report type is an open string, so there is no map to
          derive a label from — `humanize` turns `run_summary` into "Run summary" for whatever a
          later release adds, where a lookup table would fall through to the raw token (ui-rules §1).
        */}
        <p className="m-0 text-sm font-semibold" data-testid={`${TestId}.Type`}>
          {humanize(job.type)}
        </p>
        <Badge tone="plain"
          data-testid={`${TestId}.Format`}
        >
{formatLabel(job.format)}</Badge>
        {/*
          When it was asked for. On the card rather than only in a tooltip because the list is
          ordered by it: an order a reader cannot see is one they have to take on trust, and two
          run summaries of the same project are told apart by nothing else.
        */}
        <span className="m-0 text-xs text-ink-2" data-testid={`${TestId}.RequestedAt`}>
          {formatDateTime(job.requestedAt)}
          {/*
            The rendered artifact's size (005 S1) — `Artifact.sizeBytes`, joined onto the job so a
            list of them can say "2.4 MB" without a call per row.

            Beside the timestamp rather than in a column: it is a property of the download, and the
            one decision it informs — is this worth opening on a phone — is made at the moment
            somebody looks at the button. Null until COMPLETED and null for ever on a FAILED one,
            on the same terms as `downloadUrl`, so it simply does not render rather than showing a
            zero that would claim an empty file.
          */}
          {job.sizeBytes !== null && (
            <span data-testid={`${TestId}.Size`}>
              {' · '}
              {formatBytes(job.sizeBytes)}
            </span>
          )}
        </span>
        <div className="flex-1" />
        {running && (
          // ~~`CircularProgress`~~ → a bordered ring, T16. `motion-safe:` so it obeys the
          // reduced-motion block in `index.css`; the surrounding row already says the job is
          // running in words, so a still ring costs nothing.
          <span
            aria-hidden
            data-testid={`${TestId}.Spinner`}
            className="size-3.5 shrink-0 rounded-full border-2 border-line-strong border-t-steel motion-safe:animate-spin"
          />
        )}
        <Badge
          tone="plain"
          data-testid={`${TestId}.Status`}
          style={{
            backgroundColor: 'transparent',
            color: reportJobStatusColors[job.status],
            borderColor: `color-mix(in srgb, ${reportJobStatusColors[job.status]} 45%, transparent)`,
          }}
        >
          {reportJobStatusLabels[job.status]}
        </Badge>
      </div>

      {/*
        The server's sentence, not a rephrasing of it. A report's parameters are validated inside
        the worker (a `runId` naming another project's run cannot be checked without a database
        read), so this is the only place that refusal is ever seen — §82's rule about never keying
        on a server sentence applies to *branching*, not to showing it.
      */}
      {job.status === 'FAILED' && (
        <div data-testid={`${TestId}.Failed`}>
<Banner tone="bad"><span role="alert">
          {job.error ?? 'The report could not be generated, and the server did not say why.'}
        </span></Banner>
</div>
      )}

      {/*
        Two affordances on a finished report and each keys on its OWN url, never on the status:
        `downloadUrl` is the format that was asked for, `modelUrl` is the stored model the screen
        renders (§90/F2). They are written in the same instant by the same worker, so today they
        appear together — and keying each on the URL it needs is what stops a deployment that
        produced only one of the two from rendering a link to `undefined`.

        The view is a real `<Link>`, not an `onClick` navigation: it earns middle-click, ⌘-click
        and copy-link for free. ~~Copying that link is the one way a report outlives this tab
        while nothing lists jobs.~~ → the list is the way now; the link is how a report is handed
        to a colleague who holds the project grant.

        Neither is permission-gated. The caller held `report:export` to reach the 202 that made
        this card, and a control disabled for the person who created the thing it opens teaches
        the wrong rule — the refusal, if it somehow comes, is rendered where it is raised.
      */}
      {(job.modelUrl || job.downloadUrl) && (
        <div className="flex gap-2 flex-wrap items-start">
          {/*
            A `<Link>` wearing the button's classes, not a `<Button component={…}>`: this
            navigates, so the element has to be an anchor for middle-click, copy-link and the
            browser's own focus order. `buttonVariants` exists for exactly this.
          */}
          {job.modelUrl && (
            <RouterLink
              to={`/projects/${projectId}/reports/jobs/${job.jobId}`}
              className={buttonVariants({ size: 'sm', variant: 'primary' })}
              data-testid={`${TestId}.View`}
            >
              <Eye aria-hidden className="size-3.5" />
              View report
            </RouterLink>
          )}
          <DownloadReportButton job={job} testId={`${TestId}.Download`} />
        </div>
      )}

      {running && (
        <span className="m-0 text-xs text-ink-2" data-testid={`${TestId}.Waiting`}>
          {/*
            ~~"Leaving this page loses the link: the report is still made, but nothing in this
            version can list it again."~~ — true until §227 gave the server a job list, and the
            single most important sentence on this screen to have removed with it. Copy that
            describes a gap outlives the gap unless it is deleted in the same change.
          */}
          Generated in the background — this updates itself, and it stays in this list when you
          come back.
        </span>
      )}
    </div>
  );
}
