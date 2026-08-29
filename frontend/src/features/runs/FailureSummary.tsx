import { STEP_DETAIL_LIMIT, useExecutionSteps, useStatuses } from '../../api/queries';
import type { CaseStatus, StepResult, TestExecutionSummary, TestRunDetail } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { formatCount, plural } from '../../lib/format';
import { runFailures, stepOutcome } from '../../lib/results';
import { CaseKeyLink, ExecuteCaseLink } from './ExecutionRowLinks';

/**
 * **What actually broke** — the run's failures, and the steps inside them, on one panel.
 *
 * The question this answers is the one a lead has when a run finishes and the one a tester has
 * when they come back to a run somebody else worked: *which cases failed, and where in each one?*
 * Before this it was answerable only by filtering the checklist to "Needs attention" and then
 * opening every row, one at a time, to find the step.
 *
 * <h3>Four decisions, each of which the screen would look fine without</h3>
 *
 * **1. Failed and Blocked are two lists, not one "needs attention" list.** The existing filter
 * chip merges them on purpose — both need a person — but a *failure* summary that merged them
 * would report the product as broken in places nobody managed to test. `runFailures` partitions
 * them and each list carries its own sentence. Blocked comes second and says so: it is a report
 * about the environment, not about the product.
 *
 * **2. There is no percentage here.** `RunDetail.Summary` directly above already renders a pass
 * rate, and §65's recorded defect is two percentages on one screen that denominate differently.
 * Everything on this panel is a count, and every count names what it counted.
 *
 * **3. The step half is read live, per failed case, and is capped.** `steps` lives on
 * `TestExecution` and nothing at run level publishes a step rollup — so this is `STEP_DETAIL_LIMIT`
 * reads at most, in one combined region. The **case** half is complete at any run size: it comes
 * out of the `TestRunDetail` the page already holds. When the cap bites, the panel says so rather
 * than quietly reporting fewer findings than there are.
 *
 * **4. Nothing here is a rollup, so nothing here is stale.** §101's `formatComputedAt` marks the
 * screens whose numbers come from `plan_snapshot` / `suite_run_summary` and can lag a scheduler
 * tick. These numbers are computed from the executions themselves on every render, so there is no
 * "computed at" to show and adding one would be a false claim of the opposite kind.
 */

const CaseRowTestId = 'RunDetail.Failures.Case';
const StepRowTestId = 'RunDetail.Failures.Step';

export function FailureSummary({
  data,
  projectId,
  runId,
}: {
  data: TestRunDetail;
  projectId: string;
  runId: string;
}) {
  const { failed, blocked } = runFailures(data.executions ?? []);

  const statuses = useStatuses(projectId);
  /*
    The reads are keyed to the failed cases only — blocked cases are deliberately NOT opened. A
    blocked case's steps are, by definition, the ones nobody got to; fetching them would spend a
    request per row to render "0 failed, N not marked", which is the sentence the row already
    carries by being in the blocked list.
  */
  const detailed = failed.slice(0, STEP_DETAIL_LIMIT);
  const truncated = failed.length - detailed.length;
  const steps = useExecutionSteps(
    projectId,
    runId,
    detailed.map((execution) => execution.id),
  );

  return (
    <Panel title="What failed" testId="RunDetail.Failures">
      <div className="flex flex-col gap-6">
        {/*
          The headline, in counts and in words. `formatCount` rather than a bare number so the
          thousands separator matches every other figure in the product.
        */}
        <p className="m-0 text-sm" data-testid="RunDetail.Failures.Headline">
          {failed.length === 0 && blocked.length === 0
            ? 'Nothing failed and nothing was blocked in this run.'
            : [
                failed.length > 0 &&
                  `${formatCount(failed.length)} ${failed.length === 1 ? 'case' : 'cases'} failed`,
                blocked.length > 0 && `${formatCount(blocked.length)} blocked`,
              ]
                .filter(Boolean)
                .join(' · ')}
        </p>

        {failed.length === 0 && blocked.length === 0 ? (
          // Good news, worded as good news. An empty table here reads as a panel that failed to
          // load, which is the one thing this state must not be mistaken for.
          <p className="m-0 text-sm text-ink-2" data-testid="RunDetail.Failures.None">
            {(data.counts.notRun ?? 0) > 0
              ? 'Nothing has failed so far — but the run is not finished, so this is not the final answer.'
              : 'Every case in this run produced a result and none of them failed.'}
          </p>
        ) : (
          <>
            {failed.length > 0 && (
              <div className="flex flex-col gap-2">
                {/*
                  **The region wraps the step layer only, and the case rows are OUTSIDE it.**
                  That is `DataRegion`'s own first rule — wrap the response-dependent subtree and
                  nothing else — and here it is the difference between a slow step read hiding the
                  list of failures and a slow step read hiding only where in each case they were.
                  The list of failed cases needs no request at all; it is already in the run.

                  The region therefore renders as a *strip*: its loaded branch is empty, because
                  the loaded rendering is the rows above, which read the same state object.
                */}
                <DataRegion
                  testId="RunDetail.Failures.Steps"
                  state={steps}
                  skeleton={
                    <span className="m-0 text-xs text-ink-2">
                      Reading where each case failed…
                    </span>
                  }
                >
                  {() => null}
                </DataRegion>

                <div className="flex flex-col gap-3">
                  {failed.map((execution) => (
                    <FailedCaseRow
                      key={execution.id}
                      execution={execution}
                      projectId={projectId}
                      runId={runId}
                      statuses={statuses.data ?? []}
                      pending={steps.isPending}
                      // `undefined` and `[]` are different answers and the row renders them
                      // differently: not read (past the cap, or the read failed) vs read and
                      // carrying no steps at all.
                      steps={steps.data?.find((entry) => entry.executionId === execution.id)?.steps}
                    />
                  ))}
                </div>

                {truncated > 0 && (
                  <span className="m-0 text-xs text-ink-2" data-testid="RunDetail.Failures.Truncated">
                    Every failed case is listed. Step detail was read for the first{' '}
                    {formatCount(STEP_DETAIL_LIMIT)} — open the other {formatCount(truncated)} to see
                    where they failed.
                  </span>
                )}
              </div>
            )}

            {blocked.length > 0 && (
              <div className="flex flex-col gap-2">
                <span className="m-0 text-xs text-ink-2">
                  Blocked — nobody could get far enough to find out whether these pass. Not counted
                  as failures.
                </span>
                <div className="flex flex-col gap-2" data-testid="RunDetail.Failures.Blocked">
                  {blocked.map((execution) => (
                    <CaseLine
                      key={execution.id}
                      execution={execution}
                      projectId={projectId}
                      runId={runId}
                    />
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </Panel>
  );
}

/** The key and the title, both linked — §104's pair, not a third copy of it. */
function CaseLine({
  execution,
  projectId,
  runId,
}: {
  execution: TestExecutionSummary;
  projectId: string;
  runId: string;
}) {
  return (
    <div className="flex items-center gap-2 min-w-0" data-testid={CaseRowTestId}>
      <CaseKeyLink execution={execution} projectId={projectId} testId={CaseRowTestId} />
      <ExecuteCaseLink
        execution={execution}
        projectId={projectId}
        runId={runId}
        maxWidth={520}
        testId={CaseRowTestId}
      />
    </div>
  );
}

/**
 * One failed case and, beneath it, the steps that failed inside it.
 *
 * **The three numbers are the whole point.** "2 of 8 steps failed" is a half-truth if three of the
 * remaining six were never marked: the reader infers five passed. So an unmarked count is stated
 * whenever there is one, and it is never folded into either side — the rule `criterionState` and
 * `suggestedVerdict` both exist to hold, one field over.
 */
function FailedCaseRow({
  execution,
  projectId,
  runId,
  statuses,
  steps,
  pending,
}: {
  execution: TestExecutionSummary;
  projectId: string;
  runId: string;
  statuses: CaseStatus[];
  steps: StepResult[] | undefined;
  /** The step reads are in flight — the strip above says so, so the row says nothing. */
  pending: boolean;
}) {
  const outcome = steps ? stepOutcome(steps, statuses) : null;

  return (
    <div>
      <CaseLine execution={execution} projectId={projectId} runId={runId} />
      <div className="pl-4 mt-1 border-l-2 border-line">
        {pending ? null : outcome === null ? (
          <span className="m-0 text-xs text-ink-2">
            Open the case to see where it failed.
          </span>
        ) : outcome.totalCount === 0 ? (
          // Exploratory cases carry no structured steps at all. Saying "0 of 0 steps failed" would
          // read as a case whose steps all passed while the case itself failed.
          <span className="m-0 text-xs text-ink-2" data-testid={`${StepRowTestId}.None`}>
            No structured steps — the verdict is on the case as a whole.
          </span>
        ) : (
          <div className="flex flex-col gap-1">
            <span className="m-0 text-xs text-ink-2" data-testid={`${StepRowTestId}.Counts`}>
              {formatCount(outcome.failed.length)} of {plural(outcome.totalCount, 'step')} failed
              {outcome.unmarkedCount > 0 && ` · ${formatCount(outcome.unmarkedCount)} not marked`}
            </span>
            {outcome.failed.map((step) => (
              <div className="flex gap-2 items-baseline min-w-0" key={step.stepId} data-testid={StepRowTestId}>
                {/* The position as the tester saw it: `position` is 0-based on the wire. */}
                <Identifier variant="muted">{(step.position ?? 0) + 1}</Identifier>
                <div className="min-w-0">
                  <p className="m-0 text-sm" style={{ color: 'var(--color-failed)' }}>
                    {step.keyword ? `${step.keyword} ` : ''}
                    {step.action}
                  </p>
                  {step.comment && (
                    <span className="m-0 text-xs text-ink-2 block" data-testid={`${StepRowTestId}.Comment`}>
                      {step.comment}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
