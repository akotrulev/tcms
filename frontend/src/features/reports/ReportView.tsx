import {
  Box,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
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
    <Stack sx={{ gap: 2 }} data-testid={TestId}>
      <Panel testId={`${TestId}.Header`} title="What this report covers" dense>
        <ScrollX>
          <Table size="small" data-testid={`${TestId}.Header.Grid`}>
            <TableBody>
              {headerBlock(document).map((entry) => (
                <TableRow key={entry.label} data-testid={`${TestId}.Header.Row`}>
                  <TableCell
                    component="th"
                    scope="row"
                    sx={{ color: 'text.secondary', width: 260, verticalAlign: 'top' }}
                  >
                    {entry.label}
                  </TableCell>
                  <TableCell>{entry.value}</TableCell>
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
          <Typography variant="body2" sx={{ color: 'text.secondary', p: 2 }}>
            This report has no sections. Nothing matched what it was asked to compute over — the
            header above says what that was.
          </Typography>
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
    </Stack>
  );
}

function SectionPanel({ section }: { section: ReportSection }) {
  // Narrowed into a local so the row renderer below keeps the narrowing inside its own closure —
  // a non-null assertion there would survive the day a section's table becomes conditional.
  const table = section.table;

  return (
    <Panel testId={`${TestId}.Section`} title={section.heading} dense>
      <Stack sx={{ gap: 2, p: table ? 0 : 2 }}>
        {/*
          The narrative is not decoration — it is where a section says what its denominator is
          ("of the executions recorded", not "of the cases in the run"). A section whose numbers can
          be read two ways without it will be read the wrong way by somebody who was not in the room,
          so it renders above the table rather than as a caption under it.
        */}
        {section.narrative && (
          <Typography
            variant="body2"
            sx={{ color: 'text.secondary', px: table ? 2 : 0, pt: table ? 2 : 0 }}
            data-testid={`${TestId}.Section.Narrative`}
          >
            {section.narrative}
          </Typography>
        )}

        {table && (
          <ScrollX>
            <Table size="small" data-testid={`${TestId}.Section.Grid`}>
              <TableHead>
                <TableRow>
                  {table.columns.map((column) => (
                    <TableCell key={column} sx={{ color: 'text.secondary', whiteSpace: 'nowrap' }}>
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
          <Box key={series.name} sx={{ px: table ? 2 : 0, pb: table ? 2 : 0 }}>
            <SeriesChart series={series} />
          </Box>
        ))}
      </Stack>
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
    <Stack sx={{ gap: 0.75 }} data-testid={`${TestId}.Series`}>
      <Typography variant="caption" sx={{ color: 'text.secondary', fontWeight: 600 }}>
        {series.name}
      </Typography>
      {series.points.map((point, index) => (
        <Stack
          key={`${index}-${point.label}`}
          direction="row"
          sx={{ alignItems: 'center', gap: 1 }}
          data-testid={`${TestId}.Series.Point`}
        >
          <Typography variant="caption" sx={{ width: 140, flexShrink: 0 }}>
            {point.label}
          </Typography>
          <Box sx={{ flex: 1, height: 10, bgcolor: 'action.hover', borderRadius: 0.5 }}>
            <Box
              sx={{
                width: `${percents[index] ?? 0}%`,
                height: '100%',
                bgcolor: 'primary.main',
                borderRadius: 0.5,
              }}
              data-testid={`${TestId}.Series.Bar`}
            />
          </Box>
          <Typography variant="caption" sx={{ width: 64, textAlign: 'right', flexShrink: 0 }}>
            {point.value}
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}
