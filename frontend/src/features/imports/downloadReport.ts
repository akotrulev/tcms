import type { ImportRun } from '../../api/types';
import { saveBlob } from '../../lib/download';

/**
 * The report's *Download*, rendered **client-side from the run already in the cache**.
 *
 * ## There is no server artifact, and that is a departure worth naming
 *
 * D15 says the dropped-field list is *"persisted as an `artifact`"*. It is not, and this is not an
 * omission: `artifact` is the **`repository` module's** table, and `tracker.imports` may not write
 * it — cross-module dependencies point only at the five foundation modules, and `repository` is not
 * one of them (`ArchitectureRules.FOUNDATION_MODULES`). 009's `db` line replaced that mechanism
 * with `import_run` + `import_dropped_field`, which is better for the purpose besides: an artifact
 * is a blob with a name, and `t25`'s acceptance criterion is *"how many Story Points values did we
 * drop across every import"* — a question rows answer and a stored file does not.
 *
 * So the file a person keeps is made here, out of the report they are looking at. Nothing is
 * fetched: `GET /imports/{id}` has already answered, and a download that re-asked the server would
 * be a second read of the same row for no gain.
 *
 * ## Two files, because they answer two questions
 *
 * The JSON is the whole run — every count, the applied mapping, the people, the dropped rows — and
 * it is what somebody hands to whoever asks *"what exactly did this migration do"*. The CSV is the
 * dropped fields alone, because that list is the one thing a person opens a spreadsheet for, and a
 * JSON document is not a thing a spreadsheet opens.
 */

/** `import-PROJ-2026-08-14`, filesystem-safe, from the run's own start rather than from today. */
function stem(run: ImportRun): string {
  return `import-${run.sourceProjectKey}-${run.startedAt.slice(0, 10)}`.replace(
    /[^a-zA-Z0-9._-]/g,
    '-',
  );
}

/**
 * One CSV field.
 *
 * Quoted unconditionally rather than only when it has to be. A reason sentence carries commas and
 * a source field can be called `Custom: "Squad"` — the mockup's own row — so the conditional form
 * would be a rule with two exceptions on a list of seven rows, and the doubling of an embedded
 * quote is the part that is easy to leave out either way.
 */
function csvField(value: string | number): string {
  return `"${String(value).replaceAll('"', '""')}"`;
}

/** The dropped fields as a spreadsheet, in the server's order and with its sentences. */
export function droppedFieldsCsv(run: ImportRun): string {
  const rows = [
    ['Source field', 'Type', 'Values held', 'Why'],
    ...run.droppedFields.map((field) => [
      field.name,
      field.fieldType,
      String(field.valueCount),
      field.reason,
    ]),
  ];
  /*
    CRLF, which is what RFC 4180 says and what Excel on Windows needs to keep a quoted field
    holding a newline in one cell. A bare LF is read correctly by everything else.
  */
  return rows.map((row) => row.map(csvField).join(',')).join('\r\n');
}

export function downloadReportJson(run: ImportRun): void {
  saveBlob(
    new Blob([JSON.stringify(run, null, 2)], { type: 'application/json' }),
    `${stem(run)}.json`,
  );
}

export function downloadDroppedFieldsCsv(run: ImportRun): void {
  saveBlob(
    new Blob([droppedFieldsCsv(run)], { type: 'text/csv;charset=utf-8' }),
    `${stem(run)}-dropped-fields.csv`,
  );
}
