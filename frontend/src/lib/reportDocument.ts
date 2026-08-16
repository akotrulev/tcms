/**
 * The stored report model, checked at the boundary — F2's half of §6e's one-model architecture.
 *
 * ## Why this file exists at all, when every other shape in this app is generated
 *
 * `src/api/schema.d.ts` is generated from `openapi.yaml`, and nothing in this app declares a wire
 * shape by hand. This is the exception, and it is forced rather than chosen: the artifact route
 * (`GET …/reports/artifacts/{artifactId}/content`) is declared as **binary**, because it serves
 * five formats' bytes — Markdown, HTML, and this JSON among them. A schema on that response would
 * be a claim about four things it is not. So the contract carries the shape in **prose**, on
 * `ReportJob.modelUrl` (*"`{ header, report }`… `report` is `{ title, metadata, sections }`"*), and
 * the authority for the field names is `backend/…/reporting/ReportDocument.java` + `ReportModel`.
 *
 * ## So it is PARSED, not cast — and a parse failure is a FAILURE, never an emptiness
 *
 * A cast (`as ReportDocument`) would compile and would put the drift three components deep, where
 * it renders as a report with no sections: a document that looks like a report of nothing, which is
 * indistinguishable from a run in which nothing happened. §79's cast defect, one layer out and with
 * a worse blast radius, because here the shape is not even under the compiler's eye.
 *
 * What makes this worth guarding rather than trusting is the artifact's lifetime.
 * `JsonReportRenderer` refuses to share the web layer's `ObjectMapper` for exactly this reason: the
 * artifact is *"a file that outlives the deployment that wrote it, that a screen will parse months
 * later"*. So this screen will one day be handed a document written by an older release. Two
 * behaviours follow, and they point in opposite directions on purpose:
 *
 *   - **Unknown fields are dropped, not refused.** Zod strips them by default and that is kept: a
 *     *newer* deployment's artifact — a header field R6 adds, a section property R7 adds — must
 *     still render everything this client understands. Refusing it would make every report
 *     unopenable the moment the server ships a field, which is the worse failure of the two.
 *   - **Missing or mistyped known fields throw.** `parseReportDocument` raises, the query fails, and
 *     the region renders its failed state with a retry. `data-fetching`'s rule verbatim: *"a parse
 *     failure is an error, not an emptiness — never `catch` it into `[]`/`null`, which turns a
 *     broken contract into a silent 'no records'."*
 *
 * ## Every cell is a string, and this file does no arithmetic
 *
 * `ReportModel`'s own load-bearing decision: a table cell is text the computation already formatted,
 * never a number for a renderer to format — *"that is exactly how one screen comes to read 94% while
 * the export of the same model reads 93.5%"*. The types below say so (`rows: string[][]`), which is
 * what makes the on-screen view unable to recompute a percentage even by accident.
 *
 * `Point.value` is the one number, and it is not a counter-example: a chart needs magnitude to draw
 * a bar at all, so a series carries the number *and* the table beside it carries the rendered text.
 */
import { z } from 'zod';

/** A label/value pair. Both sides are already formatted — see the header. */
const entrySchema = z.object({
  label: z.string(),
  value: z.string(),
});

const tableSchema = z.object({
  columns: z.array(z.string()),
  /**
   * Rows are **not** checked against the column count here, though the server's `ReportModel.Table`
   * constructor refuses a mismatch. Re-asserting it would turn a server-side bug into a report that
   * will not open at all, when the honest rendering — a short row, visibly short — is strictly more
   * useful to whoever has to report it. The renderer pads instead, and says so there.
   */
  rows: z.array(z.array(z.string())),
});

const pointSchema = z.object({
  label: z.string(),
  /** The one number in the model. `finite` because `Infinity`/`NaN` would make a bar width `NaN%`. */
  value: z.number().finite(),
});

const seriesSchema = z.object({
  name: z.string(),
  points: z.array(pointSchema),
});

const sectionSchema = z.object({
  heading: z.string(),
  /*
    `nullable`, not `optional`, and the difference is a decision the server already made:
    `JsonReportRenderer` writes nulls rather than omitting them, precisely so a consumer can tell
    "this section has no table" from "the producer did not know about tables". Accepting both spellings
    (`.nullish()`) would throw that distinction away here at no benefit — and would quietly accept a
    document from a producer that omits fields, which is the shape nobody has seen.
  */
  narrative: z.string().nullable(),
  table: tableSchema.nullable(),
  series: z.array(seriesSchema),
});

const reportSchema = z.object({
  title: z.string(),
  metadata: z.array(entrySchema),
  sections: z.array(sectionSchema),
});

const headerSchema = z.object({
  reportType: z.string(),
  description: z.string(),
  company: z.string(),
  projectScope: z.string(),
  scopeNote: z.string(),
  generatedBy: z.string(),
  generatedAt: z.string(),
  /*
    E1. Where the numbers came from, as a sentence the server composed — "Live — computed directly
    from…" or "As of … — computed from the execution fact projection…". Required, like every other
    provenance line: a document that omits it is one this screen must refuse rather than render
    without the line that qualifies every number under it.
  */
  dataFreshness: z.string(),
  selection: z.array(entrySchema),
});

const documentSchema = z.object({
  header: headerSchema,
  report: reportSchema,
});

export type ReportEntry = z.infer<typeof entrySchema>;
export type ReportTable = z.infer<typeof tableSchema>;
export type ReportSeries = z.infer<typeof seriesSchema>;
export type ReportSection = z.infer<typeof sectionSchema>;
export type ReportHeader = z.infer<typeof headerSchema>;
export type ReportDocument = z.infer<typeof documentSchema>;

/**
 * The whole header block, in the order it reads — and it is the SERVER's order, restated.
 *
 * `ReportDocument.headerBlock()` assembles provenance, then the selection, then the report's own
 * metadata, *"so three formats cannot print three different subsets or three different orders of one
 * block"*. This screen is the fourth renderer of that block, and it is the one that could most
 * easily drift: it is in another language, in another repository half, with nothing to fail if it
 * quietly omitted a line. So the assembly is reproduced here **as a single function** rather than
 * spread through the JSX, which is what makes the two comparable at all.
 *
 * The selection is prefixed for the server's reason: a parameter is named by the client and could
 * be called `Company`, and a header where a caller's own key silently takes the place of a
 * provenance line is a document that lies about itself.
 *
 * The one part that is deliberately **not** reproduced: the aggregation policy is not a header
 * field anywhere — it is in `report.metadata`, which is the tail of this list, because it is the one
 * item on §6e's header list only the report can answer.
 */
export function headerBlock(document: ReportDocument): ReportEntry[] {
  const { header, report } = document;
  return [
    { label: 'Report', value: header.description },
    { label: 'Report type', value: header.reportType },
    { label: 'Company', value: header.company },
    { label: 'Project scope', value: header.projectScope },
    { label: 'Scope', value: header.scopeNote },
    { label: 'Generated by', value: header.generatedBy },
    { label: 'Generated at', value: header.generatedAt },
    { label: 'Data freshness', value: header.dataFreshness },
    ...header.selection.map((entry) => ({
      label: `Selection — ${entry.label}`,
      value: entry.value,
    })),
    ...report.metadata,
  ];
}

/**
 * The width of each bar in a series, as a percentage — the model's only arithmetic.
 *
 * **Both rules are `HtmlReportRenderer`'s, and each is a defect it already fixed once.** They are
 * restated here rather than inherited, because the screen and the exported HTML are two renderers
 * of one model in two languages and nothing can fail if they disagree:
 *
 *   - **A bar is scaled against the largest value in its own series, never against a total.** A
 *     series need not be parts of a whole — "executions per tester" sums to something meaningless —
 *     so dividing by the sum draws every bar too short and, on a two-point series, reads as though
 *     everything happened half as much as it did.
 *   - **An all-zero series draws NO bars**, rather than the full-width ones `width: NaN%` renders
 *     as in most browsers. That is the failure worth the guard: a chart saying everything happened,
 *     on a report where nothing did.
 *
 * Negative values are not special-cased because the model has none — the points are counts.
 */
export function barPercents(points: readonly { value: number }[]): number[] {
  const largest = points.reduce((max, point) => Math.max(max, point.value), 0);
  if (largest <= 0) return points.map(() => 0);
  return points.map((point) => (point.value / largest) * 100);
}

/**
 * Parses a fetched artifact body into a document, or throws.
 *
 * The thrown message names the *file* rather than the field path alone, because the person who sees
 * it is looking at a report that will not open and needs to know the report is the broken thing —
 * Zod's own `path` is appended for whoever reads the console. `describeError` renders it as an
 * unexpected error, which is what it is: nothing the reader did produced it, and retrying is
 * pointless until a new report is generated.
 */
export function parseReportDocument(value: unknown): ReportDocument {
  const result = documentSchema.safeParse(value);
  if (result.success) return result.data;

  const first = result.error.issues[0];
  const where = first?.path.length ? first.path.join('.') : 'the document';
  throw new Error(
    `This report's stored model is not in a shape this version can display (${where}: `
      + `${first?.message ?? 'unknown problem'}). The report itself may still download correctly.`,
  );
}
