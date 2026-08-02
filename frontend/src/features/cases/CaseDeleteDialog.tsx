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
import { useEffect } from 'react';
import type { TestCase } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { useDeleteCase } from '../../api/queries';
import { describeError } from '../../lib/describeError';

/**
 * Deleting a test case — the confirmation, and the one place its two refusals are readable.
 *
 * `useDeleteCase` had lived in the data module with **no consumer and no endpoint** since Phase 2;
 * this slice ships both halves. The dialog follows `DeleteSuiteDialog`'s shape for the reason that
 * one was written that way: a blocking modal is right when the interruption *is* the value, it
 * **names the case** rather than asking "are you sure", and the confirm button is verb-labeled and
 * danger-styled so the title and the button agree about what is about to happen.
 *
 * ## The failure stays on the dialog
 *
 * `case:delete` is `OWN`-qualified for a Test Engineer (roles spec §A5), so a caller who holds the
 * permission can still be refused — for this case, because someone else wrote it. That 403 carries
 * the only sentence that explains it ("You can only delete test cases you created").
 *
 * **The client can now predict that refusal and mostly does.** ~~`GET /me/permissions` answers
 * keys, not qualifiers, so the button is correctly enabled right up to the refusal.~~ → superseded:
 * §65 put the qualifier on the wire and this slice put the case's `createdBy` beside it, so the
 * Delete button on the detail screen is disabled with the reason on it rather than inviting the
 * click. This dialog is reachable only past that gate.
 *
 * **Rendering the refusal here is still load-bearing, and the reason changed rather than went
 * away.** The gate is an affordance layer over a *snapshot*: authorship is read from the case the
 * screen loaded, so a case re-authored — or a grant narrowed — between load and click still lands
 * here, and `useCan` falls **open** on a failed permissions query by policy. A toast at the corner,
 * or a dialog that closes on error, and the sentence explaining an unexpected refusal is gone.
 */
export function CaseDeleteDialog({
  open,
  onClose,
  projectId,
  testCase,
  onDeleted,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  testCase: TestCase;
  onDeleted: () => void;
}) {
  const remove = useDeleteCase(projectId);

  // Reopening after a refusal must not show the previous one.
  useEffect(() => {
    if (open) remove.reset();
    // `remove` is a stable mutation object; depending on it would reset on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, testCase.id]);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs" data-testid="CaseDetail.Delete">
      <DialogTitle>Delete this test case?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <DialogContentText>
            <Identifier>{testCase.key}</Identifier> {testCase.title} will be removed from the
            repository.
          </DialogContentText>
          {/*
            Not "this cannot be undone", because it is not true: the delete is soft, and past runs
            keep resolving the version they executed. What is true — and what the reader needs —
            is that nothing in the product brings it back.
          */}
          <DialogContentText variant="body2">
            Past runs keep the version they executed. Nothing here restores a deleted case.
          </DialogContentText>
          {remove.isError && (
            <Alert severity="error" role="alert" data-testid="CaseDetail.Delete.Error">
              {describeError(remove.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="CaseDetail.Delete.Cancel">
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={remove.isPending}
          data-testid="CaseDetail.Delete.Confirm"
          onClick={() => {
            remove.mutate(testCase.id, {
              onSuccess: () => {
                onDeleted();
                onClose();
              },
            });
          }}
        >
          {remove.isPending ? 'Deleting…' : 'Delete case'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
