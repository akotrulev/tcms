import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
} from '@mui/material';
import { useState } from 'react';
import { useDeleteAutomationMapping } from '../../api/queries';
import type { AutomationMapping } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { describeError } from '../../lib/describeError';

/**
 * Removing a learned binding — confirmed by the test name, which is the row's identity.
 *
 * ## What it warns about is what happens NEXT time, not now
 *
 * Nothing that has already been recorded moves: results posted while the binding existed stay on
 * the case they landed on. What changes is the next build — the test goes back to being unmatched
 * and records **no result at all**, silently, unless the case carries an `automationKey` or its
 * display key appears in the test name. That is the consequence somebody has to be told, because
 * the intuitive reading of "remove" is that something already visible disappears.
 *
 * ## The delete is idempotent and this dialog does not pretend otherwise
 *
 * The server answers 204 whether the row was there or not, so a second confirmation after a
 * concurrent removal succeeds rather than erroring — the same shape §118's revoke has, and the
 * reason neither dialog offers a "this may already be gone" branch.
 */

const TestId = 'Ingestions.UnbindDialog';

export function UnbindMappingDialog({
  open,
  projectId,
  mapping,
  onClose,
}: {
  open: boolean;
  projectId: string;
  mapping: AutomationMapping;
  onClose: () => void;
}) {
  const remove = useDeleteAutomationMapping(projectId);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = async () => {
    setFailure(null);
    try {
      await remove.mutateAsync(mapping.id);
      onClose();
    } catch (error) {
      setFailure(describeError(error).body);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <DialogTitle>Remove this binding?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          {failure && (
            <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
              {failure}
            </Alert>
          )}

          <DialogContentText component="div">
            <Identifier variant="strong">{mapping.testName}</Identifier> will stop resolving to{' '}
            {mapping.caseKey ? (
              <Identifier variant="strong">{mapping.caseKey}</Identifier>
            ) : (
              'the case it points at'
            )}
            .
          </DialogContentText>

          <DialogContentText>
            Results already recorded stay where they are. From the next build onward this test
            records no result at all — unless its case carries an automation key, or the case key
            appears in the test name.
          </DialogContentText>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          onClick={() => void submit()}
          disabled={remove.isPending}
          data-testid={`${TestId}.Confirm`}
        >
          {remove.isPending ? 'Removing…' : 'Remove binding'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
