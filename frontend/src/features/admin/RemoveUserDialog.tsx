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
import { ApiError } from '../../api/client';
import type { User } from '../../api/types';
import { useRemoveUser } from '../../api/queries';
import { describeError } from '../../lib/describeError';

/**
 * Removing a person from the company — the confirmation, and the two things it has to say.
 *
 * A modal rather than the immediate button the Revoke column uses, and the difference is not
 * squeamishness: revoking a grant takes away one project, and this takes away the company. It
 * follows `CaseDeleteDialog`'s shape for the reason that one was written that way — it **names the
 * person** instead of asking "are you sure", and the confirm button is verb-labeled and
 * danger-styled so the title and the button agree about what is about to happen.
 *
 * ## The body is two sentences because two things are surprising
 *
 * Neither is inferable from "Remove". Their grants are kept and go inert, so **re-inviting them
 * restores their previous project access** — the consequence that follows from the model
 * (`CompanyMembership`) rather than from this screen, and the one that surprises an administrator
 * who assumed a removal deleted something. And their **session ends immediately**, which is what
 * an administrator offboarding somebody in a hurry actually wants to know: the answer to "are they
 * out *now*" is yes, rather than "when their token expires".
 *
 * ## The refusal stays here
 *
 * The self-removal 400 should be unreachable — the row's own button is disabled for the caller's
 * own row, where the client can predict it from the session. It is still rendered, because a
 * disable that stops working is silent otherwise, and because the sentence the server sends is the
 * only one that explains what to do instead ("ask another administrator").
 */
export function RemoveUserDialog({
  open,
  onClose,
  user,
}: {
  open: boolean;
  onClose: () => void;
  user: User;
}) {
  const remove = useRemoveUser();

  // Reopening after a refusal must not show the previous one.
  useEffect(() => {
    if (open) remove.reset();
    // `remove` is a stable mutation object; depending on it would reset on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, user.id]);

  const refusal = remove.isError
    ? remove.error instanceof ApiError && remove.error.fieldErrors?.userId
      ? remove.error.fieldErrors.userId.join(' ')
      : describeError(remove.error).body
    : null;

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs" data-testid="Admin.Users.Remove">
      <DialogTitle>Remove this person from the company?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <DialogContentText>
            {user.displayName} will lose access to every project here, and their session ends
            immediately.
          </DialogContentText>
          <DialogContentText variant="body2">
            Their roles are kept but stop applying, so inviting them back restores the projects they
            had. Nothing they wrote is deleted.
          </DialogContentText>
          {refusal && (
            <Alert severity="error" role="alert" data-testid="Admin.Users.Remove.Error">
              {refusal}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="Admin.Users.Remove.Cancel">
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={remove.isPending}
          data-testid="Admin.Users.Remove.Confirm"
          onClick={() => {
            remove.mutate(user.id, { onSuccess: () => onClose() });
          }}
        >
          {remove.isPending ? 'Removing…' : 'Remove person'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
