import type { BurnUpReport, CumulativeFlowReport, CycleTimeReport } from '../../api/types';

/**
 * The Export control — a CSV of the numbers already on screen.
 *
 * <h2>Not a report job, and 020 D-b is why</h2>
 *
 * `POST …/reports/generate` produces a *document*: asynchronous, polled, downloaded, stored as an
 * artifact. Delivery analytics registers no `ReportComputation`, so pressing Export there would
 * generate something else entirely. This serialises the exact response objects the panels are
 * rendering, which is the strongest available form of *what you see is what exports* — not two
 * renderers over one model, but one set of numbers with no second reader at all.
 *
 * What it costs, stated: no PDF, no HTML, nothing filed on the server, and no export of a report
 * nobody has opened. All acceptable for a dashboard; none of it is what the report pipeline exists
 * for.
 *
 * <h2>Every file carries what its numbers are OF</h2>
 *
 * A header comment line with the release, the window and — when the report is insufficient — the
 * server's reason. A CSV of eleven rows with no denominator on it is the same defect as a figure
 * with no subtitle, one file format over.
 */
export function burnUpCsv(report: BurnUpReport): string {
  return [
    `# Release burn-up · ${report.releaseVersion} · created ${report.releaseCreatedAt}`,
    `# ${report.doneTotal} of ${report.countedTotal} counted issues done · `
      + `${report.scopeAddedAfterCreation} assigned after the release was created`,
    ...note(report.sufficiency.reason),
    'date,done,total_scope',
    ...report.points.map((point) => `${point.date},${point.done},${point.totalScope}`),
  ].join('\n');
}

export function cycleTimeCsv(report: CycleTimeReport): string {
  return [
    `# Cycle time · measured from ${report.measuredFrom.toLowerCase()} to done · `
      + `last ${report.windowDays} days`,
    '# This product records no moment at which work started, so this is not "started to done".',
    ...note(report.sufficiency.reason),
    'type,completed,median_days,p90_days',
    ...report.bands.map(
      (band) => `${csv(band.typeName)},${band.count},${band.medianDays},${band.p90Days}`,
    ),
  ].join('\n');
}

export function flowCsv(report: CumulativeFlowReport): string {
  return [
    `# Flow · issues per status band, weekly · last ${report.windowWeeks} weeks`,
    '# before_current_status is issues that existed then whose status on that date is recorded '
      + 'nowhere — it is not a status.',
    ...note(report.sufficiency.reason),
    'week,not_started,in_flight,done,before_current_status',
    ...report.points.map(
      (point) =>
        `${point.date},${point.notStarted},${point.inFlight},${point.done},`
        + `${point.beforeCurrentStatus}`,
    ),
  ].join('\n');
}

/** The server's insufficiency sentence, when there is one — the export must not lose it. */
function note(reason: string | null): string[] {
  return reason === null ? [] : [`# Not enough data: ${reason}`];
}

/** A field that might contain a comma. Types are named by a person, so this is not theoretical. */
function csv(value: string): string {
  return /[",\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}

/**
 * Hands the browser a file.
 *
 * An object URL rather than a `data:` one: a long CSV exceeds what some browsers accept in a `data:`
 * href, and the failure is a link that silently does nothing. Revoked on the next frame — revoking
 * synchronously races the download in Safari.
 */
export function download(filename: string, contents: string): void {
  const url = URL.createObjectURL(new Blob([contents], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  requestAnimationFrame(() => URL.revokeObjectURL(url));
}
