import { useState } from 'react';
import type { CaseConditionRef } from '../../api/types';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Checkbox,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Textarea,
} from '../../components/ui';

/**
 * Blocked is the one result that requires an explanation, because it is a statement about the
 * environment rather than about the software. Recording it without saying what blocked you
 * produces a row nobody can act on — and the pre-filled unmet preconditions mean the tester
 * usually doesn't have to type anything.
 *
 * <h3>Two callers, one dialog, and the difference is who holds the checklist</h3>
 *
 * Moved out of `ExecutionPage` in §100, when Grid mode became a second caller.
 *
 *   - **Focus/Split** pass `unmet` — the tester has already unticked the preconditions that did
 *     not hold, on the checklist that is on screen beside the steps. The dialog states them back.
 *   - **Grid** has no checklist on screen, so it passes `conditions` instead and the dialog
 *     *renders* one. This is what keeps the rule intact in the third mode: Grid's `B` is not a
 *     one-click write, because Blocked has to say which precondition failed and Grid does not
 *     otherwise know.
 *
 * A case with no preconditions is a real and ordinary state in both callers — then there is no
 * checklist to show and the free-text reason is the whole of it.
 *
 * <h3>Why this one stayed a dialog when the failure panel did not (007 T12)</h3>
 *
 * `renders/03-execution.html` frame B replaces a modal with a rail section, and the runner's
 * *failure* capture follows it: the step being described stays on screen. Blocked is the opposite
 * shape. Grid mode is a second caller with **no rail at all**, and the checklist is a gate rather
 * than a description — the point is that the tester cannot record Blocked while looking past the
 * question. Two spellings of Blocked, one per mode, would be the drift the component was extracted
 * to prevent.
 */
export function BlockedDialog({
  open,
  onClose,
  unmet = [],
  conditions,
  pending = false,
  onConfirm,
}: {
  open: boolean;
  onClose: () => void;
  /** Titles the caller already knows did not hold (Focus/Split). */
  unmet?: string[];
  /** The case's preconditions, when the caller has no checklist of its own (Grid). */
  conditions?: CaseConditionRef[];
  pending?: boolean;
  onConfirm: (reason: string) => void;
}) {
  // Which conditions the tester has ticked OFF here — only used when this dialog owns the
  // checklist. Keyed by id, absent meaning "still holds", so a case whose conditions change
  // between renders cannot leave a stale `true` behind.
  const [failedHere, setFailedHere] = useState<Record<string, boolean>>({});
  const [reason, setReason] = useState('');

  const ownChecklist = conditions !== undefined;
  const failedTitles = ownChecklist
    ? (conditions ?? []).filter((condition) => failedHere[condition.id]).map((c) => c.title)
    : unmet;

  const prefilled = failedTitles.length
    ? `Blocked — these preconditions did not hold:\n${failedTitles.map((title) => `- ${title}`).join('\n')}`
    : '';
  // The typed reason wins once there is one, so the prefill is a starting point rather than a
  // value that keeps overwriting what the tester wrote.
  const value = reason || prefilled;

  const close = () => {
    setFailedHere({});
    setReason('');
    onClose();
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && close()}>
      <DialogContent data-testid="Execution.BlockedDialog">
        <DialogHeader>
          <DialogTitle>Record this as blocked</DialogTitle>
          <DialogDescription>
            Blocked means the test couldn&rsquo;t be run — not that the software failed. It&rsquo;s
            excluded from the pass rate and shown separately, so say what stopped you.
          </DialogDescription>
        </DialogHeader>

        {ownChecklist && (conditions ?? []).length > 0 && (
          <div className="mt-4 flex flex-col gap-2" data-testid="Execution.BlockedDialog.Conditions">
            <p className="m-0 text-[12px] text-ink-4">Tick the preconditions that did not hold</p>
            {(conditions ?? []).map((condition) => (
              <div key={condition.id} className="flex items-start gap-2">
                {/*
                  ~~`inputTestId`, not a bare `data-testid`: on a MUI Checkbox that landed on the
                  wrapper span, where `toBeChecked` could not see it — §62's lesson.~~ →
                  **superseded by 007 T12 (D3)**: this checkbox is the control, so the attribute
                  goes straight on it and the workaround's premise is gone with the library.
                */}
                <Checkbox
                  id={`Execution.BlockedDialog.Condition.${condition.id}`}
                  checked={failedHere[condition.id] ?? false}
                  onCheckedChange={(checked) =>
                    setFailedHere((previous) => ({ ...previous, [condition.id]: checked === true }))
                  }
                  aria-label={condition.title}
                  data-testid="Execution.BlockedDialog.Condition"
                  className="mt-0.5"
                />
                <label
                  htmlFor={`Execution.BlockedDialog.Condition.${condition.id}`}
                  className="cursor-pointer text-[13px] text-ink-2"
                >
                  {condition.title}
                </label>
              </div>
            ))}
          </div>
        )}

        <div className="mt-4">
          <Field label="What blocked this test" htmlFor="Execution.BlockedDialog.Reason">
            <Textarea
              id="Execution.BlockedDialog.Reason"
              rows={3}
              value={value}
              onChange={(event) => setReason(event.target.value)}
              data-testid="Execution.BlockedDialog.ReasonField"
            />
          </Field>
        </div>

        <DialogFooter>
          <Button onClick={close} data-testid="Execution.BlockedDialog.Cancel">
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={!value.trim() || pending}
            onClick={() => onConfirm(value)}
            data-testid="Execution.BlockedDialog.Confirm"
            style={{ backgroundColor: 'var(--color-blocked)' }}
          >
            {pending ? 'Saving…' : 'Record blocked'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
