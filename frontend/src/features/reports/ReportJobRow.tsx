import { buttonVariants } from '../../components/ui/buttonVariants';
import { Eye } from 'lucide-react';
import { Badge, Banner, TableCell, TableRow } from '../../components/kit';
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
 * ## A table row, not a card — changed 2026-08-30 (renders audit 1.8)
 *
 * `renders/06-analytics.html` frame F draws Reports and CI ingestion as *"two tables about jobs,
 * drawn the same way on purpose"* — Report · Kind · State · Size · Generated · Download. CI
 * ingestion **is** that table in this build; Reports was a stack of bordered panels with inline
 * buttons, so the deliberate pairing was lost and two screens taught two shapes for one idea.
 *
 * What the card had and a row must not lose is the FAILED sentence. It rides in the title cell's
 * `subtitle`, which is where `RunSchedulePage` puts a schedule's fire error for the same reason: a
 * row is one line and a refusal is a sentence, and dropping the sentence to fit the line is how a
 * failed job becomes indistinguishable from an empty one — the specific defect §85 names.
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

export function ReportJobRow({ projectId, job }: { projectId: string; job: ReportJob }) {
  const running = !isJobFinished(job.status);

  return (
    <TableRow data-testid={TestId}>
      <TableCell
        variant="title"
        subtitle={
          job.status === 'FAILED' ? (
            /*
              The server's sentence, not a rephrasing of it. A report's parameters are validated
              inside the worker (a `runId` naming another project's run cannot be checked without a
              database read), so this is the only place that refusal is ever seen — §82's rule about
              never keying on a server sentence applies to *branching*, not to showing it.
            */
            <div data-testid={`${TestId}.Failed`}>
              <Banner tone="bad">
                <span role="alert">
                  {job.error ?? 'The report could not be generated, and the server did not say why.'}
                </span>
              </Banner>
            </div>
          ) : running ? (
            <span data-testid={`${TestId}.Waiting`}>
              {/*
                ~~"Leaving this page loses the link: the report is still made, but nothing in this
                version can list it again."~~ — true until §227 gave the server a job list, and the
                single most important sentence on this screen to have removed with it. Copy that
                describes a gap outlives the gap unless it is deleted in the same change.
              */}
              Generated in the background — this updates itself, and it stays in this list when you
              come back.
            </span>
          ) : undefined
        }
      >
        {/*
          Humanized, not the wire value: the report type is an open string, so there is no map to
          derive a label from — `humanize` turns `run_summary` into "Run summary" for whatever a
          later release adds, where a lookup table would fall through to the raw token (ui-rules §1).
        */}
        <span className="font-semibold" data-testid={`${TestId}.Type`}>
          {humanize(job.type)}
        </span>
      </TableCell>

      <TableCell>
        <Badge tone="plain" data-testid={`${TestId}.Format`}>
          {formatLabel(job.format)}
        </Badge>
      </TableCell>

      <TableCell>
        <span className="inline-flex items-center gap-2">
          {running && (
            // ~~`CircularProgress`~~ → a bordered ring, T16. `motion-safe:` so it obeys the
            // reduced-motion block in `index.css`; the cell beside it already says the job is
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
        </span>
      </TableCell>

      <TableCell align="right" className="tabular-nums text-ink-3">
        {/*
          The rendered artifact's size (005 S1) — `Artifact.sizeBytes`, joined onto the job so a list
          of them can say "2.4 MB" without a call per row. Null until COMPLETED and null for ever on
          a FAILED one, on the same terms as `downloadUrl`, so the cell is empty rather than showing
          a zero that would claim an empty file.
        */}
        {job.sizeBytes !== null ? (
          <span data-testid={`${TestId}.Size`}>{formatBytes(job.sizeBytes)}</span>
        ) : null}
      </TableCell>

      <TableCell className="text-ink-3">
        {/*
          When it was asked for. A column rather than a tooltip because the list is ordered by it:
          an order a reader cannot see is one they have to take on trust, and two run summaries of
          the same project are told apart by nothing else.
        */}
        <span data-testid={`${TestId}.RequestedAt`}>{formatDateTime(job.requestedAt)}</span>
      </TableCell>

      <TableCell align="right">
        {/*
          Two affordances on a finished report and each keys on its OWN url, never on the status:
          `downloadUrl` is the format that was asked for, `modelUrl` is the stored model the screen
          renders (§90/F2). They are written in the same instant by the same worker, so today they
          appear together — and keying each on the URL it needs is what stops a deployment that
          produced only one of the two from rendering a link to `undefined`.

          Neither is permission-gated. The caller held `report:export` to reach the 202 that made
          this row, and a control disabled for the person who created the thing it opens teaches the
          wrong rule — the refusal, if it somehow comes, is rendered where it is raised.
        */}
        <span className="flex justify-end gap-2">
          {job.modelUrl && (
            /*
              A `<Link>` wearing the button's classes, not a `<Button component={…}>`: this
              navigates, so the element has to be an anchor for middle-click, copy-link and the
              browser's own focus order. `buttonVariants` exists for exactly this.
            */
            <RouterLink
              to={`/projects/${projectId}/reports/jobs/${job.jobId}`}
              className={buttonVariants({ size: 'sm', variant: 'primary' })}
              data-testid={`${TestId}.View`}
            >
              <Eye aria-hidden className="size-3.5" />
              View
            </RouterLink>
          )}
          {job.downloadUrl && <DownloadReportButton job={job} testId={`${TestId}.Download`} />}
        </span>
      </TableCell>
    </TableRow>
  );
}
