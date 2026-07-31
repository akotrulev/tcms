import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Select,
  Stack,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import type { BulkResult, Condition, LifecycleStatus } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { lifecycleLabels } from '../../lib/labels';
import { useBulkCases, useConditions } from '../../api/queries';
import { formatCount } from '../../lib/format';

/**
 * Bulk edits are **preview, then commit**.
 *
 * The dialog runs the operation as a dry run first and shows exactly what would change,
 * what is already compliant, and what would be skipped and why. Only then does the confirm
 * button commit. A mass edit that applies straight from a selection is how someone
 * "approves 240 cases" at 5pm and discovers on Monday that 30 of them shouldn't have been.
 *
 * The result is reported **per item**, not as a blanket toast: "38 changed, 4 already
 * approved, 2 skipped" with the skipped ones named. A blanket success over a partial
 * failure is the worse half of that defect, because the user believes all 44 went through.
 */

/** Contract `BulkOperation.action` values this dialog can drive. */
type Action = 'SET_STATUS' | 'ATTACH_CONDITIONS';

const ACTIONS: { value: Action; label: string }[] = [
  { value: 'SET_STATUS', label: 'Change status' },
  { value: 'ATTACH_CONDITIONS', label: 'Add a precondition' },
];

const TestIdPrefix = 'Cases.BulkDialog';

export function BulkActionDialog({
  open,
  onClose,
  projectId,
  selectedIds,
  onCommitted,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  selectedIds: string[];
  onCommitted: (result: BulkResult) => void;
}) {
  const [action, setAction] = useState<Action>('SET_STATUS');
  const [lifecycleValue, setLifecycleValue] = useState<LifecycleStatus>('IN_REVIEW');
  const [conditionId, setConditionId] = useState('');
  const [preview, setPreview] = useState<BulkResult | null>(null);

  const conditions = useConditions(projectId, '', 'PRE');
  const bulk = useBulkCases(projectId);

  // Changing the action or its argument invalidates any preview taken against the previous
  // one — committing a preview that describes a different operation is the bug this prevents.
  useEffect(() => {
    setPreview(null);
  }, [action, lifecycleValue, conditionId]);

  const payload =
    action === 'SET_STATUS' ? { lifecycleStatus: lifecycleValue } : { conditionId };
  const hasArgument = action === 'SET_STATUS' ? !!lifecycleValue : !!conditionId;
  const canRun = selectedIds.length > 0 && hasArgument;

  const run = async (dryRun: boolean) => {
    const result = await bulk.mutateAsync({ caseIds: selectedIds, action, payload, dryRun });
    if (dryRun) setPreview(result);
    else {
      onCommitted(result);
      handleClose();
    }
  };

  const handleClose = () => {
    setPreview(null);
    onClose();
  };

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth data-testid={TestIdPrefix}>
      <DialogTitle>
        Edit {formatCount(selectedIds.length)} selected {selectedIds.length === 1 ? 'case' : 'cases'}
      </DialogTitle>

      <DialogContent dividers>
        <Stack sx={{ gap: 2 }}>
          <Box>
            <Typography variant="caption" component="label" htmlFor="bulk-operation" sx={{ display: 'block', mb: 0.5 }}>
              Operation
            </Typography>
            <Select
              id="bulk-operation"
              size="small"
              fullWidth
              value={action}
              onChange={(event) => setAction(event.target.value as Action)}
              data-testid={`${TestIdPrefix}.Operation`}
            >
              {ACTIONS.map((op) => (
                <MenuItem key={op.value} value={op.value}>
                  {op.label}
                </MenuItem>
              ))}
            </Select>
          </Box>

          {action === 'SET_STATUS' ? (
            <Box>
              <Typography variant="caption" component="label" htmlFor="bulk-status" sx={{ display: 'block', mb: 0.5 }}>
                New status
              </Typography>
              <Select
                id="bulk-status"
                size="small"
                fullWidth
                value={lifecycleValue}
                onChange={(event) => setLifecycleValue(event.target.value as LifecycleStatus)}
                data-testid={`${TestIdPrefix}.Field.LifecycleStatus`}
              >
                {Object.entries(lifecycleLabels).map(([key, label]) => (
                  <MenuItem key={key} value={key}>
                    {label}
                  </MenuItem>
                ))}
              </Select>
            </Box>
          ) : (
            <Box>
              <Typography variant="caption" component="label" htmlFor="bulk-condition" sx={{ display: 'block', mb: 0.5 }}>
                Precondition
              </Typography>
              <Select
                id="bulk-condition"
                size="small"
                fullWidth
                displayEmpty
                value={conditionId}
                onChange={(event) => setConditionId(event.target.value)}
                data-testid={`${TestIdPrefix}.Field.Condition`}
              >
                <MenuItem value="">Choose a condition…</MenuItem>
                {(conditions.data ?? []).map((condition: Condition) => (
                  <MenuItem key={condition.id} value={condition.id}>
                    {condition.title}
                    {/* Omitted at zero rather than rendered as "used by 0". Nothing maintains
                        usage_count yet, so on a real instance every row would carry it — a label
                        stating a fact the data never made, and one that invites reading the
                        order as a ranking when it is currently just the title. Same call as the
                        dangling "Your role:" and the invented status colour. */}
                    {condition.usageCount > 0 && (
                      <Box component="span" sx={{ ml: 1, color: 'text.secondary', fontSize: '0.75rem' }}>
                        used by {formatCount(condition.usageCount)}
                      </Box>
                    )}
                  </MenuItem>
                ))}
              </Select>
            </Box>
          )}

          {preview && <BulkPreview result={preview} />}

          {bulk.isError && (
            <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Error`}>
              {bulk.error instanceof Error ? bulk.error.message : "Couldn't apply the change. Try again."}
            </Alert>
          )}
        </Stack>
      </DialogContent>

      <DialogActions>
        <Button onClick={handleClose} data-testid={`${TestIdPrefix}.Cancel`}>
          Cancel
        </Button>
        <Button
          onClick={() => void run(true)}
          disabled={!canRun || bulk.isPending}
          data-testid={`${TestIdPrefix}.Preview`}
        >
          {bulk.isPending && !preview ? 'Checking…' : 'Preview changes'}
        </Button>
        <Button
          variant="contained"
          onClick={() => void run(false)}
          // Commit is gated on having seen a preview — the point of preview-then-commit is
          // lost if the user can skip straight past it.
          disabled={!preview || preview.changed.length === 0 || bulk.isPending}
          title={!preview ? 'Preview the changes first' : undefined}
          data-testid={`${TestIdPrefix}.Confirm`}
        >
          {preview ? `Apply to ${formatCount(preview.changed.length)} cases` : 'Apply'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function BulkPreview({ result }: { result: BulkResult }) {
  return (
    <Box data-testid={`${TestIdPrefix}.Preview.Result`}>
      <Alert
        severity={result.skipped.length > 0 ? 'warning' : 'info'}
        icon={false}
        sx={{ '& .MuiAlert-message': { width: '100%' } }}
      >
        <AlertTitle sx={{ fontSize: '0.875rem' }}>Preview</AlertTitle>
        <Stack sx={{ gap: 1 }}>
          <Typography variant="body2">
            <strong>{formatCount(result.changed.length)}</strong> will change ·{' '}
            <strong>{formatCount(result.unchanged.length)}</strong> already match ·{' '}
            <strong>{formatCount(result.skipped.length)}</strong> will be skipped
          </Typography>

          {result.changed.length > 0 && (
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                Changing
              </Typography>
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mt: 0.5 }}>
                {result.changed.slice(0, 12).map((key) => (
                  <Identifier key={key} variant="muted">
                    {key}
                  </Identifier>
                ))}
                {result.changed.length > 12 && (
                  <Typography variant="caption">+{result.changed.length - 12} more</Typography>
                )}
              </Box>
            </Box>
          )}

          {/* Skipped items are named with their reason — "2 skipped" alone leaves the user
              with no way to find out which two, or what to do about them. */}
          {result.skipped.length > 0 && (
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                Skipped
              </Typography>
              <Stack component="ul" sx={{ m: 0, mt: 0.5, pl: 2, gap: 0.25 }}>
                {result.skipped.map((item) => (
                  <Box component="li" key={item.key}>
                    <Typography variant="caption">
                      <Identifier variant="muted">{item.key}</Identifier> — {item.reason}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </Box>
          )}
        </Stack>
      </Alert>
    </Box>
  );
}
