/**
 * Handing a fetched `Blob` to the browser as a file the user keeps.
 *
 * This is the second half of an authenticated download and it is deliberately separate from the
 * first: `api/client.ts`'s `fetchArtifact` owns the transport and the credential, and this owns the
 * DOM. Keeping them apart is what lets the transport half be tested without a document, and what
 * stops a second screen from re-deriving the anchor dance below.
 *
 * ## The revoke is the part that is easy to leave out
 *
 * `URL.createObjectURL` pins the blob in memory until the URL is revoked — a report is a whole
 * rendered document, and a user who generates a dozen over an afternoon on a screen that never
 * unmounts is holding all twelve. Revoking in a `finally` rather than after the click keeps that
 * true on the paths where the click throws.
 *
 * The revoke is deferred by a tick because Safari has historically cancelled an in-flight download
 * whose object URL was revoked synchronously in the same task.
 */
export function saveBlob(blob: Blob, filename: string): void {
  const href = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = href;
    anchor.download = filename;
    // Not appended to the document: a detached anchor's `click()` starts the download in every
    // browser this app supports, and appending one would put a stray node in the page for as long
    // as the synchronous handler runs — visible to anything walking the DOM, including tests.
    anchor.click();
  } finally {
    setTimeout(() => URL.revokeObjectURL(href), 0);
  }
}

/**
 * A filesystem-safe name for a downloaded report, used when the response named none.
 *
 * The extension comes from the format the job echoed back rather than from the blob's media type:
 * a deployment that adds a renderer serves a media type this client has never heard of, and a
 * lookup table here would fall through to something wrong. The format is what the person asked
 * for, so it is what the file should be called.
 */
export function reportFilename(type: string, format: string): string {
  const stem = `${type}-${new Date().toISOString().slice(0, 10)}`.replace(/[^a-zA-Z0-9._-]/g, '-');
  return `${stem}.${format.toLowerCase()}`;
}
