import { useState } from 'react';
import { useCreateIssueComment, useTransitionIssue } from '../../api/queries';
import type { BoardIssueCard, IssueStatusRef } from '../../api/types';
import { Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
  Input,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';

const TEST_ID = 'Board.Block';

/**
 * Blocking a card: **one line of reason, then the transition** (021 §2.7).
 *
 * ## The reason is a comment, and the comment goes first
 *
 * There is no "blocked reason" column on the contract and 021 did not add one — the reason is an
 * ordinary `IssueComment`, which is where the rest of the conversation about this issue already is
 * and where the person who unblocks it will look. So the dialog posts the comment and *then*
 * transitions, in that order and never the other way: a card that lands in `Blocked` with no reason
 * attached is the state this dialog exists to prevent, and a failed comment write with the move
 * already made would produce exactly one. A failed *move* after a successful comment leaves a note
 * saying why on a card that did not move, which is untidy and true; the reverse is tidy and a lie.
 *
 * ## The target status is read off the card, never off a name
 *
 * `blocking` is a flag on one status per project (D-b) and the project named that status itself. So
 * the entry is the member of `card.allowedTransitions` carrying `blocking: true` — which also means
 * the dialog cannot be opened for a card that may not be blocked, because there is no target to
 * open it with. Matching on `name === 'Blocked'` would work on the seeded project and on no other.
 *
 * ## Failures are `describeError`'s, like every other write
 *
 * Including the 422 the workflow endpoint answers when two statuses are marked blocking: it is the
 * guard against a stale tab, not a path an ordinary session reaches, and dressing it as a field
 * error on a dialog with no such field would be inventing a control to hang it on.
 */
export function BlockDialog({
  card,
  target,
  onClose,
}: {
  card: BoardIssueCard;
  /** The project's `blocking` status, taken from this card's own `allowedTransitions`. */
  target: IssueStatusRef;
  onClose: () => void;
}) {
  const comment = useCreateIssueComment(card.key);
  const transition = useTransitionIssue();
  const [reason, setReason] = useState('');
  const [refused, setRefused] = useState<string | null>(null);

  const pending = comment.isPending || transition.isPending;
  const blank = reason.trim() === '';

  const submit = async () => {
    setRefused(null);
    try {
      await comment.mutateAsync({ body: reason.trim() });
      await transition.mutateAsync({ key: card.key, statusId: target.id });
      onClose();
    } catch (error) {
      const described = describeError(error);
      setRefused(described.body);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent data-testid={`${TEST_ID}.Dialog`}>
        <DialogTitle>{`Block ${card.key}`}</DialogTitle>
        <DialogDescription>
          {`It moves to ${target.name}, and remembers ${card.status.name} as where it came from — so `
            + 'unblocking puts it back rather than asking you to choose.'}
        </DialogDescription>

        <div className="flex flex-col gap-3">
          <div>
            <label className="mb-1 block text-[12.5px] text-ink-2" htmlFor={`${TEST_ID}-reason`}>
              What is it waiting on?
            </label>
            {/*
              One line, not a textarea: the render asks for a sentence a colleague can act on, and a
              box that invites five paragraphs is a box people leave empty. It is posted as an
              ordinary comment, so anything longer belongs on the issue where it can be replied to.
            */}
            <Input
              id={`${TEST_ID}-reason`}
              value={reason}
              maxLength={200}
              placeholder="Waiting on the provider's sandbox credentials"
              onChange={(event) => setReason(event.target.value)}
              data-testid={`${TEST_ID}.Reason`}
            />
          </div>

          {refused && (
            <div role="alert">
              <Banner tone="bad" data-testid={`${TEST_ID}.Refused`}>
                <span>{refused}</span>
              </Banner>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button onClick={onClose} data-testid={`${TEST_ID}.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={pending || blank}
            title={blank ? 'A blocked card says what it is waiting on.' : undefined}
            data-testid={`${TEST_ID}.Confirm`}
            onClick={() => void submit()}
          >
            {pending ? 'Blocking…' : `Block · ${target.name}`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
