import { Box, Chip, Tooltip } from '@mui/material';
import type {
  AutomationStatus,
  HealthSignal,
  HealthSignalSeverity,
  LifecycleStatus,
  Priority,
  StatusKind,
} from '../api/types';
import { lifecycleVar, statusVar } from '../lib/statusColors';
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
 */

/** A filled dot plus text — the pattern used wherever space is tight enough that a chip crowds. */
export function StatusDot({ kind, testId }: { kind: StatusKind; testId?: string }) {
  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }} data-testid={testId}>
      <Box
        component="span"
        aria-hidden="true"
        sx={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          flexShrink: 0,
          bgcolor: statusVar[kind],
        }}
      />
      <Box component="span" sx={{ fontSize: '0.8125rem' }}>
        {statusLabels[kind]}
      </Box>
    </Box>
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
    <Chip
      size={size}
      label={statusLabels[kind]}
      data-testid={testId}
      sx={{
        // Tinted background with a saturated border and text: readable in both schemes
        // without the chip becoming a solid colour block that fights the row it sits in.
        bgcolor: `color-mix(in srgb, ${statusVar[kind]} 12%, transparent)`,
        color: statusVar[kind],
        border: `1px solid color-mix(in srgb, ${statusVar[kind]} 40%, transparent)`,
      }}
    />
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
    <Chip
      size={size}
      label={lifecycleLabels[status]}
      data-testid={testId}
      sx={{
        bgcolor: `color-mix(in srgb, ${lifecycleVar[status]} 12%, transparent)`,
        color: lifecycleVar[status],
        border: `1px solid color-mix(in srgb, ${lifecycleVar[status]} 40%, transparent)`,
      }}
    />
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
    <Box
      data-testid={testId}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.75,
        fontWeight: emphasis ? 600 : 400,
        color: priority === 'CRITICAL' ? 'var(--color-failed)' : 'text.primary',
      }}
    >
      <Box
        component="span"
        aria-hidden="true"
        sx={{
          width: 2,
          height: 14,
          borderRadius: 1,
          bgcolor:
            priority === 'CRITICAL'
              ? 'var(--color-failed)'
              : priority === 'HIGH'
                ? 'var(--color-blocked)'
                : 'var(--color-border-strong)',
        }}
      />
      {priorityLabels[priority]}
    </Box>
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
    <Box component="span" data-testid={testId} sx={{ color: 'text.secondary' }}>
      {automationLabels[status]}
    </Box>
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
  LONG_UNEXECUTED: 'This case has not been executed in over 90 days.',
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
  FAILING_REPEATEDLY: 'This case has failed in several consecutive runs.',
  INCOMPLETE: 'Required fields are missing.',
  ORPHANED: "This case's owner has been deactivated.",
};

/**
 * How loudly each severity reads — **a map over the server's four values, not over the
 * signals.**
 *
 * ~~`const SEVERE_SIGNALS: HealthSignal[]`~~ **is gone (§179), and what it was is worth
 * keeping.** It was a hand-written list of the signals that mean something is wrong *now*,
 * with a comment saying out loud that it was a second copy of `HealthSignal.Severity` and
 * that "if a signal ever renders here with a severity beside it, this list should go rather
 * than be kept in step". By §178 **the two copies disagreed about two of the ten members** —
 * the list carried `FAILING_REPEATEDLY` (the server: WARNING) and lacked `CONDITION_DRIFT`
 * (the server: ERROR) — so the same case rendered red on a dashboard tile and amber on its
 * own chip, and no gate on either side could see it. The wire carries the severity now, so
 * this file no longer holds an opinion about which signals are severe; it only decides what
 * a severity *looks like*.
 *
 * `satisfies Record<HealthSignalSeverity, string>` is the difference between the two shapes:
 * a new severity on the wire is a compile error here, where a new *signal* was silently
 * absent from the old list and rendered as merely worth a look.
 *
 * **INFO reads the same as WARNING today and that is not an oversight.** It is the amber
 * treatment, which since §178 means most rows of a young project carry an amber chip
 * (`UNLINKED` fires on any case with no spec link). Quietening INFO is a design change to
 * the grid's reading plan, not a mapping fix, so it is recorded rather than smuggled in
 * here. `NEUTRAL` never reaches a chip — it is for the dashboard tiles that are not signals
 * — and is mapped so the exhaustiveness check has something to be exhaustive about.
 */
const severityColors = {
  ERROR: 'var(--color-failed)',
  WARNING: 'var(--color-blocked)',
  INFO: 'var(--color-blocked)',
  NEUTRAL: 'var(--color-text-muted)',
} as const satisfies Record<HealthSignalSeverity, string>;

/**
 * One raised signal, in the colour the server says it deserves.
 *
 * Both props come off one `RaisedSignal` and must keep coming off the *same* one: the pair is
 * assembled server-side from one enum in one expression precisely so a chip cannot show one
 * signal's name in another's colour.
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
  const color = severityColors[severity];
  return (
    <Tooltip title={healthHelp[signal]}>
      <Chip
        size="small"
        label={healthLabels[signal]}
        data-testid={testId}
        sx={{
          bgcolor: 'transparent',
          color,
          border: `1px solid color-mix(in srgb, ${color} 45%, transparent)`,
        }}
      />
    </Tooltip>
  );
}
