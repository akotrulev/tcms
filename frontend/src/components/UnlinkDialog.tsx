import { useEffect } from 'react';
import { Banner } from './kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui';
import { describeError } from '../lib/describeError';

/**
 * Withdrawing a traceability link — the confirmation, shared by **both** ends of one act (§173).
 *
 * ## Why this is one component and not two dialogs
 *
 * A `doc_link` row is removed from the case's Traceability panel and from the documentation page's
 * block, under two different permission keys, through two different hooks. It is still **one act**,
 * and `ui-rules` §6 is explicit that one concept must not get two verbs or two behaviours depending
 * on which screen it is reached from. Until this slice neither end confirmed at all — §172 matched
 * §169's unconfirmed Remove deliberately, rather than spot-fixing one side and leaving the pair
 * disagreeing, and recorded the two as one follow-up. This is that follow-up, so it fixes the class:
 * a third link surface gets the same dialog by construction rather than by whoever writes it
 * remembering.
 *
 * ## Why a confirmation at all, on something this small
 *
 * `ui-rules` §4 takes no exception for "small" destructive actions, and this one earns the rule on
 * its own: the delete is **hard** on the server — the assertion is withdrawn, not tombstoned,
 * because a tombstoned assertion still reads as one to every count drawn off `doc_link` — and the
 * control is an icon button sitting inches from a chip somebody clicks to navigate. There is no undo
 * anywhere in the product, and re-creating the link means knowing which case it was.
 *
 * ## What the dialog owes the reader
 *
 * **The object, by name.** `target` is what the link points at, in words, and the caller resolves it
 * from whichever end it is standing at — the case end passes `describeLinkTarget`, the block end
 * passes the source's key and title. "Remove this link?" over an unnamed row is precisely the "are
 * you sure?" the rule forbids.
 *
 * **The consequence, in the caller's terms**, because the two ends genuinely differ: withdrawing a
 * `VERIFIES` link from a block changes what that block's coverage marker says, which is a visible
 * effect on a screen the reader is looking at; withdrawing an external link changes nothing but the
 * record. A shared sentence would have to be vague enough to cover both, and a vague consequence is
 * worse than none.
 *
 * The confirm button is verb-labeled and danger-styled, so the title and the button agree about what
 * is about to happen — `CaseDeleteDialog`'s shape, which is the house pattern for all of these.
 */
export function UnlinkDialog({
  open,
  onClose,
  target,
  consequence,
  onConfirm,
  pending,
  error,
  reset,
  testId,
}: {
  open: boolean;
  onClose: () => void;
  /** What the link points at, as text. Named, because "this link" is not a name. */
  target: string;
  /** What changes on the screen the reader is on. Differs per end — see the header. */
  consequence: string;
  onConfirm: () => void;
  pending: boolean;
  error: unknown;
  /** The mutation's `reset`, so a reopen does not show the previous refusal. */
  reset: () => void;
  testId: string;
}) {
  // Reopening after a refusal must not show the previous one — the rule every dialog in this
  // codebase follows since `QuarantineDialog`. Keyed on the target as well as `open`, because the
  // list mounts one dialog per row and the reader may withdraw a second link after a failure.
  useEffect(() => {
    if (open) reset();
  }, [open, target, reset]);

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid={testId}>
        <DialogHeader>
          <DialogTitle>Remove this link?</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          <p className="m-0 text-ink" data-testid={`${testId}.Target`}>
            {target}
          </p>
          <p className="m-0 text-sm text-ink-2">{consequence}</p>
          {/*
            The failure stays on the dialog rather than going to the corner: the refusal this can
            produce is a 403 or a 404 about the row the reader just clicked, and a toast detaches
            the sentence from the thing it is about — `CaseDeleteDialog` records the same call.
          */}
          {error != null && (
            <div data-testid={`${testId}.Error`}>
              <Banner tone="bad">
                <span role="alert">{describeError(error).body}</span>
              </Banner>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${testId}.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={pending}
            onClick={onConfirm}
            data-testid={`${testId}.Confirm`}
          >
            {pending ? 'Removing…' : 'Remove link'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
