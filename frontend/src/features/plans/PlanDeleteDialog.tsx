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
import { useDeletePlan } from '../../api/queries';
import { describeError } from '../../lib/describeError';

/**
 * Deleting a test plan — the confirmation, and the one place its refusal is readable.
 *
 * `CaseDeleteDialog`'s shape, for the reason that one was written that way: a blocking modal is
 * right when the interruption *is* the value, and the confirm button is verb-labeled and
 * danger-styled so the title and the button agree about what is about to happen.
 *
 * ## The refusal is the reason this is a dialog rather than a button
 *
 * The server refuses the delete with a **409** while any live run was created from the plan, naming
 * how many and pointing at Close instead. That sentence is unpredictable from the client — nothing
 * on this screen knows the plan's run count — so unlike `case:delete`'s `OWN` refusal there is no
 * gate that could disable the control ahead of it. The one place the reader can be told is here,
 * after the click, which is why the dialog stays open on error and renders the body.
 */
export function PlanDeleteDialog({
  open,
  onClose,
  projectId,
  planId,
  onDeleted,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  planId: string;
  onDeleted: () => void;
}) {
  const remove = useDeletePlan(projectId);
  // The bound `reset`, not the mutation object: the object is a fresh literal on every render,
  // so depending on it would clear the refusal the dialog is displaying.
  const { reset } = remove;

  // Reopening after a refusal must not show the previous one.
  useEffect(() => {
    if (open) reset();
  }, [open, planId, reset]);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs" data-testid="PlanStats.DeleteDialog">
      <DialogTitle>Delete this test plan?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <DialogContentText>
            The plan and its scope will be removed. The suites and cases it covers are not touched.
          </DialogContentText>
          {/*
            Not "this cannot be undone" — the delete is soft and the row survives — but nothing in
            the product brings the plan back, which is the part the reader has to act on.
          */}
          <DialogContentText variant="body2">
            A plan that runs were executed against cannot be deleted; close it instead.
          </DialogContentText>
          {remove.isError && (
            <Alert severity="error" role="alert" data-testid="PlanStats.DeleteDialog.Error">
              {describeError(remove.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="PlanStats.DeleteDialog.Cancel">
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={remove.isPending}
          data-testid="PlanStats.DeleteDialog.Confirm"
          onClick={() => {
            remove.mutate(planId, {
              onSuccess: () => {
                onDeleted();
                onClose();
              },
            });
          }}
        >
          {remove.isPending ? 'Deleting…' : 'Delete plan'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
