import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import { Tooltip, TooltipContent, TooltipTrigger } from './ui';
import { cn } from '../lib/cn';
import type {
  AutomationStatus,
  HealthSignal,
  HealthSignalSeverity,
  LifecycleStatus,
  Priority,
  StatusKind,
} from '../api/types';
import { lifecycleVar, severityVar, statusVar } from '../lib/statusColors';
import {
  automationLabels,
  healthLabels,
  lifecycleLabels,
  priorityLabels,
  statusLabels,
} from '../lib/labels';

/**
 * The status vocabulary, rendered once.
 *
 * Two rules hold across all of these:
 *
 *   - **Colour is never the only signal.** Each chip carries its label as text, so a
 *     red/green distinction is not the thing carrying the meaning (WCAG 1.4.1).
 *   - **The label comes from the shared map**, so the same value reads identically in the
 *     grid, the filter, the detail pane, and the report.
 *
 * Status colour is load-bearing data here, which is why the brand palette stays out of it. The
 * palettes themselves moved to `lib/statusColors.ts` in §180, when the dashboard's stacked bars
 * needed the lifecycle one — they had been private to this file while the *server* sent a second
 * copy of the same mapping down the wire.
 *
 * ## `Pill`, and why the kit's `Badge` is not what these use
 *
 * `kit/Badge` takes a `BadgeTone` — a closed set of eight names the render's palette knows. These
 * chips are keyed on `lib/statusColors.ts` instead, which hands back a **CSS custom property name**
 * per status, lifecycle and severity, and that table is the one the server's own colour column is
 * checked against. Feeding it through an eight-name enum would mean a second mapping in the middle
 * that could disagree with it — the exact drift §180 collapsed when it moved these palettes out of
 * this file. So the tint arithmetic that was in each `sx` prop is one local component instead.
 */

/**
 * The tinted-chip treatment, once: a `color-mix` ground, a saturated border and saturated text.
 * Readable in both schemes without the chip becoming a solid block that fights the row it sits in.
 */
function Pill({
  colorVar,
  tinted = true,
  size = 'small',
  children,
  testId,
  className,
  ...rest
}: {
  /** A `var(--color-…)` reference from `lib/statusColors.ts`. */
  colorVar: string;
  /** `false` for the quieter outline used by the two warning chips. */
  tinted?: boolean;
  size?: 'small' | 'medium';
  children: ReactNode;
  testId?: string;
} & Omit<ComponentPropsWithoutRef<'span'>, 'color' | 'children'>) {
  return (
    <span
      data-testid={testId}
      className={cn(
        'inline-flex items-center rounded-full border whitespace-nowrap',
        size === 'medium' ? 'px-3 py-1 text-[13px]' : 'px-2 py-0.5 text-[11.5px]',
        className,
      )}
      style={{
        color: colorVar,
        borderColor: `color-mix(in srgb, ${colorVar} ${tinted ? 40 : 45}%, transparent)`,
        backgroundColor: tinted ? `color-mix(in srgb, ${colorVar} 12%, transparent)` : 'transparent',
      }}
      {...rest}
    >
      {children}
    </span>
  );
}

/** A filled dot plus text — the pattern used wherever space is tight enough that a chip crowds. */
export function StatusDot({ kind, testId }: { kind: StatusKind; testId?: string }) {
  return (
    <span className="inline-flex items-center gap-1.5" data-testid={testId}>
      <span
        aria-hidden="true"
        className="block size-2 shrink-0 rounded-full"
        style={{ backgroundColor: statusVar[kind] }}
      />
      <span className="text-[13px]">{statusLabels[kind]}</span>
    </span>
  );
}

export function StatusChip({
  kind,
  size = 'small',
  testId,
}: {
  kind: StatusKind;
  size?: 'small' | 'medium';
  testId?: string;
}) {
  return (
    <Pill colorVar={statusVar[kind]} size={size} testId={testId}>
      {statusLabels[kind]}
    </Pill>
  );
}

export function LifecycleChip({
  status,
  size = 'small',
  testId,
}: {
  status: LifecycleStatus;
  size?: 'small' | 'medium';
  testId?: string;
}) {
  return (
    <Pill colorVar={lifecycleVar[status]} size={size} testId={testId}>
      {lifecycleLabels[status]}
    </Pill>
  );
}

/**
 * Priority reads as text with a leading rule rather than as a coloured chip — in a grid
 * where every row also carries a result status, a second saturated chip per row turns the
 * table into confetti and the result stops standing out.
 */
export function PriorityLabel({ priority, testId }: { priority: Priority; testId?: string }) {
  const emphasis = priority === 'CRITICAL' || priority === 'HIGH';
  return (
    <span
      data-testid={testId}
      className={cn(
        'inline-flex items-center gap-1.5',
        emphasis ? 'font-semibold' : 'font-normal',
        priority === 'CRITICAL' ? 'text-[color:var(--color-failed)]' : 'text-ink',
      )}
    >
      <span
        aria-hidden="true"
        className="block h-3.5 w-0.5 shrink-0 rounded-full"
        style={{
          backgroundColor:
            priority === 'CRITICAL'
              ? 'var(--color-failed)'
              : priority === 'HIGH'
                ? 'var(--color-blocked)'
                : 'var(--color-border-strong)',
        }}
      />
      {priorityLabels[priority]}
    </span>
  );
}

export function AutomationLabel({
  status,
  testId,
}: {
  status: AutomationStatus;
  testId?: string;
}) {
  return (
    <span data-testid={testId} className="text-ink-2">
      {automationLabels[status]}
    </span>
  );
}

/**
 * Health signals are warnings about a case's own state, so they are deliberately quieter
 * than result status — an amber outline, not a filled badge. The tooltip carries the
 * explanation because the label alone can't say why it matters.
 */
const healthHelp: Record<HealthSignal, string> = {
  // Rewritten in §178, the slice that gave this signal a derivation, and wrong in three ways
  // before it — §158's correction to CONDITION_DRIFT arriving one signal later, for the same
  // reason: a tooltip nothing could raise explains a comparison nobody makes until the day it
  // starts appearing. "the block this case VERIFIES" (the server counts every relationship, not
  // only VERIFIES — a case derived from a rewritten statement is as wrong as one covering it),
  // "the BLOCK ... has changed" (the comparison is the PAGE's newest version, so editing an
  // unrelated paragraph marks the link stale — V46 §4 states that granularity and why it is the
  // finest the schema supports), and "last EDITED" (the newest case version, so assigning an
  // owner or moving a suite does not forgive a spec change).
  OUTDATED_SPEC_CHANGED:
    'A specification page this case links to has changed since the case was last saved.',
  STALE_APPROVAL: 'This case was edited after it was approved, so the approval no longer covers what it says.',
  // "since the case was last SAVED", not "last reviewed" — corrected in §158, the slice that gave
  // this signal a derivation. The server compares the newest version's snapshot against the live
  // library entry (ConditionDrift), which has nothing to do with review: a case in a project with
  // review switched off raises this exactly as readily. The old wording was harmless while nothing
  // computed the signal and would have become a tooltip explaining the wrong comparison on the day
  // it started firing.
  CONDITION_DRIFT: 'A shared condition this case relies on has changed since the case was last saved.',
  NEVER_EXECUTED: 'This case has never been run.',
  // The threshold is configurable per deployment (005 §5), so the tooltip names the fact and not
  // a number this build cannot know. See `healthLabels`.
  LONG_UNEXECUTED:
    'This case has not been run for a long time. How long counts as a long time is configured for this instance.',
  STALE_AUTOMATION: 'Marked automated, but no automated result has arrived recently.',
  UNDECLARED_AUTOMATION:
    'CI has posted results for this case recently, but its automation status says it is not automated — so automation coverage does not count it.',
  // Unchanged in §178 and checked rather than assumed: it is accurate under the derivation, and
  // deliberately says "any documentation" rather than "any requirement it verifies" — a case linked
  // only by REFERENCES does not raise this. It also matches the Traceability panel's own empty copy
  // on the same screen, which is the agreement §167 built the mirror for: the chip and the panel are
  // read off one set of rows, so a case whose only link points into a deleted page says "not linked"
  // in both places.
  UNLINKED: 'This case is not linked to any documentation, so nothing traces it to a requirement.',
  /*
    ~~'This case has failed in several consecutive runs.'~~ → rewritten 2026-08-28 against the
    predicate itself (005 §5.4), because "several consecutive runs" is wrong in two ways that matter.

    `ExecutionSource.recomputeFailingRepeatedly` takes the case's most recent results in
    `execution_fact` — a window of 3 by default, `tcms.reporting.failing-repeatedly-window` — and
    raises the signal only if **every one of them** is `FAILED`, comparing the count to the window
    SIZE rather than to the rows found. So:

      - it is `FAILED`, not "did not pass": three `BLOCKED` results in a row raise nothing;
      - it is a count of executions, not a period — there is no "recently";
      - it spans every run, plan and configuration in the project, so it is not "in this run";
      - a case with fewer results than the window never raises it, however badly it did.

    Wording that is true: *"failed the last N times it ran"*. The number is left unstated for
    `LONG_UNEXECUTED`'s reason — the window is configurable and this client is not told it.
  */
  FAILING_REPEATEDLY:
    'Every one of this case’s most recent results was a failure — across every run and configuration in this project, not just this one.',
  // Rewritten in §182, the slice that gave this signal a derivation — the fourth tooltip in this
  // map corrected by the slice that made its signal capable of appearing, after §158's, §178's and
  // §181's. Four for four, which is no longer a run of coincidences: copy for a signal nothing can
  // raise is copy nobody checks, and it reads as a perfectly sensible sentence right up until the
  // day it starts appearing.
  //
  // "Required fields are missing" was wrong twice over. Nothing is required — the case saved, and
  // the server's three terms (no steps, nothing saying what should happen, no classification) are
  // all optional columns. And it left out the gate, which is the entire meaning: only an APPROVED
  // case raises this, so what the reader is looking at is a sign-off that covered unfinished work.
  // A draft missing the same three things is just a draft and raises nothing.
  //
  // The second sentence is the action, and it is deliberately "open it" rather than a list: the
  // signal does not say WHICH of the three is missing, and a tooltip that guessed would be wrong
  // on two cases out of three.
  INCOMPLETE:
    'This case was approved without its steps, without saying what should happen, or without a '
    + 'classification. Open it to see which.',
  // Rewritten in §181, the slice that gave this signal a derivation — the third tooltip in this map
  // to be corrected by the slice that made its signal capable of appearing, after §158's and §178's,
  // and the same lesson a third time: copy nothing can raise is copy nobody checks.
  //
  // It was wrong in the way that matters most here. The server's predicate has three terms — no
  // membership, membership REMOVED, account DEACTIVATED — and this sentence named only the third,
  // which is the one no deployment can currently produce. The reader who actually sees this chip is
  // looking at a case whose owner was offboarded, and was being told something that had not
  // happened. "No longer works here" covers all three without naming a mechanism the reader has no
  // way to check, and it stays true when the account screen lands.
  //
  // The second sentence is the action, which none of the three states offer on their own: the case
  // is not wrong, it is unattended, and reassigning it is the whole of the fix. That is also why
  // this is INFO on the server — nothing here is broken.
  ORPHANED:
    'This case is owned by somebody who no longer works here, so nobody is maintaining it. Reassign it to give it an owner.',
};

const quarantineHelp =
  'Somebody has quarantined this case — its results are not trusted, and the reports print a ' +
  'pass rate that leaves it out. Run it and record a verdict as normal.';

/**
 * A case somebody has pulled out of the trusted set — §237, `case_flake.quarantined`.
 *
 * Quieter than a result status and shaped like `HealthChip` for the same reason: this is a warning
 * about the *case*, not a verdict on the execution it sits beside, and a second saturated chip per
 * row turns a run's checklist into confetti while the result stops standing out.
 *
 * **It never means skip it.** A quarantined case is still on the checklist, still takes a verdict,
 * and still counts towards the run's own pass rate — what changes is that the reports print a
 * *second* rate that leaves it out and leave the first one alone. The tooltip says so, because
 * "Quarantined" on its own reads as "don't bother", which is the one thing it must not mean.
 *
 * The tooltip cannot say *why* the case was quarantined: the reason lives on `case_flake` and the
 * execution wire carries only the flag. That is a deliberate stop rather than an oversight — the
 * reason is a sentence, the checklist is a table, and the case's own screen (`FlakePanel`) and the
 * board card both render it in full. The chip's job is to make a reader ask.
 */
export function QuarantineChip({ testId }: { testId?: string }) {
  const color = severityVar.WARNING;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Pill colorVar={color} tinted={false} testId={testId} aria-label={quarantineHelp}>
          Quarantined
        </Pill>
      </TooltipTrigger>
      <TooltipContent>{quarantineHelp}</TooltipContent>
    </Tooltip>
  );
}

/**
 * One raised signal, in the colour the server says it deserves.
 *
 * Both props come off one `RaisedSignal` and must keep coming off the *same* one: the pair is
 * assembled server-side from one enum in one expression precisely so a chip cannot show one
 * signal's name in another's colour.
 *
 * ~~`const severityColors`~~ **moved to `lib/statusColors.ts` in §183**, where the header carries
 * the reading-plan decision it stopped deferring (INFO is blue, not amber) and the reason there is
 * one table: the dashboard tile had a private copy of the same four keys that disagreed about that
 * exact value. What this component decides is unchanged and is nothing — the severity arrives with
 * the signal, and ~~`SEVERE_SIGNALS`~~, the hand-written list of "which signals are severe" that
 * drifted from the server about two of the ten members, went in §179.
 */
export function HealthChip({
  signal,
  severity,
  testId,
}: {
  signal: HealthSignal;
  severity: HealthSignalSeverity;
  testId?: string;
}) {
  const color = severityVar[severity];
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Pill colorVar={color} tinted={false} testId={testId} aria-label={healthHelp[signal]}>
          {healthLabels[signal]}
        </Pill>
      </TooltipTrigger>
      <TooltipContent>{healthHelp[signal]}</TooltipContent>
    </Tooltip>
  );
}
