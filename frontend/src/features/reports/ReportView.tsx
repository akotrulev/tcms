import { cn } from '../../lib/cn';
import { Table, TableBody, TableCell, TableHead, TableRow } from '../../components/kit';
import { Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import {
  barPercents,
  headerBlock,
  type ReportDocument,
  type ReportSection,
  type ReportSeries,
} from '../../lib/reportDocument';

/**
 * A stored report model, on screen — F2 of the M2.5 plan.
 *
 * ## This component computes nothing, and that is the whole of its correctness
 *
 * The plan's line for F2 says to reuse `lib/results.ts` for the count arithmetic rather than
 * computing percentages in the page. **There is no arithmetic to reuse it for**, and the departure
 * is worth stating rather than leaving as an omission: `ReportModel`'s load-bearing decision is
 * that *every cell is a string* — text the computation already formatted — precisely so that
 * *"what you see is what exports"* is a property of the design instead of a promise. A percentage
 * recomputed here from numbers the model did not send is exactly the defect §65 records as "two
 * contradictory percentages on one screen", with the second copy in another format of the same
 * report. So the tables below render the strings they are given, in the order they are given, and
 * `lib/results.ts` is deliberately **not** imported.
 *
 * The one exception is the bar width, and it is not a counter-example: a chart needs magnitude to
 * draw a bar at all, which is why `ReportModel.Point` carries a number beside the table's rendered
 * text. That arithmetic lives in `lib/reportDocument.ts`'s `barPercents`, with both of the defects
 * `HtmlReportRenderer` already fixed once.
 *
 * ## The header block is the server's, reassembled once
 *
 * §6e makes the provenance block mandatory, and `ReportDocument.headerBlock()` assembles it in one
 * place *"so three formats cannot print three different subsets"*. This screen is the fourth
 * renderer of that block and the one furthest from it, so the assembly is a single function
 * (`headerBlock`) rather than JSX that walks the header field by field — the two are comparable
 * only if the client's version is one list in one place.
 *
 * ## Presentational, and it takes a document rather than a job
 *
 * No queries, no route params, no job. It renders whatever document it is handed, which is what
 * makes it testable against a hostile model and what would let a future saved-report screen render
 * one without going through a job at all.
 */

const TestId = 'Report';

export function ReportView({ document }: { document: ReportDocument }) {
  const { report } = document;

  return (
    <div className="flex flex-col gap-4" data-testid={TestId}>
      <Panel testId={`${TestId}.Header`} title="What this report covers" dense>
        <ScrollX>
          <Table data-testid={`${TestId}.Header.Grid`}>
            <TableBody>
              {headerBlock(document).map((entry) => (
                <TableRow key={entry.label} data-testid={`${TestId}.Header.Row`}>
                  {/* `<th scope="row">`: the label IS this row's header, and the block is a
                      two-column label/value table rather than a grid of data. */}
                  <TableCell rowHeader className="w-[260px] text-left align-top font-normal text-ink-2">
                    {entry.label}
                  </TableCell>
                  {/*
                    §3's measure, on the one cell that holds prose. Two of these entries are the
                    report's scope sentences — "Everything a customer can reach from the storefront,
                    on the two browsers that carry 94% of our traffic" — and a table cell that does
                    not wrap forced this block to 1639px inside a 1366px region, so reading one
                    sentence needed horizontal scrolling (renders audit 2.5). `whitespace-normal`
                    because `kit/Table`'s cells deliberately do not wrap: that rule is right for a
                    grid of values and wrong for a paragraph, and this is the paragraph.
                  */}
                  <TableCell className="max-w-[70ch] whitespace-normal">{entry.value}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ScrollX>
      </Panel>

      {/*
        A report with no sections is a legal report — a selection that matched nothing still has a
        header saying what was asked for, and that header is the useful part. So this says so
        instead of rendering as though the page failed to load: an empty body and a failed read are
        the two things §85's 404-versus-`200 []` argument exists to keep apart.
      */}
      {report.sections.length === 0 ? (
        <Panel testId={`${TestId}.NoSections`} dense>
          <p className="m-0 text-[13px] text-ink-2 p-4">
            This report has no sections. Nothing matched what it was asked to compute over — the
            header above says what that was.
          </p>
        </Panel>
      ) : (
        report.sections.map((section, index) => (
          // Keyed on the heading and its position: headings are the model's own labels and need not
          // be unique across a report (R7 adds repeated per-group sections), while the position
          // alone would be the index key the rules forbid — the pair is stable because the model's
          // order is fixed and this list is never reordered or filtered.
          <SectionPanel key={`${index}-${section.heading}`} section={section} />
        ))
      )}
    </div>
  );
}

function SectionPanel({ section }: { section: ReportSection }) {
  // Narrowed into a local so the row renderer below keeps the narrowing inside its own closure —
  // a non-null assertion there would survive the day a section's table becomes conditional.
  const table = section.table;

  return (
    <Panel testId={`${TestId}.Section`} title={section.heading} dense>
      <div className={cn('flex flex-col gap-4', table ? 'p-0' : 'p-4')}>
        {/*
          The narrative is not decoration — it is where a section says what its denominator is
          ("of the executions recorded", not "of the cases in the run"). A section whose numbers can
          be read two ways without it will be read the wrong way by somebody who was not in the room,
          so it renders above the table rather than as a caption under it.
        */}
        {section.narrative && (
          <p
            className={cn('m-0 text-[13px] text-ink-2', table && 'px-4 pt-4')}
            data-testid={`${TestId}.Section.Narrative`}
          >
            {section.narrative}
          </p>
        )}

        {table && (
          <ScrollX>
            <Table data-testid={`${TestId}.Section.Grid`}>
              <TableHead>
                <TableRow>
                  {table.columns.map((column) => (
                    <TableCell key={column} className="text-ink-2 whitespace-nowrap">
                      {column}
                    </TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {table.rows.map((row, rowIndex) => (
                  // The row's own cells are its identity; two identical rows in one table are two
                  // rows of the same thing, so the index disambiguates them and nothing reorders.
                  <TableRow key={`${rowIndex}-${row.join('|')}`} data-testid={`${TestId}.Section.Row`}>
                    {/*
                      Padded to the header's width rather than rendered short. The server's
                      `ReportModel.Table` constructor refuses a mismatched row, so this is
                      unreachable in a document it wrote — but a shifted row puts a number under the
                      wrong column heading and still looks like a report, which is the one outcome
                      worth spending three lines to make impossible.
                    */}
                    {table.columns.map((column, cellIndex) => (
                      <TableCell key={column}>{row[cellIndex] ?? ''}</TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </ScrollX>
        )}

        {section.series.map((series) => (
          <div key={series.name} className={cn(table && 'px-4 pb-4')}>
            <SeriesChart series={series} />
          </div>
        ))}
      </div>
    </Panel>
  );
}

/**
 * A labelled bar per point, in CSS — the same chart the exported HTML draws, for the same reason it
 * draws it that way: no library, no canvas, nothing to fail to load.
 *
 * **The value is printed beside every bar.** A bar alone encodes the magnitude only through its
 * length against a scale the reader cannot see, and `accessibility`'s not-by-colour-alone rule has
 * the same shape here — the number is the accessible rendering of the bar, which is why the row is
 * a `<figure>`-less plain list with the text present rather than an image with an alt.
 */
function SeriesChart({ series }: { series: ReportSeries }) {
  const percents = barPercents(series.points);

  return (
    <div className="flex flex-col gap-1.5" data-testid={`${TestId}.Series`}>
      <span className="m-0 text-[12px] text-ink-2 font-semibold">
        {series.name}
      </span>
      {series.points.map((point, index) => (
        <div className="flex items-center gap-2" key={`${index}-${point.label}`} data-testid={`${TestId}.Series.Point`}>
          <span className="m-0 text-[12px] shrink-0" style={{ width: 140 }}>
            {point.label}
          </span>
          <div className="h-2.5 flex-1 rounded-sm bg-field">
            <div
              data-testid={`${TestId}.Series.Bar`}
              className="h-full rounded-sm bg-[color:var(--color-viz-1)]"
              style={{ width: `${percents[index]}%` }}
            />
          </div>
          <span className="m-0 text-[12px] text-right shrink-0" style={{ width: 64 }}>
            {point.value}
          </span>
        </div>
      ))}
    </div>
  );
}
