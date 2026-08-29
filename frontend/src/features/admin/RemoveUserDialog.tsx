import { useEffect } from 'react';
import { ApiError } from '../../api/client';
import type { User } from '../../api/types';
import { useRemoveUser } from '../../api/queries';
import { Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
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
  // The bound `reset`, not the mutation object: the object is a fresh literal on every render,
  // so depending on it would clear the refusal the dialog is displaying.
  const { reset } = remove;

  // Reopening after a refusal must not show the previous one.
  useEffect(() => {
    if (open) reset();
  }, [open, user.id, reset]);

  const refusal = remove.isError
    ? remove.error instanceof ApiError && remove.error.fieldErrors?.userId
      ? remove.error.fieldErrors.userId.join(' ')
      : describeError(remove.error).body
    : null;

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid="Admin.Users.Remove">
        <DialogHeader>
          <DialogTitle>Remove this person from the company?</DialogTitle>
          <DialogDescription>
            {user.displayName} will lose access to every project here, and their session ends
            immediately.
          </DialogDescription>
        </DialogHeader>

        <p className="mt-3 text-[12.5px] leading-[1.55] text-ink-3">
          Their roles are kept but stop applying, so inviting them back restores the projects they
          had. Nothing they wrote is deleted.
        </p>

        {refusal && (
          <Banner tone="bad" className="mt-3" data-testid="Admin.Users.Remove.Error">
            <span role="alert">{refusal}</span>
          </Banner>
        )}

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid="Admin.Users.Remove.Cancel">
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={remove.isPending}
            data-testid="Admin.Users.Remove.Confirm"
            onClick={() => {
              remove.mutate(user.id, { onSuccess: () => onClose() });
            }}
          >
            {remove.isPending ? 'Removing…' : 'Remove person'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
