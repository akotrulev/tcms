import { Alert, Box, Button, Stack } from '@mui/material';
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined';
import { useState } from 'react';
import { fetchArtifact } from '../../api/client';
import type { IngestionJob } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { saveBlob } from '../../lib/download';

/**
 * The verbatim file CI posted, fetched as a file.
 *
 * ## Why this is not an `<a href>`, and not `api.get`
 *
 * `DownloadReportButton`'s two reasons exactly, and both fail silently. The session is a bearer
 * token — `SecurityConfiguration` is `STATELESS` with no cookie anywhere — so a plain anchor at
 * `/api/v1/…` sends no credential and answers **401** while rendering as a working link. And
 * `reportUrl` already carries `/api/v1` because the *server* composed it, so `api.get` would produce
 * `/api/v1/api/v1/…` and then try to parse XML as JSON. `api/client.ts` carries the full account.
 *
 * ## It keys on the URL, never on the format or the status
 *
 * `reportUrl` is null for every job posted before uploads were kept, and no migration can change
 * that. Keying on anything else — a status of `COMPLETED`, a known format — would render a button
 * that 404s on every historical row. This is `DownloadReportButton`'s rule and it is load-bearing
 * for the same reason: the field that cannot produce a broken link is the URL.
 *
 * ## It is a sibling of `DownloadReportButton` rather than a reuse of it
 *
 * The two look alike and are not the same control. That one takes a `ReportJob` and names a
 * *format* a person chose; this one takes an ingestion job and names a *dialect* CI chose, falls
 * back to a different filename, and is the only download in the app whose bytes the product did not
 * produce. Merging them would mean a component with two shapes of input and a branch for which — and
 * `ui-rules` §8 is about not writing the *authenticated download* twice, which is `fetchArtifact`
 * and `saveBlob`, both of which this uses.
 */
export function DownloadRawReportButton({
  job,
  testId,
}: {
  job: IngestionJob;
  /** Locator root; the control renders as `<testId>` and its failure as `<testId>.Error`. */
  testId: string;
}) {
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  const url = job.reportUrl;
  if (!url) return null;

  const download = async () => {
    setError(null);
    setDownloading(true);
    try {
      const { blob, filename } = await fetchArtifact(url);
      // The server's own name when it sent one — it carries the dialect, which the media type
      // cannot: `application/xml` is true of both JUnit and TestNG and distinguishes neither.
      saveBlob(blob, filename ?? `ingestion-${job.id}.txt`);
    } catch (failure) {
      setError(describeError(failure).body);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Stack sx={{ gap: 1, alignItems: 'flex-start' }}>
      {error && (
        <Alert severity="error" role="alert" data-testid={`${testId}.Error`}>
          {error}
        </Alert>
      )}
      <Box>
        <Button
          size="small"
          variant="outlined"
          startIcon={<DescriptionOutlinedIcon />}
          disabled={downloading}
          onClick={() => void download()}
          data-testid={testId}
        >
          {downloading ? 'Downloading…' : 'Download the posted report'}
        </Button>
      </Box>
    </Stack>
  );
}
