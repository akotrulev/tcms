/**
 * A stored file, reached the only way this app can reach one: a **fetch**, not an anchor.
 *
 * ## Why the obvious spelling is wrong, and silently
 *
 * `Attachment.url` and `Artifact.url` are server-composed `/api/v1/…` paths, and an
 * `<a href={url} download>` pointed at one sends **no credential** — the session is a bearer token
 * this client sets on every request (`SecurityConfiguration` is `STATELESS`, no cookie anywhere).
 * The result is a **401** rendered as a working link. `api/client.ts` carries the full account and
 * names `EvidencePanel` as the place that still has the hole.
 *
 * So the control is a button that runs `fetchArtifact` — which owns the same-origin guard standing
 * between a server-supplied string and this user's token, the 60s budget, and the error mapping —
 * and hands the blob to `saveBlob`, which owns the object-URL dance and its revoke.
 *
 * ## Extracted rather than copied, at the third call site
 *
 * `DownloadReportButton` was the first and stays its own component: it keys on a `ReportJob`,
 * composes a filename from the job's type and format because the response may name none, and
 * renders as a prominent action. This is the *inline* shape — an attachment row and a register row,
 * where the file's name **is** the affordance and the fallback filename is a column the server
 * always sends. Two implementations of an authenticated download would be two implementations of
 * the transport, the busy guard and the in-place failure, and would agree until one changed.
 *
 * ## The failure renders here, beside the thing that raised it
 *
 * Not a global toast: the row is still on screen to press again and this failure is about that
 * press. It is a fragment rather than a wrapper so the caller decides the layout — a table cell and
 * a stacked list want different things, and a component that picked one would be wrong in the other.
 */
import { useState } from 'react';
import type { ReactNode } from 'react';
import { Banner } from './kit';
import { cn } from '../lib/cn';
import { fetchArtifact } from '../api/client';
import { describeError } from '../lib/describeError';
import { saveBlob } from '../lib/download';

export function FileDownloadLink({
  url,
  filename,
  children,
  testId,
  className,
}: {
  /** The **server's** path. Never composed here — a client that rebuilds it holds a second copy of
   *  the routing, and a route change then breaks the download with a 404 that reads as a missing
   *  file rather than as a missing endpoint. */
  url: string;
  /**
   * What to call the saved file when the response's `Content-Disposition` names none.
   *
   * A required prop with no default, because every caller has a better answer than this component
   * does and an invented one would be indistinguishable from a real name.
   */
  filename: string;
  /** Visible text. Defaults to the filename, which is the usual case. */
  children?: ReactNode;
  /** Locator root; the control is `<testId>` and its failure `<testId>.Error`. */
  testId: string;
  /** ~~`sx`~~ → `className` in T16; the callers that styled this link pass utilities now. */
  className?: string;
}) {
  const [downloading, setDownloading] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const download = async () => {
    setFailure(null);
    setDownloading(true);
    try {
      const result = await fetchArtifact(url);
      saveBlob(result.blob, result.filename ?? filename);
    } catch (error) {
      setFailure(describeError(error).body);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <>
      <button
        type="button"
        disabled={downloading}
        onClick={() => void download()}
        /*
          The visible text is the file's name and the accessible name CONTAINS it (WCAG 2.5.3), so
          voice control still works on the words on screen while a screen reader hears what pressing
          it does. `ExecutionRowLinks` makes the same call for its two links in one row.
        */
        aria-label={`Download ${filename}`}
        data-testid={testId}
        className={cn(
          'cursor-pointer border-0 bg-transparent p-0 text-left text-[13px] text-steel underline-offset-4 hover:underline',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
          downloading && 'cursor-default opacity-60',
          className,
        )}
      >
        {children ?? filename}
      </button>
      {failure && (
        <div className="mt-1" data-testid={`${testId}.Error`}>
          <Banner tone="bad">
            <span role="alert">{failure}</span>
          </Banner>
        </div>
      )}
    </>
  );
}
