import {
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControlLabel,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import type { CaseConditionRef } from '../../api/types';
import { inputTestId } from '../../lib/muiTestIds';

/**
 * Blocked is the one result that requires an explanation, because it is a statement about the
 * environment rather than about the software. Recording it without saying what blocked you
 * produces a row nobody can act on — and the pre-filled unmet preconditions mean the tester
 * usually doesn't have to type anything.
 *
 * <h3>Two callers, one dialog, and the difference is who holds the checklist</h3>
 *
 * Moved out of `ExecutionPage` in §100, when Grid mode became a second caller.
 *
 *   - **Focus/Split** pass `unmet` — the tester has already unticked the preconditions that did
 *     not hold, on the checklist that is on screen beside the steps. The dialog states them back.
 *   - **Grid** has no checklist on screen, so it passes `conditions` instead and the dialog
 *     *renders* one. This is what keeps the rule intact in the third mode: Grid's `B` is not a
 *     one-click write, because Blocked has to say which precondition failed and Grid does not
 *     otherwise know.
 *
 * A case with no preconditions is a real and ordinary state in both callers — then there is no
 * checklist to show and the free-text reason is the whole of it.
 */
export function BlockedDialog({
  open,
  onClose,
  unmet = [],
  conditions,
  pending = false,
  onConfirm,
}: {
  open: boolean;
  onClose: () => void;
  /** Titles the caller already knows did not hold (Focus/Split). */
  unmet?: string[];
  /** The case's preconditions, when the caller has no checklist of its own (Grid). */
  conditions?: CaseConditionRef[];
  pending?: boolean;
  onConfirm: (reason: string) => void;
}) {
  // Which conditions the tester has ticked OFF here — only used when this dialog owns the
  // checklist. Keyed by id, absent meaning "still holds", so a case whose conditions change
  // between renders cannot leave a stale `true` behind.
  const [failedHere, setFailedHere] = useState<Record<string, boolean>>({});
  const [reason, setReason] = useState('');

  const ownChecklist = conditions !== undefined;
  const failedTitles = ownChecklist
    ? (conditions ?? []).filter((condition) => failedHere[condition.id]).map((c) => c.title)
    : unmet;

  const prefilled = failedTitles.length
    ? `Blocked — these preconditions did not hold:\n${failedTitles.map((title) => `- ${title}`).join('\n')}`
    : '';
  // The typed reason wins once there is one, so the prefill is a starting point rather than a
  // value that keeps overwriting what the tester wrote.
  const value = reason || prefilled;

  const close = () => {
    setFailedHere({});
    setReason('');
    onClose();
  };

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth data-testid="Execution.BlockedDialog">
      <DialogTitle>Record this as blocked</DialogTitle>
      <DialogContent>
        <DialogContentText sx={{ mb: 2, fontSize: '0.875rem' }}>
          Blocked means the test couldn't be run — not that the software failed. It's excluded
          from the pass rate and shown separately, so say what stopped you.
        </DialogContentText>

        {ownChecklist && (conditions ?? []).length > 0 && (
          <Stack sx={{ mb: 2 }} data-testid="Execution.BlockedDialog.Conditions">
            <Typography variant="caption" sx={{ color: 'text.secondary', mb: 0.5 }}>
              Tick the preconditions that did not hold
            </Typography>
            {(conditions ?? []).map((condition) => (
              <FormControlLabel
                key={condition.id}
                control={
                  <Checkbox
                    size="small"
                    checked={failedHere[condition.id] ?? false}
                    onChange={(event) =>
                      setFailedHere((previous) => ({ ...previous, [condition.id]: event.target.checked }))
                    }
                    // `inputTestId`, not a bare `data-testid`: on a MUI Checkbox that lands on
                    // the wrapper span where `toBeChecked` cannot see it — §62's lesson, and the
                    // reason `lib/muiTestIds.ts` exists.
                    slotProps={inputTestId('Execution.BlockedDialog.Condition', condition.title)}
                  />
                }
                label={<Typography variant="body2">{condition.title}</Typography>}
              />
            ))}
          </Stack>
        )}

        <TextField
          label="What blocked this test"
          fullWidth
          multiline
          minRows={3}
          value={value}
          onChange={(event) => setReason(event.target.value)}
          slotProps={{ htmlInput: { 'data-testid': 'Execution.BlockedDialog.ReasonField' } }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={close} data-testid="Execution.BlockedDialog.Cancel">
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={!value.trim() || pending}
          onClick={() => onConfirm(value)}
          data-testid="Execution.BlockedDialog.Confirm"
          sx={{ bgcolor: 'var(--color-blocked)', '&:hover': { bgcolor: 'var(--color-blocked)', filter: 'brightness(0.92)' } }}
        >
          {pending ? 'Saving…' : 'Record blocked'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
