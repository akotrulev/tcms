/**
 * How a command prints.
 *
 * Two modes, and the split is the surface spec's §6: human-readable is the default, and
 * `--json` prints **the generated client's response verbatim** so the CLI composes in a shell.
 * Verbatim is the load-bearing word — a `--json` that reshaped the response would be a second
 * schema, undocumented, that `jq` scripts would come to depend on and `openapi.yaml` would know
 * nothing about.
 *
 * Everything is written through an injectable sink rather than to `console.log` directly, so the
 * command tests assert on output instead of on mocks of the standard library.
 */
export interface Output {
  line(text?: string): void;
  json(value: unknown): void;
  /** Diagnostics and refusals. Goes to stderr so `--json | jq` is never poisoned by them. */
  error(text: string): void;
}

export function consoleOutput(): Output {
  return {
    line: (text = '') => process.stdout.write(`${text}\n`),
    json: (value) => process.stdout.write(`${JSON.stringify(value, null, 2)}\n`),
    error: (text) => process.stderr.write(`${text}\n`),
  };
}

export interface Column<T> {
  header: string;
  value: (row: T) => string;
}

/**
 * A left-aligned table sized to its content.
 *
 * Padded rather than tab-separated: a tab renders at a width the terminal chooses, so a column
 * of keys and a column of titles stop lining up the moment one key is longer than seven
 * characters — which `TC-1` and `TC-1000` guarantee within a week of use.
 */
export function table<T>(out: Output, rows: readonly T[], columns: readonly Column<T>[]): void {
  if (rows.length === 0) {
    out.line('(none)');
    return;
  }

  const cells = rows.map((row) => columns.map((column) => column.value(row)));
  const widths = columns.map((column, index) =>
    Math.max(column.header.length, ...cells.map((cell) => cell[index]?.length ?? 0)),
  );

  const render = (values: readonly string[]) =>
    values
      .map((value, index) => (index === values.length - 1 ? value : value.padEnd(widths[index] ?? 0)))
      .join('  ')
      .trimEnd();

  out.line(render(columns.map((column) => column.header)));
  out.line(render(widths.map((width) => '-'.repeat(width))));
  for (const cell of cells) out.line(render(cell));
}

/** A `label: value` block, for a detail view. */
export function fields(out: Output, entries: readonly (readonly [string, string])[]): void {
  const width = Math.max(...entries.map(([label]) => label.length));
  for (const [label, value] of entries) out.line(`${`${label}:`.padEnd(width + 1)} ${value}`);
}

/**
 * A value a column can print.
 *
 * `null` and `undefined` become an em-dash rather than the strings "null" and "undefined", which
 * is not cosmetic: a column reading `undefined` is indistinguishable from a field whose value is
 * the literal text, and the first is a bug in this client while the second is data.
 */
export function show(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

/**
 * Who said it — the display name, the source system's own string, or an em-dash.
 *
 * Three states rather than two, and all three are real. A comment's `author` is null when
 * `issue_comment.created_by` names an account that no longer resolves — naming anybody would
 * attribute what they did not say — and when the comment was imported off a tracker whose author
 * matched nobody here, where plan 014 D-j keeps the source's own string on `externalAuthor` as
 * text rather than reassigning the comment or prefixing "Originally by …" onto the body.
 *
 * Here rather than at the two call sites, because a second copy of this is how the read command
 * and the write command come to print an offboarded author differently.
 */
export function showAuthor(comment: {
  author?: { displayName: string } | null;
  externalAuthor?: string | null;
}): string {
  return show(comment.author?.displayName ?? comment.externalAuthor);
}

/** An ISO instant as a date a person reads. Kept UTC — a run's timestamps are compared, not felt. */
export function showDate(value: unknown): string {
  if (typeof value !== 'string') return show(value);
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) return value;
  return new Date(parsed).toISOString().replace('T', ' ').slice(0, 16);
}
