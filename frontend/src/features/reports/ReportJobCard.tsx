import { Alert, Box, Button, Chip, CircularProgress, Stack, Typography } from '@mui/material';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import { Link as RouterLink } from 'react-router-dom';
import { isJobFinished } from '../../api/queries';
import type { ReportJob } from '../../api/types';
import { formatDateTime } from '../../lib/format';
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
    <Box
      data-testid={TestId}
      sx={{
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        p: 2,
      }}
    >
      <JobBody job={job} projectId={projectId} />
    </Box>
  );
}

function JobBody({ job, projectId }: { job: ReportJob; projectId: string }) {
  const running = !isJobFinished(job.status);

  return (
    <Stack sx={{ gap: 1 }}>
      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        {/*
          Humanized, not the wire value: the report type is an open string, so there is no map to
          derive a label from — `humanize` turns `run_summary` into "Run summary" for whatever a
          later release adds, where a lookup table would fall through to the raw token (ui-rules §1).
        */}
        <Typography variant="body2" sx={{ fontWeight: 600 }} data-testid={`${TestId}.Type`}>
          {humanize(job.type)}
        </Typography>
        <Chip
          size="small"
          variant="outlined"
          label={formatLabel(job.format)}
          data-testid={`${TestId}.Format`}
        />
        {/*
          When it was asked for. On the card rather than only in a tooltip because the list is
          ordered by it: an order a reader cannot see is one they have to take on trust, and two
          run summaries of the same project are told apart by nothing else.
        */}
        <Typography
          variant="caption"
          sx={{ color: 'text.secondary' }}
          data-testid={`${TestId}.RequestedAt`}
        >
          {formatDateTime(job.requestedAt)}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {running && <CircularProgress size={14} data-testid={`${TestId}.Spinner`} />}
        <Chip
          size="small"
          label={reportJobStatusLabels[job.status]}
          data-testid={`${TestId}.Status`}
          sx={{
            bgcolor: 'transparent',
            color: reportJobStatusColors[job.status],
            border: `1px solid color-mix(in srgb, ${reportJobStatusColors[job.status]} 45%, transparent)`,
          }}
        />
      </Stack>

      {/*
        The server's sentence, not a rephrasing of it. A report's parameters are validated inside
        the worker (a `runId` naming another project's run cannot be checked without a database
        read), so this is the only place that refusal is ever seen — §82's rule about never keying
        on a server sentence applies to *branching*, not to showing it.
      */}
      {job.status === 'FAILED' && (
        <Alert severity="error" role="alert" data-testid={`${TestId}.Failed`}>
          {job.error ?? 'The report could not be generated, and the server did not say why.'}
        </Alert>
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
        <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {job.modelUrl && (
            <Button
              component={RouterLink}
              to={`/projects/${projectId}/reports/jobs/${job.jobId}`}
              size="small"
              variant="contained"
              startIcon={<VisibilityOutlinedIcon />}
              data-testid={`${TestId}.View`}
            >
              View report
            </Button>
          )}
          <DownloadReportButton job={job} testId={`${TestId}.Download`} />
        </Stack>
      )}

      {running && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid={`${TestId}.Waiting`}>
          {/*
            ~~"Leaving this page loses the link: the report is still made, but nothing in this
            version can list it again."~~ — true until §227 gave the server a job list, and the
            single most important sentence on this screen to have removed with it. Copy that
            describes a gap outlives the gap unless it is deleted in the same change.
          */}
          Generated in the background — this updates itself, and it stays in this list when you
          come back.
        </Typography>
      )}
    </Stack>
  );
}
