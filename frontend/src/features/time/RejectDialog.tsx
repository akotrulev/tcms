import { useState } from 'react';
import { ApiError } from '../../api/client';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Textarea,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';

/**
 * Sending a fortnight back — and the note is the point of it.
 *
 * <h2>Reject is disabled until there is a note, and the server refuses one without it anyway</h2>
 *
 * `POST …/decision` answers a `400` on a `REJECT` with no note, and its sentence says why: the note
 * is *"what the person sees at the top of the unlocked fortnight"*. A rejection with nothing in it
 * is a period that came back with no way to know what to change, so the button here is disabled
 * until the box has something in it — an affordance, not the rule, which is why the server keeps
 * its own refusal and this dialog renders it if it ever arrives.
 *
 * <h2>A dialog and not a route, which is the opposite of `ConnectAssistantPage`'s call</h2>
 *
 * That screen is a route because dismissing it loses a secret that cannot be recovered. Nothing is
 * lost by dismissing this one: the period stays submitted, the queue is unchanged, and the approver
 * writes the note again. What a dialog buys is the panel staying on screen behind it — the hours
 * being rejected are what the note is *about*, and a full-page form would take them away at the
 * moment they are needed most.
 */

const TEST_ID = 'Approvals.Reject';

export function RejectDialog({
  personName,
  pending,
  error,
  onReject,
  onClose,
}: {
  personName: string;
  pending: boolean;
  error: Error | null;
  onReject: (note: string) => void;
  onClose: () => void;
}) {
  const [note, setNote] = useState('');

  const fieldErrors = error instanceof ApiError ? (error.fieldErrors ?? undefined) : undefined;

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{`Send ${personName}’s timesheet back`}</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          {error && !fieldErrors && (
            <Banner tone="bad" data-testid={`${TEST_ID}.Error`}>
              <span role="alert">{describeError(error).body}</span>
            </Banner>
          )}

          <Banner tone="neutral">
            <span>
              The period is unlocked for {personName} again, with this note visible at the top of it.
              A rejection is never silent, and never a deletion.
            </span>
          </Banner>

          <Field
            label="Why it is coming back"
            htmlFor={`${TEST_ID}-note`}
            help="Say what has to change. This is the whole of what they will have to go on."
            error={fieldErrors?.note?.join(' ')}
          >
            <Textarea
              id={`${TEST_ID}-note`}
              rows={4}
              autoFocus
              value={note}
              onChange={(event) => setNote(event.target.value)}
              placeholder="the 8h on 24 Aug is against ACME-140 but that epic was on hold that week — re-point it or drop it."
              data-testid={`${TEST_ID}.Note`}
            />
          </Field>
        </div>

        <DialogFooter className="mt-5">
          <Button size="sm" type="button" onClick={onClose} data-testid={`${TEST_ID}.Cancel`}>
            Cancel
          </Button>
          <Button
            size="sm"
            variant="danger"
            type="button"
            disabled={pending || note.trim() === ''}
            onClick={() => onReject(note.trim())}
            data-testid={`${TEST_ID}.Confirm`}
          >
            Reject
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
