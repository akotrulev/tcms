import type { Criterion } from '../api/types';
import { Tooltip, TooltipContent, TooltipTrigger } from './ui';
import { criterionColor, criterionState, criterionStateLabel } from '../lib/results';

/**
 * A plan's exit criteria as one compact strip — one mark per criterion, plus the count in text.
 *
 * <h3>Why this shape rather than the "All met / N unmet" chip it replaces</h3>
 *
 * The chip could only say two things, and a plan list is read for a third. `GET /plans` never
 * evaluates a criterion — doing so means resolving every plan's results, which is the statistics
 * endpoint run once per row — so **`met` is null on every criterion this component sees on the
 * list**. The chip rendered that as "All met", which is a claim nothing checked, about the one
 * column a lead scans to decide whether a release is ready.
 *
 * A mark per criterion cannot make that mistake: three of them grey says *three gates exist and
 * nobody has measured them*, which is the true and useful sentence. The same component on a
 * screen that DOES evaluate (the statistics page, and §97's Plan tab when it lands) shows the
 * same marks in green and red with no change here.
 *
 * <h3>~~`met` is null on every criterion this component sees on the list~~ → not any more (005 S5)</h3>
 *
 * `TestPlanSummary` now carries `exitCriteriaMet` and `exitCriteriaTotal`, batched server-side
 * over the whole page of plans rather than by running the statistics endpoint once per row — which
 * is what made the old claim true and is exactly what the batched writer avoids. The per-criterion
 * `met` flags are **still null on the list**, so the marks stay grey and the strip does not pretend
 * to know *which* gate passed; what the counts add is the one sentence a lead was scanning for.
 *
 * Hence `summary`: when a caller has authoritative counts it passes them, and they win over the
 * per-criterion derivation. The paragraph above is kept rather than deleted because the mistake it
 * records — a chip claiming "All met" over data nothing evaluated — is the reason this component
 * exists, and the fix is that the counts are now *computed*, not that the caution was wrong.
 *
 * <h3>Colour is not the only signal</h3>
 *
 * Two carriers besides colour, because a 14×4px mark can hold no label:
 *
 *   - the **count in text** beside the strip — "2 of 5 met", or "5 not evaluated" when nothing
 *     was measured, which is a different sentence from "0 of 5 met" and the distinction is the
 *     entire point;
 *   - a **tooltip per mark** naming that criterion and its state, so the strip is readable one
 *     gate at a time rather than only as a gestalt.
 *
 * The strip is `aria-hidden` and the text summary carries the accessible name: a screen reader
 * reading five unlabelled marks is noise, and the summary already says what they say.
 */

const TestIdPrefix = 'CriteriaStrip';

export function CriteriaStrip({
  criteria,
  emptyLabel = 'None set',
  summary: authoritative,
  testId,
}: {
  criteria: Criterion[];
  /**
   * Counts the **server** evaluated (005 S5), when the caller has them.
   *
   * Takes precedence over the per-criterion derivation below, which cannot see them: on the plan
   * list every `Criterion.met` is null, so the derivation can only say "not evaluated" while the
   * server has just told the caller that four of five passed. Absent on a screen with no such
   * counts, where the derivation is the only answer there is.
   */
  summary?: { met: number; total: number };
  /**
   * What a plan with no criteria reads as. Defaulted rather than required so the two call sites
   * cannot drift into "None" and "None set" — `ui-rules`' one-name-one-concept, in miniature.
   */
  emptyLabel?: string;
  testId?: string;
}) {
  const composedTestId = `${testId ?? TestIdPrefix}`;

  if (criteria.length === 0) {
    return (
      <span className="text-[12px] text-ink-2" data-testid={`${composedTestId}.Empty`}>
        {emptyLabel}
      </span>
    );
  }

  const states = criteria.map(criterionState);
  const met = states.filter((state) => state === 'MET').length;
  const evaluated = states.filter((state) => state !== 'NOT_EVALUATED').length;

  /*
    Three summaries, not one with a zero in it.

    "0 of 5 met" and "5 not evaluated" are read identically by a tired lead at 6pm and mean
    opposite things — the first is a plan that failed every gate, the second a plan whose gates
    nobody has run. `evaluated === 0` is the common case on this endpoint today, so the sentence
    that distinguishes them is the one most readers will actually see.
  */
  const summary = authoritative
    /*
      The server's counts, when the caller has them. `total` is the plan's declared criteria and
      `met` how many currently pass, so "0 of 3 met" here is a real measurement and NOT the
      ambiguity the three-way sentence below exists to break — the ambiguity was between measured
      and unmeasured, and these are measured.
    */
    ? `${authoritative.met} of ${authoritative.total} met`
    : evaluated === 0
      ? `${criteria.length} not evaluated`
      : evaluated === criteria.length
        ? `${met} of ${criteria.length} met`
        : `${met} of ${evaluated} met · ${criteria.length - evaluated} not evaluated`;

  return (
    <div className="flex min-w-0 items-center gap-2" data-testid={composedTestId}>
      <div aria-hidden="true" className="flex shrink-0 gap-1">
        {criteria.map((criterion, index) => {
          const state = states[index];
          return (
            <Tooltip
              // The metric is not unique — a plan may gate on pass_rate per configuration — so
              // the index is part of the key rather than the whole of it being the metric.
              // (`PlanStatisticsPage.ExitCriteria` keys the same list the same way.)
              key={`${criterion.metric}-${index}`}
            >
              <TooltipTrigger asChild>
                <span
                  data-testid={`${composedTestId}.Mark`}
                  className="h-1 w-3.5 shrink-0 rounded-[1px]"
                  style={{
                    backgroundColor: criterionColor(state),
                    // A grey mark on a grey row is invisible at 4px, and "invisible" would read
                    // as "this plan has no criteria" — the one thing the strip exists to deny.
                    outline: state === 'NOT_EVALUATED' ? '1px solid var(--color-border)' : 'none',
                    outlineOffset: -1,
                  }}
                />
              </TooltipTrigger>
              <TooltipContent>{`${criterion.label} — ${criterionStateLabel(state)}`}</TooltipContent>
            </Tooltip>
          );
        })}
      </div>
      <span
        className="text-[12px] whitespace-nowrap text-ink-2"
        data-testid={`${composedTestId}.Summary`}
      >
        {summary}
      </span>
    </div>
  );
}
