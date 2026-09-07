/**
 * TCMS spec markdown — the format a specification is written in, and both directions of it.
 *
 * `docs/specs/2026-09-07-agent-specification-formatter.md` §2 decides the grammar; this is the
 * whole of its mechanical half. A model writes Markdown natively and a person reviews it in a
 * diff; a `DocBlockInput[]` is neither, and before this module every page an agent wrote was
 * hand-assembled — `level` required on a heading and refused on a paragraph, `rows` on a table and
 * nowhere else — and read back as JSON.
 *
 * ## One construct to one block type, and nothing silently dropped
 *
 * The page stores **plain text per block** and there are six block types, so the format is exactly
 * the Markdown subset that maps onto them. Anything else — a blockquote, HTML, a thematic break —
 * is **refused, naming the line**, on `DocBlockInput`'s own rule: a client sending `rows` on a
 * paragraph believes it is sending something else, and so does an author whose blockquote
 * disappeared. Inline emphasis is the one exception and it passes through *as literal text with a
 * warning*, because that is what the page renders.
 *
 * ## Why this is here and not behind an endpoint
 *
 * ~~`POST …/doc-pages/parse-markdown`, a twin of `parse-gherkin`.~~ → the consumers are the two
 * adapters, which share their core by design (`docs/specs/2026-08-08-mcp-tool-and-command-surface.md`
 * §1), the mapping is pure, and a round-trip property pins it. If the SPA ever wants
 * paste-Markdown on the page editor the endpoint can be added then, with this file as its
 * reference implementation.
 *
 * ## The anchor is the whole reason the round trip is safe
 *
 * A block keeps its id however much its text changes, and links target the id. So a page read back
 * as Markdown carries `{#<uuid>}` on every block, an edit that keeps the anchor keeps the block,
 * and a **label** — `{#some-name}` — marks a new block the author wants to refer to later, which
 * the write's response resolves to an id through {@link anchorsFor}. The UUID test runs first and
 * a label may not take that shape: the label grammar is otherwise a subset of what a UUID matches,
 * so the order of the two tests is the rule rather than an implementation detail.
 */
import { TcmsClientError } from '../errors.js';
import type { components } from '../schema.js';

type Schemas = components['schemas'];

/** Refuses a source line this format has no block for, or an anchor it cannot read. */
export class MarkdownError extends TcmsClientError {
  /** 1-based, or 0 for a refusal about the document as a whole. */
  readonly line: number;

  constructor(message: string, line: number) {
    super(line > 0 ? `Line ${line}: ${message}` : message);
    this.name = 'MarkdownError';
    this.line = line;
  }
}

export interface MarkdownOptions {
  /**
   * A create has no blocks to keep, so a `{#<uuid>}` anchor on one is an id belonging to no block
   * — a `400` from the server and a refusal here — and an image cannot be written at all, because
   * a page has no attachments until it exists.
   */
  readonly mode: 'create' | 'edit';
  /** The page as it stands, which is what makes the linked-block check possible. */
  readonly current?: readonly Schemas['DocBlock'][];
  /** Consent to void the links on a block this text drops. */
  readonly dropLinked?: boolean;
}

export interface MarkdownPage {
  readonly blocks: Schemas['DocBlockInput'][];
  /** Label → the index of its block, which is the index its id arrives at. */
  readonly labels: Map<string, number>;
  /** Everything carried differently from how it was written, for the ingestion report. */
  readonly warnings: string[];
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LABEL = /^[a-z0-9][a-z0-9-]*$/;
const ANCHOR = /\s*\{#([^}]*)\}$/;
const ATTACHMENT_IMAGE = /^!\[([^\]]*)\]\(attachment:([^)]+)\)$/;

const MAX_ROWS = 100;
const MAX_COLUMNS = 12;
const MAX_CELL = 500;
const MAX_LEVEL = 6;

/**
 * Inline Markdown the page stores and renders literally.
 *
 * Warned about rather than refused, and rather than stripped: stripping would change the author's
 * words, and refusing would make a sentence containing an asterisk unwritable. What the author
 * needs to know is that `**must**` reaches the reader with the asterisks on it.
 */
const INLINE = /\*\*[^*\n]+\*\*|__[^_\n]+__|`[^`\n]+`|~~[^~\n]+~~|\[[^\]\n]+\]\([^)\n]+\)/;

interface Anchor {
  readonly text: string;
  readonly id?: string;
  readonly label?: string;
}

function anchorOf(text: string, line: number, mode: 'create' | 'edit'): Anchor {
  const found = ANCHOR.exec(text);
  if (!found) return { text };

  const value = found[1]!;
  const stripped = text.slice(0, found.index);

  if (UUID.test(value)) {
    if (mode === 'create') {
      throw new MarkdownError(
        `\`{#${value}}\` is a block id, and a create has no blocks — the page does not exist yet, `
          + 'so the server would refuse an id belonging to no block. Use a label, or edit an '
          + 'existing page.',
        line,
      );
    }
    return { text: stripped, id: value };
  }

  if (!LABEL.test(value)) {
    throw new MarkdownError(
      `'${value}' is neither a block id nor a label. A label is lower-case letters, digits and `
        + 'hyphens, starting with a letter or a digit; a block id is a UUID.',
      line,
    );
  }
  return { text: stripped, label: value };
}

/**
 * Turns TCMS spec markdown into the array `DocPageInput.blocks` takes.
 *
 * @throws MarkdownError naming the line, for anything the format cannot carry
 */
export function blocksFromMarkdown(text: string, options: MarkdownOptions): MarkdownPage {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const blocks: Schemas['DocBlockInput'][] = [];
  const labels = new Map<string, number>();
  const warnings: string[] = [];
  const ids = new Set<string>();

  const add = (block: Schemas['DocBlockInput'], anchor: Anchor, line: number): void => {
    if (anchor.id !== undefined) {
      if (ids.has(anchor.id)) {
        throw new MarkdownError(`Block id ${anchor.id} is used twice. A block has one place.`, line);
      }
      ids.add(anchor.id);
    }
    if (anchor.label !== undefined) {
      if (labels.has(anchor.label)) {
        throw new MarkdownError(
          `Label '${anchor.label}' is used twice. A label is what the next step writes a `
            + 'proposal against, so it names one block.',
          line,
        );
      }
      labels.set(anchor.label, blocks.length);
      if (block.blockType === 'heading') {
        warnings.push(
          `Line ${line}: the heading '${block.text}' carries a label. A case verifying a heading `
            + 'verifies nothing — label the statement, not the section it sits in.',
        );
      }
    }
    if (block.blockType !== 'code' && INLINE.test(block.text)) {
      warnings.push(
        `Line ${line}: inline Markdown is stored and rendered as typed — '**must**' reaches the `
          + 'reader with the asterisks on it.',
      );
    }
    blocks.push(block);
  };

  let index = 0;
  while (index < lines.length) {
    const raw = lines[index]!;
    const line = index + 1;

    if (raw.trim() === '') {
      index += 1;
      continue;
    }

    refuseUnmappable(raw, line);

    const fence = /^```(.*)$/.exec(raw);
    if (fence) {
      const anchor = anchorOf(fence[1]!.trim(), line, options.mode);
      const body: string[] = [];
      index += 1;
      while (index < lines.length && !/^```\s*$/.test(lines[index]!)) {
        body.push(lines[index]!);
        index += 1;
      }
      if (index >= lines.length) {
        throw new MarkdownError('A code fence is never closed.', line);
      }
      index += 1;
      add({ blockType: 'code', text: body.join('\n'), ...idOf(anchor) }, anchor, line);
      continue;
    }

    const heading = /^(#{1,6})\s+(.+)$/.exec(raw);
    if (heading) {
      const anchor = anchorOf(heading[2]!.trim(), line, options.mode);
      add(
        { blockType: 'heading', level: heading[1]!.length, text: anchor.text, ...idOf(anchor) },
        anchor,
        line,
      );
      index += 1;
      continue;
    }

    const caption = /^Table:\s*(.+)$/.exec(raw);
    if (caption) {
      const anchor = anchorOf(caption[1]!.trim(), line, options.mode);
      if (anchor.text === '') {
        throw new MarkdownError(
          'A table caption may not be empty — it is the statement the coverage matrix reads.',
          line,
        );
      }
      const table = readTable(lines, index + 1, line);
      add(
        { blockType: 'table', text: anchor.text, rows: table.rows, ...idOf(anchor) },
        anchor,
        line,
      );
      index = table.next;
      continue;
    }

    if (/^\s*\|/.test(raw)) {
      throw new MarkdownError(
        'A table needs a `Table: <caption>` line immediately above it. The caption is the '
          + 'statement a case links to; the cells are data.',
        line,
      );
    }

    const item = /^( *)(?:[-*]|\d+[.)])\s+(.+)$/.exec(raw);
    if (item) {
      const indent = item[1]!.length;
      if (indent % 2 !== 0) {
        throw new MarkdownError(
          `A list is nested two spaces at a time; this item is indented ${indent}.`,
          line,
        );
      }
      const level = indent / 2 + 1;
      if (level > MAX_LEVEL) {
        throw new MarkdownError(`A list nests at most ${MAX_LEVEL} deep; this item is at ${level}.`, line);
      }
      const anchor = anchorOf(item[2]!.trim(), line, options.mode);
      add({ blockType: 'list', level, text: anchor.text, ...idOf(anchor) }, anchor, line);
      index += 1;
      continue;
    }

    if (/^ +\S/.test(raw)) {
      throw new MarkdownError(
        'Indented text that is not a list item. A list item is one line and a paragraph starts '
          + 'at column 1 — an indented block has no block type to become.',
        line,
      );
    }

    const paragraph: string[] = [raw.trimEnd()];
    index += 1;
    while (index < lines.length && !startsSomethingElse(lines[index]!)) {
      refuseUnmappable(lines[index]!, index + 1);
      paragraph.push(lines[index]!.trimEnd());
      index += 1;
    }

    const anchor = anchorOf(paragraph.join('\n'), line, options.mode);
    const image = ATTACHMENT_IMAGE.exec(anchor.text);
    if (image) {
      addImage(add, warnings, anchor, image[1]!, image[2]!, line, options.mode);
      continue;
    }
    add({ blockType: 'paragraph', text: anchor.text, ...idOf(anchor) }, anchor, line);
  }

  refuseLostLinks(blocks, options, warnings);
  return { blocks, labels, warnings };
}

function idOf(anchor: Anchor): { id?: string } {
  return anchor.id === undefined ? {} : { id: anchor.id };
}

/**
 * An image is **edit-only**, and a create writes the alt text as a paragraph rather than refusing.
 *
 * A page has no attachments until it exists, so a create carrying one could only ever be a `400`
 * about a file this page does not hold. Refusing would send the author back to remove a figure
 * they will want in the next save; dropping it silently is what §2.4.8 exists to forbid. So the
 * words survive as `Figure: <alt>` and the report says the file did not come with them.
 */
function addImage(
  add: (block: Schemas['DocBlockInput'], anchor: Anchor, line: number) => void,
  warnings: string[],
  anchor: Anchor,
  alt: string,
  attachmentId: string,
  line: number,
  mode: 'create' | 'edit',
): void {
  if (alt.trim() === '') {
    throw new MarkdownError(
      'An image needs alt text — it is what a reader who cannot see it gets, and a figure in a '
        + 'specification is never decorative.',
      line,
    );
  }

  if (mode === 'create') {
    warnings.push(
      `Line ${line}: the image '${alt}' was written as the paragraph 'Figure: ${alt}'. A page has `
        + 'no attachments until it exists — attach the file, then reference it in an edit.',
    );
    add({ blockType: 'paragraph', text: `Figure: ${alt}`, ...idOf(anchor) }, anchor, line);
    return;
  }

  if (!UUID.test(attachmentId)) {
    throw new MarkdownError(
      `'${attachmentId}' is not an attachment id. An image names a file already on this page, by `
        + 'the UUID `tcms_open_doc_page` serves in `attachments`.',
      line,
    );
  }
  add({ blockType: 'image', text: alt, attachmentId, ...idOf(anchor) }, anchor, line);
}

function startsSomethingElse(raw: string): boolean {
  return raw.trim() === ''
    || /^#{1,6}\s/.test(raw)
    || raw.startsWith('```')
    || /^Table:\s/.test(raw)
    || /^\s*\|/.test(raw)
    || /^ *(?:[-*]|\d+[.)])\s/.test(raw);
}

/**
 * The constructs this format has no block for.
 *
 * Each one is a thing the author meant: a blockquote is emphasis a page renders as text, raw HTML
 * is markup nothing here parses, and a rule or a setext underline is structure a heading already
 * spells. Carrying any of them as a paragraph would put the marker characters into the stored
 * text and call it a translation.
 */
function refuseUnmappable(raw: string, line: number): void {
  if (/^\s*>/.test(raw)) {
    throw new MarkdownError(
      'A blockquote has no block type. Write it as a paragraph, or as a `code` fence if it is a '
        + 'quotation to be reproduced exactly.',
      line,
    );
  }
  if (/^\s*<[a-z/!]/i.test(raw)) {
    throw new MarkdownError('HTML has no block type. Write the words.', line);
  }
  if (/^\s*(?:-{3,}|\*{3,}|_{3,}|={3,})\s*$/.test(raw)) {
    throw new MarkdownError(
      'A thematic break or a setext underline has no block type — structure is spelled with `#` '
        + 'headings, which is what a reader comparing this page to its source looks for.',
      line,
    );
  }
}

interface Table {
  readonly rows: string[][];
  readonly next: number;
}

/**
 * The GFM table under a caption, with the separator row dropped and the contract's bounds checked
 * here rather than discovered as a `400`.
 *
 * The separator is **required by the grammar** and is not data: it is what makes the block a table
 * rather than four paragraphs beginning with a pipe, and `DocBlock.rows` says the first row is the
 * header always, so there is nothing for the alignment markers to say.
 */
function readTable(lines: readonly string[], start: number, captionLine: number): Table {
  const rows: string[][] = [];
  let index = start;

  while (index < lines.length && /^\s*\|/.test(lines[index]!)) {
    rows.push(cellsOf(lines[index]!));
    index += 1;
  }

  if (rows.length === 0) {
    throw new MarkdownError(
      'A `Table:` caption must be followed immediately by the table, with no blank line between.',
      captionLine,
    );
  }
  if (rows.length < 2 || !rows[1]!.every((cell) => /^:?-{1,}:?$/.test(cell.trim()))) {
    throw new MarkdownError(
      'A table needs its `|---|` separator row under the header. Without it this is a paragraph '
        + 'that happens to contain pipes.',
      captionLine + 1,
    );
  }

  const body = [rows[0]!, ...rows.slice(2)];
  const width = body[0]!.length;

  if (body.length > MAX_ROWS) {
    throw new MarkdownError(
      `${body.length} rows; a table holds at most ${MAX_ROWS}. A longer one is a data extract `
        + 'rather than a specification.',
      captionLine,
    );
  }
  if (width > MAX_COLUMNS) {
    throw new MarkdownError(
      `${width} columns; a table holds at most ${MAX_COLUMNS}.`,
      captionLine,
    );
  }
  for (const [offset, row] of body.entries()) {
    if (row.length !== width) {
      throw new MarkdownError(
        `This row has ${row.length} cells and the header has ${width}. A ragged table is refused `
          + 'rather than padded, because a padded one cannot say which cell was missing.',
        captionLine + (offset === 0 ? 1 : offset + 2),
      );
    }
    for (const cell of row) {
      if (cell.length > MAX_CELL) {
        throw new MarkdownError(
          `A cell is ${cell.length} characters and the limit is ${MAX_CELL}. A cell longer than a `
            + 'sentence is a paragraph that should be its own block, so a case can link to it.',
          captionLine + (offset === 0 ? 1 : offset + 2),
        );
      }
    }
  }

  return { rows: body, next: index };
}

function cellsOf(raw: string): string[] {
  const trimmed = raw.trim().replace(/^\|/, '').replace(/\|$/, '');
  const cells: string[] = [];
  let current = '';

  for (let at = 0; at < trimmed.length; at += 1) {
    const character = trimmed[at]!;
    if (character === '\\' && trimmed[at + 1] === '|') {
      current += '|';
      at += 1;
      continue;
    }
    if (character === '|') {
      cells.push(current.trim());
      current = '';
      continue;
    }
    current += character;
  }
  cells.push(current.trim());
  return cells;
}

/**
 * The consent gate for an edit that would void a link, placed where the loss is computable.
 *
 * `tcms_write_doc_page`'s `blocks` is a rewrite: a block omitted from the array is deleted and its
 * links go with it. That trap is documented on the tool and is still a trap, because the medium a
 * model edits in is text and the medium the tool takes is an id-carrying array. Here the two are
 * the same medium, so the question can be asked in full — this block, these cases — instead of as
 * a generic prompt about a whole-page write.
 */
function refuseLostLinks(
  blocks: readonly Schemas['DocBlockInput'][],
  options: MarkdownOptions,
  warnings: string[],
): void {
  if (options.mode !== 'edit' || !options.current) return;

  const kept = new Set(blocks.map((block) => block.id).filter((id): id is string => Boolean(id)));
  const lost = options.current.filter(
    (block) => block.links.length > 0 && !kept.has(block.id),
  );
  if (lost.length === 0) return;

  const described = lost.map((block) => {
    const claimants = block.links.map((link) => link.sourceKey).join(', ');
    return `"${excerpt(block.text)}" (${claimants})`;
  }).join('; ');

  if (options.dropLinked === true) {
    warnings.push(
      `${lost.length} linked block(s) were dropped and their links deleted with them: ${described}.`,
    );
    return;
  }

  throw new MarkdownError(
    `This edit drops ${lost.length} block(s) that cases claim to verify, which deletes those `
      + `claims: ${described}. Put the block back with its \`{#<id>}\` anchor, or repeat the call `
      + 'with dropLinked and say in the report why the claim no longer holds.',
    0,
  );
}

function excerpt(text: string): string {
  const oneLine = text.replace(/\s+/g, ' ').trim();
  return oneLine.length <= 60 ? oneLine : `${oneLine.slice(0, 57)}…`;
}

/**
 * The page as TCMS spec markdown, with every block carrying its id.
 *
 * The ids are the point: this is what an edit starts from, so a block whose text a model rewrites
 * keeps the anchor and therefore keeps its links. `blocksFromMarkdown(markdownFromBlocks(page),
 * {mode: 'edit', current: page})` reproduces every block's type, text, level, rows and id, and
 * `markdown.test.ts` asserts exactly that over a corpus including both worked examples in
 * `docs/specs/assets/spec-formatter/`.
 *
 * @throws MarkdownError for a page this format cannot write — see {@link unwritable}
 */
export function markdownFromBlocks(blocks: readonly Schemas['DocBlock'][]): string {
  const parts: string[] = [];

  for (const [index, block] of blocks.entries()) {
    unwritable(block);
    const anchor = `{#${block.id}}`;
    const previous = blocks[index - 1];
    const separator = index === 0
      ? ''
      : (block.blockType === 'list' && previous?.blockType === 'list' ? '\n' : '\n\n');

    parts.push(separator + rendered(block, anchor));
  }

  return `${parts.join('')}\n`;
}

function rendered(block: Schemas['DocBlock'], anchor: string): string {
  switch (block.blockType) {
    case 'heading':
      return `${'#'.repeat(block.level ?? 1)} ${block.text} ${anchor}`;
    case 'list':
      return `${'  '.repeat((block.level ?? 1) - 1)}- ${block.text} ${anchor}`;
    case 'code':
      return `\`\`\` ${anchor}\n${block.text}\n\`\`\``;
    case 'table':
      return [`Table: ${block.text} ${anchor}`, ...tableLines(block.rows ?? [])].join('\n');
    case 'image':
      return `![${block.text}](attachment:${block.attachmentId ?? ''}) ${anchor}`;
    default:
      return `${block.text} ${anchor}`;
  }
}

function tableLines(rows: readonly (readonly string[])[]): string[] {
  const header = rows[0] ?? [];
  const row = (cells: readonly string[]): string =>
    `| ${cells.map((cell) => cell.replace(/\|/g, '\\|')).join(' | ')} |`;

  return [
    row(header),
    `|${header.map(() => '---').join('|')}|`,
    ...rows.slice(1).map(row),
  ];
}

/**
 * The one page shape this format cannot write down, refused loudly rather than emitted broken.
 *
 * A block's text is one paragraph, one heading, one list item — the page editor writes it that way
 * and this format reads it that way. A stored text carrying a blank line would come back as two
 * blocks, the second with no id, which is a **silent** split of a linked statement: the id survives
 * on the first half, so the linked-block check sees nothing to refuse. Naming it here is the only
 * place that failure is visible.
 */
function unwritable(block: Schemas['DocBlock']): void {
  const oneLineOnly = block.blockType !== 'paragraph' && block.blockType !== 'code';

  if (oneLineOnly && block.text.includes('\n')) {
    throw new MarkdownError(
      `Block ${block.id} is a ${block.blockType} whose text spans lines, which this format writes `
        + 'on one. Edit it through `blocks` instead.',
      0,
    );
  }
  if (block.blockType === 'code' && /^```/m.test(block.text)) {
    throw new MarkdownError(
      `Block ${block.id} is a code block containing a fence, which would close the fence this `
        + 'format writes it in. Edit it through `blocks` instead.',
      0,
    );
  }
  if (block.blockType === 'table' && (block.rows ?? []).some((row) => row.some((cell) => cell.includes('\n')))) {
    throw new MarkdownError(
      `Block ${block.id} is a table with a cell spanning lines, which a pipe row cannot carry.`,
      0,
    );
  }
  if (block.blockType === 'paragraph' && /\n\s*\n/.test(block.text)) {
    throw new MarkdownError(
      `Block ${block.id} is a paragraph containing a blank line, which would read back as two `
        + 'blocks and silently split a statement a case may claim. Edit it through `blocks` '
        + 'instead, or split it deliberately.',
      0,
    );
  }
}

/**
 * The labels a write asked for, resolved against the page it answered with.
 *
 * By **index**, because the response is the page in array order and that is the order the blocks
 * were sent in — the same property `DocPageInput.blocks` states when it says `position` is not
 * sent. A label is never stored: it is a file-local convenience resolved to an id at exactly this
 * moment, which is what keeps a block's identity one permanent id rather than two.
 */
export function anchorsFor(
  labels: ReadonlyMap<string, number>,
  page: { readonly blocks: readonly { readonly id: string }[] },
): Record<string, string> {
  const anchors: Record<string, string> = {};

  for (const [label, index] of labels) {
    const block = page.blocks[index];
    if (!block) {
      throw new TcmsClientError(
        `The page answered with ${page.blocks.length} blocks and '${label}' was written against `
          + `block ${index + 1}. The labels cannot be resolved; read the page and match them by `
          + 'text before writing anything against them.',
      );
    }
    anchors[label] = block.id;
  }

  return anchors;
}
