import { Box, Chip, Tooltip } from '@mui/material';
import type { AutomationStatus, HealthSignal, LifecycleStatus, Priority, StatusKind } from '../api/types';
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
 * Status colour is load-bearing data here, which is why the brand palette stays out of it.
 */

const statusVar: Record<StatusKind, string> = {
  PASSED: 'var(--color-passed)',
  FAILED: 'var(--color-failed)',
  BLOCKED: 'var(--color-blocked)',
  SKIPPED: 'var(--color-skipped)',
  RETEST: 'var(--color-retest)',
  UNTESTED: 'var(--color-untested)',
  WIP: 'var(--color-wip)',
};

const lifecycleVar: Record<LifecycleStatus, string> = {
  DRAFT: 'var(--color-draft)',
  IN_REVIEW: 'var(--color-in-review)',
  APPROVED: 'var(--color-approved)',
  DEPRECATED: 'var(--color-deprecated)',
};

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
 * Signals that mean something is wrong right now, rather than merely worth a look.
 *
 * **`UNDECLARED_AUTOMATION` is deliberately not here, though its mirror is.** Stale
 * automation means a result the team believes it is getting is not arriving — a red
 * test can stay invisible for as long as it lasts. Undeclared automation means results
 * are arriving that no coverage number counts: the percentage is understated, which is
 * wrong in the direction that costs nobody a missed defect. The server draws the same
 * line (`HealthSignal.Severity`: ERROR vs WARNING), and adding the new signal here
 * "for consistency with the pair" is the tidy change that erases the distinction —
 * `HealthChip.test.tsx` exists to catch exactly that.
 *
 * This list is a second copy of that server-side severity, which is only defensible
 * because the chip has nothing to read: severity is on the wire for a `DashboardTile`
 * and not for a bare `HealthSignal`. If a signal ever renders here with a severity
 * beside it, this list should go rather than be kept in step.
 *
 * **AND THE TWO COPIES DISAGREE ABOUT TWO MEMBERS — found in §178 and deliberately not
 * fixed here.** The server's ERROR set is `OUTDATED_SPEC_CHANGED`, `CONDITION_DRIFT`,
 * `STALE_AUTOMATION` (`HealthSignal.Severity`, backend). This list has
 * `FAILING_REPEATEDLY` instead of `CONDITION_DRIFT`, so the same case is red on a
 * dashboard tile and amber on its own chip, or the reverse — which is precisely the
 * "kept in step by hand" failure the paragraph above predicted, discovered by a slice
 * that only needed to read the list. §178 stopped at recording it because changing
 * either member alters how a signal it did not derive is rendered, and the honest fix is
 * a comparison rather than an edit: the question is whether severity belongs on the wire
 * for a bare signal, which deletes this list. Left as the next slice, not as a to-do.
 */
const SEVERE_SIGNALS: HealthSignal[] = [
  'OUTDATED_SPEC_CHANGED',
  'STALE_AUTOMATION',
  'FAILING_REPEATEDLY',
];

export function HealthChip({ signal, testId }: { signal: HealthSignal; testId?: string }) {
  const severe = SEVERE_SIGNALS.includes(signal);
  const color = severe ? 'var(--color-failed)' : 'var(--color-blocked)';
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
