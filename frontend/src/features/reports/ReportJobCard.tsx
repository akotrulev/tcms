import { Alert, Box, Button, Chip, CircularProgress, Stack, Typography } from '@mui/material';
import DownloadOutlinedIcon from '@mui/icons-material/DownloadOutlined';
import { useState } from 'react';
import { fetchArtifact } from '../../api/client';
import { isJobFinished, useReportJob } from '../../api/queries';
import type { ReportJob } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { describeError } from '../../lib/describeError';
import { reportFilename, saveBlob } from '../../lib/download';
import {
  humanize,
  reportFormatLabels,
  reportJobStatusColors,
  reportJobStatusLabels,
} from '../../lib/labels';

/**
 * One generation request, watched to its end.
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

export function ReportJobCard({ projectId, jobId }: { projectId: string; jobId: string }) {
  const job = useReportJob(projectId, jobId);

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
      <DataRegion
        testId={`${TestId}.Region`}
        state={{ ...job, refetch: () => void job.refetch() }}
        skeleton={<Typography variant="body2">Requesting…</Typography>}
      >
        {(row) => <JobBody job={row} />}
      </DataRegion>
    </Box>
  );
}

function JobBody({ job }: { job: ReportJob }) {
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  const running = !isJobFinished(job.status);
  // Narrowed into a local rather than asserted at the call site: `downloadUrl` is nullable, and a
  // cast there would survive the day the field stops being sent at all.
  const downloadUrl = job.downloadUrl;

  const download = async (url: string) => {
    setDownloadError(null);
    setDownloading(true);
    try {
      const { blob, filename } = await fetchArtifact(url);
      // The response's own name when it sent one — it is the server that knows the artifact id.
      // The fallback is composed from the job, never from the blob's media type: a deployment that
      // adds a renderer serves a type this client has never heard of.
      saveBlob(blob, filename ?? reportFilename(job.type, job.format));
    } catch (error) {
      // Handed back in place rather than raised as a notification: this failure is about the
      // button that was just pressed, and the row is still on screen to press again.
      setDownloadError(describeError(error).body);
    } finally {
      setDownloading(false);
    }
  };

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

      {downloadError && (
        <Alert severity="error" role="alert" data-testid={`${TestId}.DownloadError`}>
          {downloadError}
        </Alert>
      )}

      {/*
        Keyed on the URL, never on the status — see the header. `report:export` is not gated here:
        the caller already held it to reach the 202 that made this card, and a control disabled
        for the person who created the thing it downloads teaches the wrong rule.
      */}
      {downloadUrl && (
        <Box>
          <Button
            size="small"
            variant="outlined"
            startIcon={<DownloadOutlinedIcon />}
            disabled={downloading}
            onClick={() => void download(downloadUrl)}
            data-testid={`${TestId}.Download`}
          >
            {downloading ? 'Downloading…' : `Download ${formatLabel(job.format)}`}
          </Button>
        </Box>
      )}

      {running && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid={`${TestId}.Waiting`}>
          Generated in the background — this updates itself. Leaving this page loses the link:
          the report is still made, but nothing in this version can list it again.
        </Typography>
      )}
    </Stack>
  );
}
