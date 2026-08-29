import { Download } from 'lucide-react';
import { Banner } from '../../components/kit';
import { Button } from '../../components/ui';
import { useState } from 'react';
import { fetchArtifact } from '../../api/client';
import type { ReportJob } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { reportFilename, saveBlob } from '../../lib/download';
import { reportFormatLabels } from '../../lib/labels';

/**
 * The one place a report becomes a file on somebody's disk.
 *
 * Extracted from `ReportJobCard` in F2, when the on-screen view became a second place a report can
 * be downloaded from. `ui-rules` §8 is the reason it is an extraction rather than a copy: two
 * implementations of a download would be two implementations of the *authenticated* download — the
 * transport, the filename fallback, the busy guard and the in-place failure — and the two would
 * agree until one of them was changed.
 *
 * ## Everything hard about this is in `fetchArtifact` and `saveBlob`, and that is deliberate
 *
 * Neither obvious spelling works in this app and both fail silently: an `<a href>` sends no
 * credential (the session is a bearer token) and answers 401 while rendering as a working link, and
 * `api.get` would double the `/api/v1` prefix the server already composed into the URL and then
 * parse a Markdown report as JSON. §89's finding; `api/client.ts` carries the full account.
 *
 * ## The button keys on the URL, never on the status
 *
 * `downloadUrl` is null for the whole of PENDING and RUNNING and null for ever on a FAILED job.
 * Status and URL agree today, and the one that cannot produce a broken link is the URL.
 *
 * ## It is not permission-gated, and the refusal it can meet is handed back in place
 *
 * The caller held `report:export` to reach the 202 that made this job. A caller who somehow has not
 * gets the server's 403, rendered here beside the button that raised it rather than as a corner
 * toast — the row is still on screen to press again, and this failure is about that press.
 */

const formatLabel = (format: string): string =>
  reportFormatLabels[format as keyof typeof reportFormatLabels] ?? format;

export function DownloadReportButton({
  job,
  testId,
}: {
  job: ReportJob;
  /** Locator root; the control renders as `<testId>` and its failure as `<testId>.Error`. */
  testId: string;
}) {
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  const url = job.downloadUrl;
  if (!url) return null;

  const download = async () => {
    setError(null);
    setDownloading(true);
    try {
      const { blob, filename } = await fetchArtifact(url);
      // The response's own name when it sent one — it is the server that knows the artifact id.
      // The fallback is composed from the job, never from the blob's media type: a deployment that
      // adds a renderer serves a type this client has never heard of.
      saveBlob(blob, filename ?? reportFilename(job.type, job.format));
    } catch (failure) {
      setError(describeError(failure).body);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="flex flex-col items-start gap-2">
      {error && (
        <div data-testid={`${testId}.Error`}>
          <Banner tone="bad">
            <span role="alert">{error}</span>
          </Banner>
        </div>
      )}
      <div>
        <Button
          size="sm"
          variant="ghost"
          disabled={downloading}
          onClick={() => void download()}
          data-testid={testId}
        >
          <Download aria-hidden className="size-3.5" />
          {downloading ? 'Downloading…' : `Download ${formatLabel(job.format)}`}
        </Button>
      </div>
    </div>
  );
}
