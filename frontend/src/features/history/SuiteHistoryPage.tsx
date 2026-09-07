import { Fragment, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { SuiteRunSummary, VersionMarker } from '../../api/types';
import { useSuiteHistory, useSuites } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Banner,
  ResultBar,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { executedCount, passRate, type Denominator } from '../../lib/results';
import { EM_DASH, formatComputedAt, formatCount, formatDateTime, formatPercent } from '../../lib/format';

/**
 * Suite execution history (plan §6p).
 *
 * The trap this screen is built around: a suite's pass rate dropping between two runs looks
 * like a regression, but if four cases were *added* in between, the drop is new coverage
 * finding new problems — a different situation entirely, and arguably a good one.
 *
 * So suite version changes appear inline, and any comparison spanning a membership change is
 * called out explicitly rather than left for the reader to notice from the total column.
 */
export function SuiteHistoryPage() {
  const { projectId = '', suiteId = '' } = useParams();
  /*
    ~~`useProject`, for the project's name in this screen's own breadcrumb.~~ →
    **superseded (021 R2): the shell names the project**, from the read it already makes
    for the rail's switcher — so this page's copy of that read went with its trail.
  */
  const suites = useSuites(projectId);
  const history = useSuiteHistory(projectId, suiteId);

  const suite = suites.data?.find((s) => s.id === suiteId);

  return (
    <div>
      <PageHeader
        testId="SuiteHistory.Header"
        title={`${suite?.name ?? 'Suite'} — execution history`}
        documentTitle={suite ? `History · ${suite.key}` : 'Suite history'}
        meta={
          suite && (
            <>
              <Identifier variant="strong">{suite.key}</Identifier>
              <span className="text-[13px] text-ink-3">
                {/*
                  `suite.caseCount` — the wire field, read directly, because since §98 it IS the
                  scope of the grid below: the endpoint scopes a suite's history to the suite's
                  resolved membership, and that is the number the contract says this field carries.

                  It used to be a rollup through `lib/suites.subtreeCaseCount`, and that helper is
                  deleted with the tree. §52's defect is what it existed for — this read
                  `suite.caseCount` when the endpoint reported on a whole subtree, so a parent
                  folder announced "3 cases now" above a table of runs covering 47. The two numbers
                  agree again for the strongest available reason: **there is only one of them.**
                */}
                <Identifier variant="muted">{formatCount(suite.caseCount)}</Identifier>{' '}
                cases now
              </span>
            </>
          )
        }
      />

      <DataRegion testId="SuiteHistory.Content" state={{ ...history, refetch: () => void history.refetch() }}>
        {(data) => (
          <SuiteHistoryBody
            entries={data.entries}
            markers={data.versionMarkers}
            computedAt={data.computedAt}
            projectId={projectId}
          />
        )}
      </DataRegion>
    </div>
  );
}

function SuiteHistoryBody({
  entries,
  markers,
  computedAt,
  projectId,
}: {
  entries: SuiteRunSummary[];
  markers: VersionMarker[];
  computedAt: string | null;
  projectId: string;
}) {
  const [denominator, setDenominator] = useState<Denominator>('EXECUTED');

  // Newest first, with the run key as a unique tiebreaker.
  const sorted = [...entries].sort(
    (a, b) => b.executedAt.localeCompare(a.executedAt) || a.runKey.localeCompare(b.runKey),
  );

  const versions = new Set(sorted.map((run) => run.suiteVersionNo));
  const spansVersions = versions.size > 1;

  if (sorted.length === 0) {
    return (
      <p className="m-0 text-[13px] text-ink-3" data-testid="SuiteHistory.Empty">
        This suite has not been executed as part of any run yet.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {/*
        THE CHROME IS A STRIP, not a panel header — design language rule 2. The staleness line and
        the arithmetic toggle sit in one row above the table, and the table itself is the subject
        with nothing wrapped around it (rule 1).

        How old these sums are (§101). Since the counts come from the `suite_run_summary` rollup
        they can lag by up to one scheduler tick — and unlike the plan page, this screen has no
        second staleness signal to be confused with, so the line stands alone.

        Always rendered, including the null case: "Computed now" is a positive statement rather
        than the absence of a warning. A screen that says nothing when it is current leaves the
        reader unable to tell current from unlabelled, which is the whole reason the contract makes
        the field required rather than optional.
      */}
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-[12px] text-ink-4" data-testid="SuiteHistory.ComputedAt">
          {formatComputedAt(computedAt)}
          {computedAt && ' — updated every 15 minutes'}
        </span>

        <span className="ml-auto flex items-center gap-1.5 text-[12px] text-ink-4">
          Pass rate as a share of
          <span className="flex items-center gap-0.5 rounded-md border border-line p-0.5">
            {(['EXECUTED', 'TOTAL'] as const).map((option) => (
              <button
                key={option}
                type="button"
                aria-pressed={denominator === option}
                onClick={() => setDenominator(option)}
                data-testid={`SuiteHistory.Denominator.${option}`}
                className={
                  'rounded-sm px-2 py-0.5 text-[11.5px] focus-visible:outline-2 ' +
                  'focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)] ' +
                  (denominator === option ? 'bg-wash-3 text-ink' : 'text-ink-3 hover:text-ink-2')
                }
              >
                {option === 'EXECUTED' ? 'what ran' : 'the whole suite'}
              </button>
            ))}
          </span>
        </span>
      </div>

      {/*
        The caveat that makes the table honest. It fires from the data rather than being
        written by hand, so it appears whenever it is true and never when it isn't.
      */}
      {spansVersions && (
        <Banner tone="steel" data-testid="SuiteHistory.MembershipNotice">
          <span>
            The suite&rsquo;s membership changed during this period — it spans{' '}
            {formatCount(versions.size)} versions. Compare rows within one version; across a
            version change, a movement in the pass rate can be new coverage rather than a
            regression. The totals column shows the case count each run actually covered.
          </span>
        </Banner>
      )}

      <Table data-testid="SuiteHistory.Grid">
        <TableHead>
          <TableRow>
            <TableHeaderCell>Run</TableHeaderCell>
            <TableHeaderCell className="w-[110px]">Suite version</TableHeaderCell>
            <TableHeaderCell className="w-[150px]">Configuration</TableHeaderCell>
            <TableHeaderCell className="w-[120px]">Release</TableHeaderCell>
            <TableHeaderCell className="w-[170px]">Result</TableHeaderCell>
            <TableHeaderCell align="right" className="w-[130px]">
              Pass rate
            </TableHeaderCell>
            <TableHeaderCell align="right" className="w-[90px]">
              Cases
            </TableHeaderCell>
            <TableHeaderCell className="w-[150px]">Executed</TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {sorted.map((run, index) => {
            const previous = sorted[index - 1];
            // A version boundary gets its own row so the change is impossible to miss.
            const boundary =
              previous !== undefined && previous.suiteVersionNo !== run.suiteVersionNo
                ? markers.find((m) => m.versionNo === previous.suiteVersionNo)
                : undefined;

            return (
              <Fragment key={run.runId}>
                {boundary && (
                  <TableRow key={`marker-${boundary.versionNo}`} data-testid="SuiteHistory.VersionMarker">
                    <TableCell colSpan={8} className="bg-wash-1">
                      <span className="flex flex-wrap items-center gap-2">
                        <Badge tone="steel">Suite changed to v{boundary.versionNo}</Badge>
                        <span className="text-[12px] text-ink-4">
                          {/* Nullable for CaseHistoryPage's recorded reason. */}
                          {boundary.author?.displayName ?? 'Unknown author'} ·{' '}
                          {formatDateTime(boundary.occurredAt)}
                        </span>
                        {boundary.changeComment && (
                          <span className="text-[12px] text-ink-2">{boundary.changeComment}</span>
                        )}
                      </span>
                    </TableCell>
                  </TableRow>
                )}

                <TableRow data-testid="SuiteHistory.Row">
                  <TableCell
                    variant="title"
                    subtitle={
                      <>
                        {/*
                          A withdrawn run keeps its row (§6p: history that quietly loses rows is
                          worse than no history) but loses its link, because the run detail answers
                          404 — offering navigation into it would be an invitation to a dead end.
                          The key stays legible so the row can still be matched against a report.
                        */}
                        {run.runDeleted ? (
                          <Identifier variant="muted">{run.runKey}</Identifier>
                        ) : (
                          <RouterLink
                            to={`/projects/${projectId}/runs/${run.runId}`}
                            className="text-inherit hover:text-ink"
                            data-testid="SuiteHistory.Row.RunLink"
                          >
                            <Identifier variant="muted">{run.runKey}</Identifier>
                          </RouterLink>
                        )}
                        {run.runDeleted && (
                          <>
                            {' '}
                            <span title="This run was withdrawn. Its results stay on this timeline — they were true when they were recorded — but the run itself can no longer be opened.">
                              <Badge tone="plain" data-testid="SuiteHistory.Row.Withdrawn">
                                withdrawn
                              </Badge>
                            </span>
                          </>
                        )}
                      </>
                    }
                  >
                    {run.runName}
                  </TableCell>
                  <TableCell>
                    <Identifier variant="muted">v{run.suiteVersionNo}</Identifier>
                  </TableCell>
                  <TableCell className="text-[12px]">{run.configuration ?? EM_DASH}</TableCell>
                  <TableCell className="text-[12px]">
                    {run.releaseVersion ? (
                      <Identifier variant="muted">{run.releaseVersion}</Identifier>
                    ) : (
                      <span className="text-ink-4">{EM_DASH}</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <span
                      data-testid="SuiteHistory.Row.ResultBar"
                      title={`${formatCount(run.counts.passed)} passed · ${formatCount(
                        run.counts.failed,
                      )} failed · ${formatCount(run.counts.blocked)} blocked`}
                    >
                      <ResultBar
                        passed={run.counts.passed}
                        failed={run.counts.failed}
                        blocked={run.counts.blocked}
                        skipped={run.counts.skipped}
                        untested={run.counts.notRun}
                        className="w-[110px] min-w-0"
                      />
                    </span>
                  </TableCell>
                  <TableCell align="right" data-testid="SuiteHistory.Row.PassRate">
                    <Identifier>
                      {formatPercent(passRate(run.counts, denominator), 0)}
                    </Identifier>
                    <span className="ml-1.5 text-[11.5px] text-ink-4">
                      {formatCount(run.counts.passed)}/
                      {formatCount(denominator === 'EXECUTED' ? executedCount(run.counts) : run.counts.total)}
                    </span>
                  </TableCell>
                  <TableCell align="right">
                    {/* The count each run actually covered, so a changed total is visible
                        next to the rate it moved. */}
                    <span title={`${formatCount(executedCount(run.counts))} executed`}>
                      <Identifier>{formatCount(run.counts.total)}</Identifier>
                    </span>
                  </TableCell>
                  <TableCell className="text-[12px] text-ink-4">
                    {formatDateTime(run.executedAt)}
                  </TableCell>
                </TableRow>
              </Fragment>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
