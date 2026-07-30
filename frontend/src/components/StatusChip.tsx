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
  OUTDATED_SPEC_CHANGED: 'The documentation block this case verifies has changed since the case was last edited.',
  STALE_APPROVAL: 'This case was edited after it was approved, so the approval no longer covers what it says.',
  CONDITION_DRIFT: 'A shared condition this case relies on has changed since the case was last reviewed.',
  NEVER_EXECUTED: 'This case has never been run.',
  LONG_UNEXECUTED: 'This case has not been executed in over 90 days.',
  STALE_AUTOMATION: 'Marked automated, but no automated result has arrived recently.',
  UNLINKED: 'This case is not linked to any documentation, so nothing traces it to a requirement.',
  FAILING_REPEATEDLY: 'This case has failed in several consecutive runs.',
  INCOMPLETE: 'Required fields are missing.',
  ORPHANED: "This case's owner has been deactivated.",
};

/** Signals that mean something is wrong right now, rather than merely worth a look. */
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
