import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import type { RunStatus, StatusKind, TestRunDetail } from '../../api/types';
import { useProject, useRun, useSetRunStatus } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Chip,
  ResultBar,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  type BadgeTone,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Button, Checkbox } from '../../components/ui';
import { describeError } from '../../lib/describeError';
import {
  compareCaseKeys,
  formatCount,
  formatDateTime,
  formatDuration,
  formatPercent,
} from '../../lib/format';
import { isRunClosed, runStatusLabels, statusLabels } from '../../lib/labels';
import { describeRunCode } from '../../lib/links';
import { executedCount, passRate } from '../../lib/results';
import { toast } from '../../notifications/toast';
import { CaseKeyLink, ExecuteCaseLink } from './ExecutionRowLinks';
import { FailureSummary } from './FailureSummary';
import { RunCloneDialog } from './RunCloneDialog';
import { AssignExecutionsDialog } from './AssignExecutionsDialog';
import { selectAll, toggleSelected } from './executionSelection';

/**
 * A verdict's badge tone. `WIP` is a run in flight rather than an outcome, so it takes the plain
 * badge — design language rule 5 spends the result palette on results.
 */
const statusTone = {
  PASSED: 'passed',
  FAILED: 'failed',
  BLOCKED: 'blocked',
  SKIPPED: 'skipped',
  RETEST: 'retest',
  UNTESTED: 'untested',
  WIP: 'plain',
} as const satisfies Record<StatusKind, BadgeTone>;

/**
 * A run's own status badge is its *result* rather than its lifecycle, the same reading
 * `RunListPage` takes from `renders/01-index-tables.html` frame C: a completed run whose
 * executions failed is the red that `Failed` is, because rule 5 gives results one palette.
 */
function runTone(status: RunStatus, failed: number): BadgeTone {
  if (status === 'IN_PROGRESS') return 'blocked';
  if (status === 'ABORTED') return 'skipped';
  if (status === 'COMPLETED') return failed > 0 ? 'failed' : 'passed';
  return 'plain';
}

/** The row's static locator — never interpolated with an execution id (`test-ids` rule 6). */
const RowTestId = 'RunDetail.Executions.Row';

/**
 * A run's execution list — the tester's work queue.
 *
 * The default view puts unexecuted cases first, because the question someone opening a live
 * run is asking is "what's left for me to do", not "what happened". A filter row switches to
 * the retrospective reading.
 */
export function RunDetailPage() {
  const { projectId = '', runId = '' } = useParams();
  const project = useProject(projectId);
  const run = useRun(projectId, runId);

  return (
    <div>
      <PageHeader
        testId="RunDetail.Header"
        title={run.data?.name ?? 'Test run'}
        documentTitle={run.data ? `${run.data.key} ${run.data.name}` : 'Test run'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test runs', to: `/projects/${projectId}/runs` },
          { label: run.data?.key ?? 'Run' },
        ]}
        actions={run.data && <RunActions projectId={projectId} run={run.data} />}
        meta={
          run.data && (
            <>
              <Identifier variant="strong">{run.data.key}</Identifier>
              <Badge
                tone={runTone(run.data.status, run.data.counts.failed)}
                data-testid="RunDetail.Status"
              >
                {runStatusLabels[run.data.status]}
              </Badge>
              {run.data.configuration && (
                <span className="text-[13px] text-ink-3">{run.data.configuration.name}</span>
              )}
              {/*
                Who opened the run (005 S1) — stored since M0 and served since 2026-08-28, so this
                is a wire addition rather than a new fact.

                **Null is a statement, not a gap**, and there are two of them: a `SCHEDULED` run has
                no creator because nobody created it (the schedule fired), and a creator whose
                account no longer resolves reads the same way. Both render as nothing rather than
                as "Unknown", which would send somebody looking for a person — the run's `source`
                beside it is where the first of the two is actually answered.
              */}
              {run.data.createdBy && (
                <span className="text-[13px] text-ink-3" data-testid="RunDetail.CreatedBy">
                  Opened by {run.data.createdBy.displayName}
                </span>
              )}
              {run.data.releaseVersion && (
                <span className="text-[13px] text-ink-3">
                  Release <Identifier variant="muted">{run.data.releaseVersion}</Identifier>
                  {run.data.buildNumber && (
                    <>
                      {' '}
                      build <Identifier variant="muted">{run.data.buildNumber}</Identifier>
                    </>
                  )}
                </span>
              )}
              {/* §177's Git traceability, and NOT nested inside the release the way `build` is
                  above: a CI run knows its commit whether or not anybody set a release version, so
                  a coordinate that only renders beside a release would be invisible on exactly the
                  runs that always have one. `describeRunCode` owns the wording, which is the doc
                  link's `at <sha>` and must stay it. */}
              {describeRunCode(run.data) && (
                <span className="text-[13px] text-ink-3" data-testid="RunDetail.Code">
                  <Identifier variant="muted">{describeRunCode(run.data)}</Identifier>
                </span>
              )}
              {run.data.planKey && (
                <RouterLink
                  to={`/projects/${projectId}/plans/${run.data.planId}`}
                  className="text-steel underline-offset-4 hover:underline"
                  data-testid="RunDetail.PlanLink"
                >
                  <Identifier variant="muted">{run.data.planKey}</Identifier>
                </RouterLink>
              )}
            </>
          )
        }
      />

      <DataRegion testId="RunDetail.Content" state={{ ...run, refetch: () => void run.refetch() }}>
        {(data) => <RunBody data={data} projectId={projectId} runId={runId} />}
      </DataRegion>
    </div>
  );
}

/**
 * Complete, Abort, Reopen — the run's whole lifecycle, and the first affordance in the product for
 * a column that had no writer at all until §72 — **plus Clone since §129**, which is why this took
 * the whole run rather than its status: the clone dialog prefills from the source's name, release
 * and build, and states how long the copied checklist is.
 *
 * <h3>Three decisions worth reading</h3>
 *
 * **The controls swap rather than accumulate.** A closed run offers Reopen and nothing else,
 * because the server refuses completed → aborted outright: showing both endings on an ended run
 * would offer a move that cannot succeed, which is the thing §31 exists to stop.
 *
 * **Complete is disabled on a run nobody has recorded anything in**, with the reason on the
 * tooltip, rather than left enabled for the server to refuse. The rule is knowable from the status
 * alone, so §31's disable-don't-hide applies in full — and the sentence points at Abort, which is
 * the control beside it.
 *
 * **The refusal renders here, not as a toast.** `useSetRunStatus` marks its errors handled: "abort
 * it instead" and "reopen it first" are answers about the button that was just pressed, and a
 * corner notification detaches them from it.
 */
function RunActions({ projectId, run }: { projectId: string; run: TestRunDetail }) {
  const runId = run.id;
  const status = run.status;
  const setStatus = useSetRunStatus(projectId, runId);
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [cloning, setCloning] = useState(false);
  const closed = isRunClosed(status);

  const move = (target: RunStatus) => {
    setError(null);
    setStatus.mutate(target, { onError: (failure) => setError(describeError(failure).body) });
  };

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex flex-wrap items-center gap-2">
        {/*
          §129 — `run:create`, the key the endpoint charges, which Automation Service holds: a CI
          credential may already open a run and a clone of a run is a run. Offered on a CLOSED run
          too, and deliberately: re-running last cycle against the new build is the commonest reason
          anybody opens a completed run at all, and the lifecycle controls beside it swap while this
          one does not because the source's state says nothing about whether a copy is legal.
        */}
        <PermissionGate projectId={projectId} permission="run:create">
          {({ allowed }) => (
            <Button
              size="sm"
              data-testid="RunDetail.CloneButton"
              disabled={!allowed}
              onClick={() => setCloning(true)}
            >
              Clone run
            </Button>
          )}
        </PermissionGate>
        <PermissionGate projectId={projectId} permission="run:close">
          {({ allowed }) =>
            closed ? (
              <Button
                size="sm"
                data-testid="RunDetail.Reopen"
                disabled={!allowed || setStatus.isPending}
                // IN_PROGRESS, always — the server derives NOT_STARTED for a run that never began,
                // so the client holds no second copy of that rule and cannot get it wrong for a run
                // whose startedAt it is reading from a stale cache.
                onClick={() => move('IN_PROGRESS')}
              >
                Reopen run
              </Button>
            ) : (
              <>
                <Button
                  size="sm"
                  data-testid="RunDetail.Complete"
                  /*
                    The reason travels on the control's own `title` rather than in a wrapper — a
                    disabled kit button is a plain `<button disabled>`, so there is no
                    pointer-events hole for a tooltip to need a span around.
                  */
                  title={
                    allowed && status === 'NOT_STARTED'
                      ? 'Nothing has been recorded in this run yet. Abort it instead.'
                      : undefined
                  }
                  disabled={!allowed || status === 'NOT_STARTED' || setStatus.isPending}
                  onClick={() => move('COMPLETED')}
                >
                  Complete run
                </Button>
                <Button
                  size="sm"
                  variant="danger"
                  data-testid="RunDetail.Abort"
                  disabled={!allowed || setStatus.isPending}
                  onClick={() => move('ABORTED')}
                >
                  Abort run
                </Button>
              </>
            )
          }
        </PermissionGate>
      </div>
      {error && (
        <span
          className="text-[12px] text-[color:var(--color-failed)]"
          data-testid="RunDetail.StatusError"
        >
          {error}
        </span>
      )}
      {cloning && (
        <RunCloneDialog
          open
          projectId={projectId}
          run={run}
          // The size of the checklist the copy inherits, read off the source's own executions —
          // the run carries no scope to count, which is the frozen-scope fact the dialog states.
          caseCount={(run.executions ?? []).length}
          onClose={() => setCloning(false)}
          onCloned={(created) => navigate(`/projects/${projectId}/runs/${created.id}`)}
        />
      )}
    </div>
  );
}

type Filter = 'ALL' | 'TODO' | 'FAILED';

/**
 * What this run was **asked** for (B7).
 *
 * ## The question it answers, which nothing could
 *
 * A run's checklist is a list of executions. Whether TC-41 is on it because somebody named that
 * case, because it was filed in a suite that was named, or because it carried `smoke` on the day
 * the run was made was unrecoverable — `test_run` kept the plan id and nothing else. `V61` records
 * the request, and this is where a reader meets it.
 *
 * ## Absent means NOT RECORDED, and says so
 *
 * Null is every run created before `V61` and every run opened by an ingested report, where there
 * was no request at all. Rendering that as "no scope" would state that a run covering two hundred
 * cases was asked for nothing, so the line is omitted instead: an absent sentence is the honest
 * spelling of an absent fact, and the checklist below is still the answer to what the run holds.
 *
 * ## It is not the checklist and does not pretend to be
 *
 * The counts are of what was *asked for* — two suites, one tag — never of what they resolved to.
 * A suite named here may since have been deleted. That divergence is the point of recording the
 * request, so the copy stays in the past tense and the numbers stay arm counts.
 */
function ScopeProvenance({ scopeRequest }: { scopeRequest: TestRunDetail['scopeRequest'] }) {
  if (!scopeRequest) return null;

  const arms: string[] = [];
  if (scopeRequest.suiteIds.length > 0) {
    arms.push(
      `${formatCount(scopeRequest.suiteIds.length)} ${scopeRequest.suiteIds.length === 1 ? 'suite' : 'suites'}`,
    );
  }
  if (scopeRequest.tags.length > 0) arms.push(`tagged ${scopeRequest.tags.join(', ')}`);
  if (scopeRequest.caseIds.length > 0) {
    arms.push(
      `${formatCount(scopeRequest.caseIds.length)} ${scopeRequest.caseIds.length === 1 ? 'case' : 'cases'} named directly`,
    );
  }
  // A plan-scoped run's arms are empty and the plan is the whole request, which the header already
  // renders as a link — so there is nothing to add and the line stays off rather than saying
  // "asked for: nothing".
  if (arms.length === 0) return null;

  return (
    <span className="text-[12px] text-ink-4" data-testid="RunDetail.Scope">
      Created from {arms.join(', ')} — as they resolved at the time.
    </span>
  );
}

function RunBody({ data, projectId, runId }: { data: TestRunDetail; projectId: string; runId: string }) {
  const [filter, setFilter] = useState<Filter>('ALL');
  const [selection, setSelection] = useState<string[]>([]);
  const [assignOpen, setAssignOpen] = useState(false);
  const executions = data.executions ?? [];
  const frozen = isRunClosed(data.status);

  const filtered = executions.filter((execution) => {
    if (filter === 'TODO') return !execution.status;
    if (filter === 'FAILED') return execution.status?.kind === 'FAILED' || execution.status?.kind === 'BLOCKED';
    return true;
  });

  /**
   * Unexecuted first, then failures, then everything else — and always with the case key as
   * a unique tiebreaker so the order is stable across refetches. Without the tiebreaker a
   * tester's list reshuffles under them every time a result posts.
   *
   * The tiebreaker was a bare `caseKey.localeCompare` until §100, which sorts `TC-10` ahead of
   * `TC-9` — the bug `compareReleaseVersions` had already named as "the same class as ordering by
   * case key" without anyone checking whether the case existed. It does not show on a fixture set
   * whose keys never cross a power of ten, and on a real run it reads as the list being corrupt.
   *
   * This screen keeps its needs-doing rank; Split mode's rail deliberately does not (see
   * `queueOrder.ts` for why a triage view and a position view want different orders).
   */
  const sorted = [...filtered].sort((a, b) => {
    const rank = (kind: string | undefined) => (kind === undefined ? 0 : kind === 'FAILED' || kind === 'BLOCKED' ? 1 : 2);
    return rank(a.status?.kind) - rank(b.status?.kind) || compareCaseKeys(a.caseKey, b.caseKey);
  });

  const executed = executedCount(data.counts);

  return (
    <div className="flex flex-col gap-4">
      {/*
        THE ROLLUP IS A STRIP, not a panel — design language rule 1: the subject of this screen is
        the checklist below, and `renders/02-record-as-document.html` panel B puts the bar and its
        three numbers beside the title rather than in a bordered box of their own.
      */}
      <section
        className="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-line pb-4"
        data-testid="RunDetail.Summary"
      >
        <div data-testid="RunDetail.PassRate">
          <span className="font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
            {formatPercent(passRate(data.counts, 'EXECUTED'))}
          </span>
          <span className="mt-1 block text-[12px] text-ink-4">
            passed, of {formatCount(executed)} executed · {formatCount(data.counts.total)} in scope
          </span>
        </div>

        <div className="min-w-[220px] flex-1" data-testid="RunDetail.ResultBar">
          <ResultBar
            passed={data.counts.passed}
            failed={data.counts.failed}
            blocked={data.counts.blocked}
            skipped={data.counts.skipped}
            untested={data.counts.notRun}
            className="h-2"
          />
          <div className="mt-2 flex flex-wrap gap-3 font-mono text-[11.5px] tabular-nums">
            <span style={{ color: 'var(--color-passed)' }}>{formatCount(data.counts.passed)} passed</span>
            <span style={{ color: 'var(--color-failed)' }}>{formatCount(data.counts.failed)} failed</span>
            <span style={{ color: 'var(--color-blocked)' }}>{formatCount(data.counts.blocked)} blocked</span>
            {/*
              One entry per segment the bar can draw, in the bar's own fill order.

              A legend shorter than the bar is renders audit 2.2: on TR-1 the bar drew four
              segments and its own aria-label read "6 passed, 2 failed, 1 blocked, 1 skipped of
              10", while this line printed nine of ten and ended `0 not run` — which invites the
              reader to conclude the list is complete. `PlanStatisticsPage` states all five on the
              same data, which is what makes this a defect rather than a decision.
            */}
            <span style={{ color: 'var(--color-skipped)' }}>
              {formatCount(data.counts.skipped)} skipped
            </span>
            <span className="text-ink-4">{formatCount(data.counts.notRun)} not run</span>
          </div>
        </div>

        <div className="flex flex-col gap-0.5 text-[12px] text-ink-4">
          {/*
            Three states, not two. Until runs could be created, every fixture was IN_PROGRESS
            with a `startedAt`, so the un-started case had never been rendered — and the first
            live run created through the new endpoint read "Started — · Still running", which
            is two false claims about a run nobody has opened yet.

            A run starts when somebody records the first result, not when it is named (see
            `TestRun.create`), so a null `startedAt` is an ordinary and common state rather
            than missing data.
          */}
          <span>{data.startedAt ? `Started ${formatDateTime(data.startedAt)}` : 'Not started yet'}</span>
          {data.completedAt ? (
            <span>Finished {formatDateTime(data.completedAt)}</span>
          ) : (
            data.startedAt && <span style={{ color: 'var(--color-info)' }}>Still running</span>
          )}
          <ScopeProvenance scopeRequest={data.scopeRequest} />
        </div>
      </section>

      {/*
        Between the rollup and the checklist, because it is the question asked in between: the
        summary above says how much went wrong, this says what, and the list below is where the
        work continues. It is rendered on every run including a clean one — a panel that appears
        only when there is bad news is one whose absence a reader cannot distinguish from a panel
        that failed to render.
      */}
      <FailureSummary data={data} projectId={projectId} runId={runId} />

      <section data-testid="RunDetail.Executions">
        {/* Filters in one row under the toolbar — rule 2, and the render's `.filters` strip. */}
        <div className="mb-3 flex flex-wrap items-center gap-1.5">
          {(
            [
              { value: 'ALL' as const, label: `All ${formatCount(executions.length)}` },
              { value: 'TODO' as const, label: `Not run ${formatCount(data.counts.notRun)}` },
              {
                value: 'FAILED' as const,
                label: `Needs attention ${formatCount(data.counts.failed + data.counts.blocked)}`,
              },
            ]
          ).map((option) => (
            <Chip
              key={option.value}
              selected={filter === option.value}
              aria-pressed={filter === option.value}
              onClick={() => {
                // The chips swap which rows are in view, and a selection holding ids that are
                // no longer on screen is a bulk act over rows the person cannot see.
                setFilter(option.value);
                setSelection([]);
              }}
              data-testid={`RunDetail.Filter.${option.value}`}
            >
              {option.label}
            </Chip>
          ))}
          <span className="ml-auto text-[12px] text-ink-4">
            <span className="font-mono tabular-nums text-ink-2">{formatCount(sorted.length)}</span>{' '}
            of {formatCount(executions.length)} executions
          </span>
        </div>

        {selection.length > 0 && (
          <div
            className="mb-3 flex flex-wrap items-center gap-3 rounded-md border border-line-strong bg-wash-1 px-3 py-2"
            data-testid="RunDetail.SelectionBar"
          >
            <span className="text-[13px] font-medium text-ink" data-testid="RunDetail.SelectionCount">
              {formatCount(selection.length)} selected
            </span>
            <PermissionGate projectId={projectId} permission="run:execute">
              {({ allowed, reason }) => (
                <Button
                  size="sm"
                  variant="primary"
                  disabled={!allowed || frozen}
                  title={
                    frozen
                      ? 'This run has ended, so its checklist can no longer be reorganised. Reopen the run to make changes.'
                      : (reason ?? undefined)
                  }
                  onClick={() => setAssignOpen(true)}
                  data-testid="RunDetail.AssignButton"
                >
                  Assign…
                </Button>
              )}
            </PermissionGate>
            <Button size="sm" variant="quiet" onClick={() => setSelection([])} data-testid="RunDetail.ClearSelection">
              Clear selection
            </Button>
            {/*
              NOT "the rows on this page" — this screen is not paged, it renders the whole
              checklist narrowed by the chip. And not "every row in the run" either: `Not run` is
              exactly the filter a coordinator uses before distributing, so telling them the act
              covers rows the chip hid is the misstatement this caption exists to prevent.
            */}
            <span className="ml-auto text-[12px] text-ink-4">
              Selection covers the rows this filter shows.
            </span>
          </div>
        )}

        {sorted.length === 0 ? (
          <p className="m-0 py-6 text-[13px] text-ink-3" data-testid="RunDetail.Executions.Empty">
            {filter === 'TODO'
              ? 'Everything in this run has been executed.'
              : filter === 'FAILED'
                ? 'Nothing failed or was blocked in this run.'
                : 'This run has no cases in scope.'}
          </p>
        ) : (
          <Table data-testid="RunDetail.Executions.Grid">
            <TableHead>
              <TableRow>
                {/*
                  There was an "Execution" column here showing TE_0001. Executions no longer
                  carry a display key (plan §6t) — they are reached through their run or case —
                  so the column had no data to show. The row→detail affordance moved onto the
                  case title below, which is what a tester actually recognises.
                */}
                <TableHeaderCell className="w-[34px]">
                  <Checkbox
                    checked={
                      selection.length === 0
                        ? false
                        : selection.length === sorted.length
                          ? true
                          : 'indeterminate'
                    }
                    onCheckedChange={(checked) => setSelection(checked === true ? selectAll(sorted) : [])}
                    aria-label="Select every row this filter shows"
                    data-testid="RunDetail.Executions.SelectAll"
                  />
                </TableHeaderCell>
                <TableHeaderCell>Case</TableHeaderCell>
                <TableHeaderCell className="w-[116px]">Result</TableHeaderCell>
                <TableHeaderCell className="w-[130px]">Defects</TableHeaderCell>
                <TableHeaderCell align="right" className="w-[90px]">
                  Duration
                </TableHeaderCell>
                <TableHeaderCell className="w-[150px]">Executed by</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sorted.map((execution) => (
                <TableRow key={execution.id} data-testid={RowTestId} selected={selection.includes(execution.id)}>
                  {/*
                    Row-click selection is deliberately NOT added: the row's targets are two
                    links already, and a click that both navigates and selects is the ambiguity
                    the case grid settled with `disableRowSelectionOnClick`.
                  */}
                  <TableCell>
                    <Checkbox
                      checked={selection.includes(execution.id)}
                      onCheckedChange={() => setSelection(toggleSelected(selection, execution.id))}
                      aria-label={`Select ${execution.caseKey}`}
                      data-testid={`${RowTestId}.Select`}
                    />
                  </TableCell>
                  <TableCell variant="title">
                    <span className="flex min-w-0 flex-wrap items-center gap-2">
                      {/*
                        Both links come from `ExecutionRowLinks`, shared with Grid mode on the
                        execution screen — the key opens the case, the title opens this
                        execution. This screen had that pair right and Grid mode had it
                        backwards; one module now decides it for both.
                      */}
                      <CaseKeyLink execution={execution} projectId={projectId} testId={RowTestId} />
                      <ExecuteCaseLink
                        execution={execution}
                        projectId={projectId}
                        runId={runId}
                        testId={RowTestId}
                      />
                      {/*
                        The pinned case version. This is what makes past results honest: the
                        case may have been edited five times since, and this row still says
                        which text was executed.
                      */}
                      <span className="shrink-0" title="The case version this execution ran against">
                        <Identifier variant="muted">v{execution.caseVersionNo}</Identifier>
                      </span>
                    </span>
                  </TableCell>
                  <TableCell>
                    {execution.status?.kind ? (
                      <Badge tone={statusTone[execution.status.kind]} data-testid={`${RowTestId}.Status`}>
                        {statusLabels[execution.status.kind]}
                      </Badge>
                    ) : (
                      <span className="text-[12px] text-ink-4" data-testid={`${RowTestId}.Status`}>
                        {statusLabels.UNTESTED}
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    {execution.defects.length === 0 ? (
                      <span className="text-[12px] text-ink-4">—</span>
                    ) : (
                      <span className="flex flex-col gap-0.5">
                        {execution.defects.map((defect) => (
                          <a
                            key={defect.id}
                            href={defect.url}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-[12px] text-steel underline-offset-4 hover:underline"
                            data-testid={`${RowTestId}.DefectLink`}
                          >
                            <Identifier>{defect.externalKey}</Identifier>
                            <span aria-hidden="true" className="ml-1 text-ink-4">
                              ↗
                            </span>
                            <span className="sr-only">(opens in a new tab)</span>
                          </a>
                        ))}
                      </span>
                    )}
                  </TableCell>
                  <TableCell align="right" className="text-[12px]">
                    <Identifier variant="muted">{formatDuration(execution.durationMs)}</Identifier>
                  </TableCell>
                  <TableCell className="text-[12px] text-ink-4">
                    {execution.executedAt ? (
                      <span title={formatDateTime(execution.executedAt)}>
                        {execution.executedBy?.displayName ?? 'CI'}
                      </span>
                    ) : (
                      <span>
                        {execution.assignee ? `assigned to ${execution.assignee.displayName}` : 'unassigned'}
                      </span>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </section>

      <AssignExecutionsDialog
        open={assignOpen}
        onClose={() => setAssignOpen(false)}
        projectId={projectId}
        runId={runId}
        selectedIds={selection}
        onCommitted={(result) => {
          // Per outcome, never a blanket success — CaseListPage's call, and the reason `BulkResult`
          // reports lists: the coordinator needs to know that 2 of their 300 did not go through.
          const parts = [`${formatCount(result.changed.length)} assigned`];
          if (result.unchanged.length) {
            parts.push(`${formatCount(result.unchanged.length)} already assigned`);
          }
          if (result.skipped.length) parts.push(`${formatCount(result.skipped.length)} skipped`);
          toast(parts.join(' · '), {
            variant: result.skipped.length ? 'warning' : 'success',
          });
          setSelection([]);
        }}
      />
    </div>
  );
}
