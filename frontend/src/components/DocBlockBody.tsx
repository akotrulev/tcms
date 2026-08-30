/**
 * One documentation block, rendered — **the single renderer for all six `DocBlockType`s.**
 *
 * # Why this is a shared component and not two page-local functions
 *
 * It was two. `DocsPage` and `PlanDocumentPanel` each had a `BlockView` branching on `blockType`,
 * and **they disagreed**: the plan panel indented list bullets and set a monospace face on `code`,
 * the documentation page did neither — so the same stored block read as an outline on one screen
 * and as a run of identical paragraphs on the other, with nothing failing anywhere. Neither was
 * wrong about the data; there were simply two answers to one question, which is the thing
 * `lib/labels.ts` and `DataRegion` exist to prevent one level down.
 *
 * Four of the six types had **no branch at all** until #48: `paragraph`, `code`, `table` and
 * `image` all fell through to a plain paragraph, so a page could store a table and render it as a
 * caption and a page could store an image and render nothing.
 *
 * # What this component owns, and what the caller keeps
 *
 * This owns everything *inside* a block: the heading scale, the bullet and its indent, the
 * whitespace, the table's semantics, the picture. The caller keeps the chrome around it, because
 * that genuinely differs — the documentation page anchors each block by id for deep links and hangs
 * a coverage marker in the margin; the plan panel renders link chips underneath. A component that
 * tried to own both would grow a prop per screen.
 *
 * # The image is the reason this file needs the API at all
 *
 * A block names an `attachmentId`; the URL is the page's to resolve, from the attachment list it
 * already has. That indirection is deliberate — see `AttachmentImage` below for what a naive
 * `<img src>` does here, which is fail silently.
 */
import { useEffect, useState } from 'react';
import { Banner } from './kit';
import { Skeleton } from './ui';
import { cn } from '../lib/cn';
import { useAttachmentBlob } from '../api/queries';
import { describeError } from '../lib/describeError';
import { FileDownloadLink } from './FileDownloadLink';
import type { Attachment, DocBlock } from '../api/types';

export function DocBlockBody({
  block,
  attachments,
  testId,
}: {
  block: DocBlock;
  /**
   * The page's files — what an `image` block's `attachmentId` resolves against.
   *
   * Required rather than optional, and empty is a legitimate value. An optional prop would let a
   * caller forget it and get a page whose images all report themselves missing, which reads as a
   * data problem and is a wiring one.
   */
  attachments: Attachment[];
  /** Locator root for the parts that have one — the table, the image and its states. */
  testId: string;
}) {
  switch (block.blockType) {
    case 'heading':
      return <HeadingBody block={block} />;
    case 'list':
      return <ListBody block={block} testId={testId} />;
    case 'code':
      return <CodeBody block={block} testId={testId} />;
    case 'table':
      return <TableBody block={block} testId={testId} />;
    case 'image':
      return <ImageBody block={block} attachments={attachments} testId={testId} />;
    case 'paragraph':
    default:
      /*
        The `default` is not dead code and must not be "tidied" into exhaustiveness.

        `blockType` is a union generated from the contract, so a new member would be a compile
        error at every `case` above — but the value arriving at runtime comes from a server that may
        be a version ahead of this bundle. `BlockContent` on that side carries
        `@JsonIgnoreProperties(ignoreUnknown = true)` for the mirror-image reason. A block whose
        type this build does not know still has words in it, and showing them is better than
        showing a gap where a requirement is.
      */
      return <ProseBody text={block.text} />;
  }
}

function HeadingBody({ block }: { block: DocBlock }) {
  // Two levels of the six get their own element; the rest share `h4` and shrink by type scale.
  // Depth is real information — the outline is what a specification is made of — but six nested
  // heading elements on a page that is itself inside a route's `h1`/`h2` produces a document
  // outline that no longer describes the screen.
  const depth = block.level ?? 1;
  const element = depth === 1 ? 'h2' : depth === 2 ? 'h3' : 'h4';

  const Heading = element;
  return (
    <Heading
      className="m-0 font-semibold text-ink"
      style={{ fontSize: `${Math.max(1.05, 1.5 - (depth - 1) * 0.14)}rem` }}
    >
      {block.text}
    </Heading>
  );
}

function ListBody({ block, testId }: { block: DocBlock; testId: string }) {
  /*
    The bullet's indent, which is `level` doing a second job (§93).

    A depth of 1 is the unindented common case and is also what every list block written before the
    field existed reads back as — `level` is null there, and `?? 1` is where that becomes a bullet
    rather than a crash. Markers typed into the text ("1." / "a." / "i.") were the alternative and
    cannot be indented, renumbered, or linked to.
  */
  const depth = block.level ?? 1;

  return (
    <div
      className="flex items-start gap-2"
      style={{ paddingLeft: `${(depth - 1) * 24}px` }}
      data-testid={`${testId}.Bullet`}
    >
      {/* aria-hidden: the bullet is a visual marker, and a screen reader announcing "bullet" per
          line adds nothing the indentation does not already imply. */}
      <span className="leading-[1.6] text-ink-2" aria-hidden>
        •
      </span>
      <ProseBody text={block.text} />
    </div>
  );
}

function CodeBody({ block, testId }: { block: DocBlock; testId: string }) {
  return (
    // `whitespace-pre-wrap` and not `pre`: a long line wraps instead of pushing a horizontal
    // scrollbar onto the whole page. `overflow-x-auto` catches what cannot wrap — a URL, a base64
    // blob — and keeps that scrollbar inside this block, which is the rule the table below follows.
    <pre
      data-testid={`${testId}.Code`}
      className="m-0 overflow-x-auto rounded-sm border border-line bg-field p-3 font-mono text-[12.5px] whitespace-pre-wrap text-ink"
    >
      {/* A real <code> inside a real <pre>: the two elements together are what tells a screen
          reader this is preformatted source rather than a paragraph that happens to be monospace. */}
      <code>{block.text}</code>
    </pre>
  );
}

function TableBody({ block, testId }: { block: DocBlock; testId: string }) {
  const rows = block.rows ?? [];

  if (rows.length === 0) {
    // The server refuses an empty table, so this is a block written by something else — an older
    // build, a direct write. Stating it beats rendering a caption above nothing, which reads as a
    // table whose rows are still loading.
    return (
      <div className="flex flex-col gap-1">
        <ProseBody text={block.text} />
        <p className="m-0 text-[13px] text-ink-2" data-testid={`${testId}.Table.Empty`}>
          This table has no rows.
        </p>
      </div>
    );
  }

  const [header, ...body] = rows;

  const CELL = 'border border-line px-2 py-1.5 text-left align-top text-[13px]';

  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      {/*
        The caption is `text`, and it is a real <caption> rather than a paragraph above the table.
        A screen reader announces a caption as the table's name when the user lands on it; a
        paragraph sitting above one is a separate thing that happens to be nearby.
      */}
      <div className="max-w-full overflow-x-auto">
        <table data-testid={`${testId}.Table`} className="w-full border-collapse text-ink">
          {block.text ? (
            <caption className="caption-top pb-1 text-left text-[13px] text-ink-2">
              {block.text}
            </caption>
          ) : null}
          <thead>
            <tr>
              {header.map((cell, index) => (
                // `scope="col"` is what makes the header cell announce with every cell beneath it.
                // Without it a <th> is only bold text, and a table read column-by-column becomes a
                // list of values with nothing saying what they are.
                <th scope="col" key={index} className={cn(CELL, 'bg-field font-semibold')}>
                  {cell}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {body.map((row, rowIndex) => (
              /*
                Index keys, deliberately, and this is the case where they are correct: a cell is a
                bare string with no id, rows have no identity of their own, and the whole table is
                replaced wholesale on every save. There is no state inside a cell for a reordered
                key to attach to the wrong row.
              */
              <tr key={rowIndex}>
                {row.map((cell, cellIndex) => (
                  <td key={cellIndex} className={CELL}>
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ImageBody({
  block,
  attachments,
  testId,
}: {
  block: DocBlock;
  attachments: Attachment[];
  testId: string;
}) {
  const file = attachments.find((attachment) => attachment.id === block.attachmentId);

  if (!file) {
    /*
      Named rather than skipped, and the wording avoids blaming the reader's permissions.

      The server refuses to delete an attachment a block references, and refuses a block naming a
      file that is not on the page — so both ends are guarded and this state should be unreachable.
      Reaching it means one of those guards did not hold, and a block that silently renders nothing
      is how that stays unreported. The alt text is shown, because it is the one description of the
      picture that survives the picture.
    */
    return (
      <div data-testid={`${testId}.Image.Missing`}>
        <Banner tone="warn">
          This image&rsquo;s file is no longer attached to the page. It was described as:{' '}
          {block.text}
        </Banner>
      </div>
    );
  }

  return <AttachmentImage file={file} alt={block.text} testId={testId} />;
}

/**
 * The picture itself — fetched with the session's credential and shown from an object URL.
 *
 * **`<img src={file.url}>` is the obvious spelling and it is wrong, silently.** The session is a
 * bearer token this client sets per request, so a browser-initiated image request carries none and
 * the server answers 401 — which renders as a broken image with every gate green. `api/client.ts`
 * has carried that account since §89 for anchors; this is the `<img>` half.
 *
 * The blob is cached by TanStack Query (see `useAttachmentBlob`); the **URL** is created here and
 * revoked on unmount, because an object URL is a lifetime rather than a value and a revoked one in
 * a shared cache is a broken image on the next mount.
 */
function AttachmentImage({
  file,
  alt,
  testId,
}: {
  file: Attachment;
  alt: string;
  testId: string;
}) {
  const blob = useAttachmentBlob(file.url);
  const [objectUrl, setObjectUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!blob.data) {
      setObjectUrl(null);
      return;
    }
    const url = URL.createObjectURL(blob.data);
    setObjectUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [blob.data]);

  if (blob.isError) {
    /*
      The download stays available when the render fails, which is the whole reason this failure is
      rendered in place rather than raised as a notification: the bytes may be perfectly fine and
      unrenderable — a TIFF, a PDF somebody attached as a diagram — and offering the file is a
      better answer than an apology. `FileDownloadLink` is the same credential-carrying read.
    */
    return (
      <div data-testid={`${testId}.Image.Error`}>
        <Banner tone="warn">
          <div className="flex flex-col items-start gap-1">
            <span>
              {alt} — {describeError(blob.error).body}
            </span>
            <FileDownloadLink
              url={file.url}
              filename={file.filename}
              testId={`${testId}.Image.Download`}
            />
          </div>
        </Banner>
      </div>
    );
  }

  if (!objectUrl) {
    return (
      <Skeleton
        // `aria-busy` and a label, so the wait is announced rather than being a silent grey box —
        // and the label is the alt text, which is the only description that exists yet.
        aria-busy="true"
        aria-label={alt}
        data-testid={`${testId}.Image.Loading`}
        className="h-[180px] max-w-[640px] rounded-sm"
      />
    );
  }

  return (
    <div className="flex flex-col items-start gap-1">
      <img
        src={objectUrl}
        // Required and never empty: the server refuses a blank alt on an image block, because an
        // image in a specification is never decorative.
        alt={alt}
        data-testid={`${testId}.Image`}
        className="h-auto max-w-full rounded-sm border border-line"
      />
      {/* The alt text again, visibly. In a specification the sentence describing a diagram is part
          of the specification — a reader quoting the document needs it, and hiding it in an
          attribute means only screen-reader users ever get it. */}
      <p className="m-0 text-[13px] text-ink-2" data-testid={`${testId}.Image.Caption`}>
        {alt}
      </p>
    </div>
  );
}

/** A run of authored words, with the newlines the author typed. */
function ProseBody({ text }: { text: string }) {
  return (
    // `whitespace-pre-wrap`, so a paragraph somebody wrote across several lines reads as they
    // wrote it. The editor stores the newlines; a renderer that collapsed them would show a
    // different document from the one being edited two clicks away.
    <p className="m-0 min-w-0 flex-1 text-[14.5px] whitespace-pre-wrap text-ink">{text}</p>
  );
}
