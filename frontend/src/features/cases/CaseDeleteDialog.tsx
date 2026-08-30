import { Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
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
 * ~~`case:delete` is `OWN`-qualified for a Test Engineer (roles spec §A5), so a caller who holds the
 * permission can still be refused — for this case, because someone else wrote it. That 403 carries
 * the only sentence that explains it ("You can only delete test cases you created").~~
 *
 * **→ superseded 2026-08-14 (`V51`, §207): authorship no longer refuses anything.** CRUD on plans,
 * cases and steps belongs to every non-Viewer contributor whoever created the row, so the
 * authorship 403 is unreachable from the shipped role catalogue and that sentence is one the server
 * keeps for a future `OWN` grant rather than one this dialog will show.
 *
 * **Rendering the refusal here is still load-bearing, and the reason is now the whole reason rather
 * than the second one.** The gate upstream is an affordance layer over a *snapshot*: the permission
 * set is read once, so a grant **revoked** between load and click still lands here, and `useCan`
 * falls **open** on a failed permissions query by policy — that open-fallback is what guarantees
 * some callers reach this dialog without the permission at all. A toast at the corner, or a dialog
 * that closes on error, and the sentence explaining an unexpected refusal is gone.
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
  // The bound `reset`, not the mutation object: the object is a fresh literal on every render,
  // so depending on it would clear the refusal the dialog is displaying.
  const { reset } = remove;

  // Reopening after a refusal must not show the previous one.
  useEffect(() => {
    if (open) reset();
  }, [open, testCase.id, reset]);

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid="CaseDetail.Delete">
      <DialogHeader>
          <DialogTitle>Delete this test case?</DialogTitle>
        </DialogHeader>
      <div className="mt-4 flex flex-col gap-4">
          <p className="m-0 text-ink">
            <Identifier>{testCase.key}</Identifier> {testCase.title} will be removed from the
            repository.
          </p>
          {/*
            Not "this cannot be undone", because it is not true: the delete is soft, and past runs
            keep resolving the version they executed. What is true — and what the reader needs —
            is that nothing in the product brings it back.
          */}
          <p className="m-0 text-[13px] text-ink-2">
            Past runs keep the version they executed. Nothing here restores a deleted case.
          </p>
          {remove.isError && (
            <div data-testid="CaseDetail.Delete.Error">
            <Banner tone="bad"><span role="alert">
              {describeError(remove.error).body}
            </span></Banner>
          </div>
          )}
        </div>
      <DialogFooter>
        <Button onClick={onClose} data-testid="CaseDetail.Delete.Cancel">
          Cancel
        </Button>
        <Button
          variant="danger"
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
      </DialogFooter>
    </DialogContent>
    </Dialog>
  );
}
