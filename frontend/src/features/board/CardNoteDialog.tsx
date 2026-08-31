import { useState } from 'react';
import { usePutCardAnnotation } from '../../api/queries';
import { ApiError } from '../../api/client';
import type { BoardCard } from '../../api/types';
import { Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
  Input,
  Textarea,
} from '../../components/ui';

const TEST_ID = 'MyWork.Note';

/**
 * The viewer's own note and personal due date on one card (story B5).
 *
 * **Private to the caller, and the dialog says so where the reader can see it** — the endpoint keys
 * the row on the caller, so a teammate opening the same issue does not see this. That is the whole
 * reason `derived_item_annotation` is a separate table from the issue, and the reason this write
 * costs no permission beyond seeing the card: annotating something is not editing it.
 *
 * **It writes against the card's `sourceType`/`sourceId`, never its `id`.** The two are different
 * on a derived card by design — a review's `sourceId` names the artifact under review — and
 * `BoardService.annotationKey` keys the row on the card's *kind* and that id, so a note written
 * here lands on the same row the board reads back. A dialog that sent the card's `id` would write a
 * note nothing ever reads: the personal board's ISSUE cards carry the issue KEY there.
 *
 * A card with no `sourceId` cannot be annotated and the caller does not render this — see
 * `MyWorkPage`'s row, where the affordance is absent rather than disabled, because the reason is
 * structural rather than about this reader.
 */
export function CardNoteDialog({
  card,
  onClose,
}: {
  card: BoardCard & { sourceType: string; sourceId: string };
  onClose: () => void;
}) {
  const save = usePutCardAnnotation();
  const [note, setNote] = useState(card.annotation?.note ?? '');
  const [due, setDue] = useState(card.annotation?.personalDueDate ?? '');
  const [refused, setRefused] = useState<string | null>(null);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent data-testid={`${TEST_ID}.Dialog`}>
        <DialogTitle>{`Your note on ${card.title}`}</DialogTitle>
        <DialogDescription>
          Private to you — teammates do not see this, even on the same issue.
        </DialogDescription>

        <div className="flex flex-col gap-3">
          <div>
            <label className="mb-1 block text-[12.5px] text-ink-2" htmlFor={`${TEST_ID}-body`}>
              Note
            </label>
            <Textarea
              id={`${TEST_ID}-body`}
              rows={3}
              value={note}
              onChange={(event) => setNote(event.target.value)}
              data-testid={`${TEST_ID}.Body`}
            />
          </div>

          <div className="max-w-[220px]">
            <label className="mb-1 block text-[12.5px] text-ink-2" htmlFor={`${TEST_ID}-due`}>
              My due date
            </label>
            {/*
              Separate from the issue's own due date and never a substitute for it: the team agreed
              one date and this is the reader's own plan for it, which is why the panel that lists
              these calls it "My notes" rather than "My dates".
            */}
            <Input
              id={`${TEST_ID}-due`}
              type="date"
              value={due}
              onChange={(event) => setDue(event.target.value)}
              data-testid={`${TEST_ID}.Due`}
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
            disabled={save.isPending}
            data-testid={`${TEST_ID}.Save`}
            onClick={() => {
              setRefused(null);
              save.mutate(
                {
                  sourceType: card.sourceType,
                  sourceId: card.sourceId,
                  input: {
                    // An empty field is a cleared value, not an absent key: the reader deleting
                    // their note means the note is gone, and an omitted key means "leave it".
                    note: note.trim() === '' ? null : note,
                    personalDueDate: due === '' ? null : due,
                  },
                },
                {
                  onSuccess: onClose,
                  onError: (error) =>
                    setRefused(
                      error instanceof ApiError
                        ? error.detail
                        : "Couldn't save your note. Check your connection and try again.",
                    ),
                },
              );
            }}
          >
            {save.isPending ? 'Saving…' : 'Save note'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
