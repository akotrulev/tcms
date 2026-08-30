/**
 * Create or edit a library condition, from inside the case editor (§95).
 *
 * ## The edit is the only mutation in this app whose blast radius is not the thing being edited
 *
 * An attachment is a reference, so saving here changes **every case and suite attaching this
 * entry**, with no prompt elsewhere and no fork. `ui-rules` §4 requires a confirmation that
 * *restates the action and names the specific object* for a destructive action; an edit that
 * silently rewrites three hundred cases earns the same treatment, so the impact is stated **on the
 * dialog, before the save**, from the two counts the server derives.
 *
 * **No fork option.** "Change everywhere / copy for this case" was refused on the server's terms:
 * the fork is what turned the shared-step library into a pile of near-duplicates nobody could
 * rank. An author who wants different text creates a different condition — which is what the
 * create mode of this same dialog is for.
 *
 * ## The two counts are never added
 *
 * `usageCount` is cases attaching the entry directly; `suiteUsageCount` is suites, each of which
 * propagates to every case filed in it. Their sum is a number about nothing, and this dialog is
 * where an author decides from it — so the suite figure is worded as what it is rather than
 * converted into a case count the client has no basis to compute.
 */
import { useState } from 'react';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Textarea,
} from '../../components/ui';
import { Banner } from '../../components/kit';
import { useCreateCondition, useUpdateCondition } from '../../api/queries';
import type { Condition } from '../../api/types';
import { ApiError } from '../../api/client';
import { cn } from '../../lib/cn';
import { describeError } from '../../lib/describeError';
import { impactSummary } from '../../lib/conditions';

const TYPES = [
  { value: 'PRE', label: 'Precondition' },
  { value: 'POST', label: 'Postcondition' },
  { value: 'BOTH', label: 'Both' },
] as const;

export function ConditionEditDialog({
  projectId,
  open,
  /** The entry being edited, or null to create one. */
  condition,
  defaultType,
  onClose,
  onCreated,
}: {
  projectId: string;
  open: boolean;
  condition: Condition | null;
  defaultType: 'PRE' | 'POST';
  onClose: () => void;
  /** Called with the new entry's id so the panel can attach what the author just wrote. */
  onCreated: (created: Condition) => void;
}) {
  const editing = condition !== null;

  const [title, setTitle] = useState(condition?.title ?? '');
  const [type, setType] = useState<string>(condition?.type ?? defaultType);
  const [body, setBody] = useState((condition?.body ?? []).join('\n'));
  const [errors, setErrors] = useState<Record<string, string[]>>({});
  const [rootError, setRootError] = useState<string | null>(null);

  const create = useCreateCondition(projectId);
  const update = useUpdateCondition(projectId);
  const pending = create.isPending || update.isPending;

  const submit = async () => {
    setErrors({});
    setRootError(null);

    // One action per line. A condition body is a procedure, so the positions are the content —
    // the same shape the shared-step editor takes, and the server drops blank lines and refuses an
    // all-blank body, so a trailing newline here is harmless by design rather than by luck.
    const input = { title, type: type as Condition['type'], body: body.split('\n') };

    try {
      if (editing) {
        await update.mutateAsync({
          conditionId: condition.id,
          // The version the author read the IMPACT FIGURES against, not merely the text. If
          // somebody else has edited the entry since, those figures may be wrong too, so the
          // precondition is protecting the decision and not just the fields.
          lockVersion: condition.lockVersion,
          input,
        });
      } else {
        const created = await create.mutateAsync(input);
        onCreated(created);
      }
      onClose();
    } catch (error) {
      // The dialog's own `applyServerError`, matching `BulkActionDialog`'s: a 400's per-field
      // messages land on their controls, and anything else — a 409 above all — becomes the root
      // alert, because "reload the condition and reapply" is an instruction about this dialog and
      // not a toast.
      if (error instanceof ApiError && error.fieldErrors) {
        setErrors(error.fieldErrors);
        return;
      }
      setRootError(describeError(error).body);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit condition' : 'New condition'}</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          {editing && (
            /*
              The impact, stated before the save and naming the object — ui-rules §4. `warn` rather
              than a neutral note because the consequence is the point: this is not a remark about
              where the entry is used, it is what the author is about to change.
            */
            <div data-testid="CaseEditor.Condition.Impact">
              <Banner tone="warn">
                Saving changes “{condition.title}” everywhere it is used. {impactSummary(condition)}.
              </Banner>
            </div>
          )}

          {rootError && (
            <div data-testid="CaseEditor.Condition.Error">
              <Banner tone="bad">
                <span role="alert">{rootError}</span>
              </Banner>
            </div>
          )}

          <Field label="Title" htmlFor="condition-title" error={errors.title?.[0]}>
            <Input
              id="condition-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              data-testid="CaseEditor.Condition.Title"
            />
          </Field>

          <Field
            label="Used as"
            htmlFor="condition-type"
            error={errors.type?.[0]}
            help="“Both” appears in the precondition and postcondition pickers."
          >
            <select
              id="condition-type"
              value={type}
              onChange={(event) => setType(event.target.value)}
              data-testid="CaseEditor.Condition.Type"
              className="w-full cursor-pointer rounded-[8px] border border-line bg-field px-3 py-1.5 text-[13px] text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]"
            >
              {TYPES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>

          <Field
            label="Actions"
            htmlFor="condition-body"
            error={errors.body?.[0]}
            help="One action per line — the order is the procedure."
          >
            <Textarea
              id="condition-body"
              rows={3}
              value={body}
              onChange={(event) => setBody(event.target.value)}
              data-testid="CaseEditor.Condition.Body"
            />
          </Field>
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid="CaseEditor.Condition.Cancel">
            Cancel
          </Button>
          {/*
            Verb-labeled, and on an edit it names the consequence rather than saying "Save" —
            ui-rules §4's rule about a warning title with a neutral confirm button sending a mixed
            signal. The pending flag is the double-submit guard.
          */}
          <Button
            variant={editing ? 'danger' : 'primary'}
            disabled={pending}
            onClick={submit}
            data-testid="CaseEditor.Condition.Save"
          >
            {pending ? 'Saving…' : editing ? 'Change everywhere' : 'Create condition'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** A label above its control, its refusal — or its help — beneath it. One column, always. */
function Field({
  label,
  htmlFor,
  help,
  error,
  children,
}: {
  label: string;
  htmlFor: string;
  help?: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label htmlFor={htmlFor} className="mb-1 block text-[13px] font-medium text-ink-2">
        {label}
      </label>
      {children}
      {(error ?? help) && (
        <p
          role={error ? 'alert' : undefined}
          className={cn('m-0 mt-1 text-[12px]', error ? 'text-[color:var(--color-failed)]' : 'text-ink-4')}
        >
          {error ?? help}
        </p>
      )}
    </div>
  );
}
