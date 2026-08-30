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
import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { useQuarantineCase } from '../../api/queries';
import type { TestCase } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { describeError } from '../../lib/describeError';

/** The longest reason the contract accepts (`CaseQuarantineInput.reason.maxLength`). */
const REASON_MAX = 1000;

/**
 * Taking a flaky test out of the gate, and putting it back (§121 phase 4).
 *
 * ## One component, two directions, and they are not a toggle
 *
 * `ui-rules` §4 forbids swapping a control's meaning in place, and the endpoint is a `PUT` carrying
 * an **absolute state** for the same reason — a toggle derived from what the screen last read is
 * how a stale cache releases a quarantine nobody asked to release. So `quarantining` is a prop
 * decided by the caller from the row it is looking at, the dialog states which direction it is
 * going in its title, and the two confirm buttons carry different verbs.
 *
 * They share a component because they share the refusal path, the pending state and the object they
 * name — two files would be `ui-rules` §8's renderer divergence in miniature, which is exactly how
 * §104's two grids ended up with opposite answers about where a link goes.
 *
 * ## The reason is required in one direction only
 *
 * Quarantining without a reason is refused; releasing takes none. That asymmetry is the contract's
 * and the database's (`case_flake_quarantine_complete`), and the argument is on the endpoint:
 * releasing returns a test to the default, and the exception is the thing that needs justifying. A
 * quarantine with no reason is debt nobody can clear — the board card says a test is excluded and
 * cannot say why or by whom.
 *
 * **The client refuses a blank reason itself rather than sending it**, because the answer is
 * certain and a round trip to be told so is a worse version of the same message. The server's field
 * error is still landed on the field: the check exists twice on purpose, and the server's is the
 * one that binds.
 *
 * ## It closes on success, deliberately unlike §118's reveal
 *
 * Nothing here is unrecoverable — the panel behind it renders the new state, and the decision can be
 * reversed by the opposite direction of this same dialog. §118's token dialog stays open because it
 * shows a secret that can never be produced again; copying that behaviour to every dialog in the app
 * was never the lesson.
 */
export function QuarantineDialog({
  open,
  onClose,
  projectId,
  testCase,
  quarantining,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  testCase: TestCase;
  /** `true` takes the test out of the gate, `false` puts it back. Never derived from the row here. */
  quarantining: boolean;
}) {
  const write = useQuarantineCase(projectId, testCase.id);
  // The bound `reset`, not the mutation object: the object is a fresh literal on every render,
  // so depending on it would clear the refusal the dialog is displaying.
  const { reset } = write;
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);

  // Reopening after a refusal must not show the previous one, and reopening in the other direction
  // must not carry the reason typed for the first. `CaseDeleteDialog`'s rule, plus the field.
  useEffect(() => {
    if (open) {
      reset();
      setReason('');
      setTouched(false);
    }
  }, [open, testCase.id, quarantining, reset]);

  const blank = reason.trim().length === 0;
  const serverError =
    write.error instanceof ApiError ? write.error.fieldErrors?.reason?.join(' ') : undefined;
  // The client's refusal only after the field has been touched or a submit attempted, so the dialog
  // does not open already complaining about a field nobody has reached yet.
  const reasonError = quarantining
    ? (serverError ?? (touched && blank ? 'Say why this test is being quarantined.' : undefined))
    : undefined;

  const submit = () => {
    setTouched(true);
    if (quarantining && blank) return;
    write.mutate(
      // `reason` is ignored by the server when releasing, and is left off rather than sent empty —
      // a field the endpoint documents as ignored is one a request should not carry an opinion in.
      quarantining ? { quarantined: true, reason: reason.trim() } : { quarantined: false },
      { onSuccess: onClose },
    );
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid="CaseDetail.Quarantine">
      <DialogHeader>
          <DialogTitle>
        {quarantining ? 'Quarantine this test?' : 'Put this test back into the gate?'}
      </DialogTitle>
        </DialogHeader>
      <div className="mt-4 flex flex-col gap-4">
          <p className="m-0 text-ink">
            <Identifier>{testCase.key}</Identifier> {testCase.title}
          </p>

          {quarantining ? (
            <>
              {/*
                Says what quarantine does AND what it does not do. Nothing downstream reads the flag
                yet — it does not exclude the case from a run, a report or a pass rate — and a
                reader who assumes otherwise stops watching a test that is still gating. §6e's
                argument in one sentence: a denominator that moved without saying so is worse than
                no figure at all.
              */}
              <p className="m-0 text-[13px] text-ink-2">
                The test stays in every run and every report, and its results still count. Quarantine
                records the decision to stop treating its failures as a signal — it is a note for
                the people reading them, not an exclusion.
              </p>
              <Field
                label="Why"
                htmlFor="quarantine-reason"
                error={reasonError}
                help="Recorded against the case and named on the board card. Somebody who did not make this decision has to be able to evaluate it."
              >
                <Textarea
                  id="quarantine-reason"
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  onBlur={() => setTouched(true)}
                  required
                  rows={2}
                  autoFocus
                  aria-invalid={reasonError !== undefined}
                  maxLength={REASON_MAX}
                  data-testid="CaseDetail.Quarantine.Reason"
                />
              </Field>
            </>
          ) : (
            <p className="m-0 text-[13px] text-ink-2">
              The reason recorded with the quarantine is cleared. It stays in the audit log — the
              row keeps only the current decision, which is why the log is where the history of an
              exclusion lives.
            </p>
          )}

          {/*
            Anything that is not the reason field's own error. A 403 lands here — the gate below is
            an affordance over a snapshot, so a grant narrowed between load and click arrives as a
            refusal on a screen that offered the action.
          */}
          {write.isError && serverError === undefined && (
            <div data-testid="CaseDetail.Quarantine.Error">
            <Banner tone="bad"><span role="alert">
              {describeError(write.error).body}
            </span></Banner>
          </div>
          )}
        </div>
      <DialogFooter>
        <Button onClick={onClose} data-testid="CaseDetail.Quarantine.Cancel">
          Cancel
        </Button>
        <Button
          variant="primary"
          // Danger-styled in one direction only: `ui-rules` §4 wants the button and the title to
          // agree, and releasing a test back into the gate is the safe direction — colouring it red
          // would make the two acts look equally grave and teach the colour to mean nothing.
          color={quarantining ? 'warning' : 'primary'}
          disabled={write.isPending}
          onClick={submit}
          data-testid="CaseDetail.Quarantine.Confirm"
        >
          {write.isPending
            ? 'Saving…'
            : quarantining
              ? 'Quarantine test'
              : 'Return to the gate'}
        </Button>
      </DialogFooter>
    </DialogContent>
    </Dialog>
  );
}
