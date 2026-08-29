import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useDeleteTeam } from '../../api/queries';
import type { Team } from '../../api/types';
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
import { plural } from '../../lib/format';
import { describeError } from '../../lib/describeError';

/**
 * Deleting a team, and — more usefully — reporting why it cannot be.
 *
 * ## The 409 is the reason this is a dialog rather than a menu item
 *
 * `role_assignment.team_id` cascades, so a delete that went through would revoke every grant made
 * through the team **silently**. The server refuses instead, with counts: "3 role grants are made
 * through this team", "1 team sits below this one". That refusal is the screen's real content —
 * a toast saying "could not delete" would leave an administrator clicking through the grant list
 * to work out what is holding it.
 *
 * `PermissionGate` cannot predict it: whether a team holds grants is a fact about the row, not
 * about the caller's permissions, so it is surfaced where it happens rather than pretended away by
 * disabling the button — the same reading `useRevokeRole` records about the self-lockout refusal.
 *
 * ## Members are not a blocker, and the copy says so
 *
 * Membership confers nothing on its own, so a team full of people deletes cleanly and those people
 * lose nothing. Stating it stops an administrator emptying the team first for no reason.
 */

const TestId = 'Admin.TeamDelete';

export function DeleteTeamDialog({
  open,
  team,
  onClose,
}: {
  open: boolean;
  team: Team;
  onClose: () => void;
}) {
  const remove = useDeleteTeam();
  const [blockers, setBlockers] = useState<string[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = async () => {
    setBlockers(null);
    setFailure(null);
    try {
      await remove.mutateAsync(team.id);
      onClose();
    } catch (error) {
      // The 409 carries `errors.grants` / `errors.children`, each already a full sentence naming a
      // count. Rendered as they arrive rather than re-worded here — the server counted, and a
      // second phrasing on this side is a copy that drifts.
      if (error instanceof ApiError && error.fieldErrors) {
        const messages = Object.values(error.fieldErrors).flat();
        if (messages.length > 0) {
          setBlockers(messages);
          return;
        }
      }
      setFailure(describeError(error).body);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
        <DialogHeader>
          <DialogTitle>Delete {team.name}?</DialogTitle>
          <DialogDescription>
            The team is removed from the org chart.{' '}
            {team.members.length > 0
              ? `Its ${plural(team.members.length, 'member')} stay in the company and lose nothing — being in a team grants nothing on its own.`
              : 'Nobody is in it.'}
          </DialogDescription>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-3">
          {failure && (
            <div data-testid={`${TestId}.Error`}>
              <Banner tone="bad">
                <span role="alert">{failure}</span>
              </Banner>
            </div>
          )}

          {blockers && (
            <div data-testid={`${TestId}.Blocked`}>
              <Banner tone="warn">
                <ul role="alert" className="m-0 list-disc pl-4">
                  {blockers.map((message) => (
                    <li key={message}>{message}</li>
                  ))}
                </ul>
              </Banner>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="danger"
            onClick={() => void submit()}
            disabled={remove.isPending}
            data-testid={`${TestId}.Confirm`}
          >
            {remove.isPending ? 'Deleting…' : 'Delete team'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
