import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateAllocation, useProjectMembers, useUpdateAllocation } from '../../api/queries';
import type { Allocation, AllocationPatch } from '../../api/types';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';

/**
 * Allocating somebody to this project at a percentage for a range — and editing that allocation.
 *
 * <h2>The 409 is shown INLINE, and that is the decision this dialog exists to get right</h2>
 *
 * Two allocations of the same person to the same project over overlapping days are refused rather
 * than merged (017 D-n), and the server's refusal **names the range it collided with**. That makes
 * the conflict a fact about a field the person has this second finished typing — so it belongs
 * beside the form, where they can move the date, and not in a toast that scrolls away while they
 * are looking at the dates it is about.
 *
 * The hooks carry `meta.handled`, which is what stops the global error handler raising the
 * notification as well; without it the reader would get the same refusal twice, once where it is
 * useful and once where it is not.
 *
 * <h2>Every other refusal still goes to the form-level banner</h2>
 *
 * A `400` lands on the control that caused it through `Problem.errors`, a `403` and anything else
 * through `describeError`, which is the one place in this app an error becomes user-facing copy.
 * Only the 409 gets its own treatment, and only because only it names a value on screen.
 *
 * <h2>The person is chosen once and never changed</h2>
 *
 * `AllocationPatch` carries no `personId` — moving an allocation from one person to another is
 * deleting one and creating another, and doing it as an edit would slip past the overlap check,
 * which keys on the person and the project. So the control is **disabled** on an edit rather than
 * absent (§31 applied to a field), and the patch is a true delta of the three fields that can move.
 *
 * <h2>The range picker is free-form, which is what makes the screen's fixed window survivable</h2>
 *
 * `/projects/:projectId/staffing` shows the server's default window and offers no picker, so an
 * allocation typed here for next spring is one the table will not list. That is honest rather than
 * hidden: the subtitle names the window the table covers, and the alternative — constraining these
 * two date fields to it — would stop somebody planning ahead, which is most of what staffing is.
 */

const MAX_NOTE = 500;

/**
 * Mirrors `AllocationInput`'s own bounds, and only the ones the client can actually answer.
 *
 * `1–100` is the contract's range: zero is not an allocation and over a hundred on one project is
 * not a thing the timeline can draw. Whether the result *overlaps* is a fact only the server has,
 * and it arrives on this form as the 409 the header is about — a client-side overlap check would be
 * a second opinion that could refuse a range the server would take.
 */
const AllocationSchema = z
  .object({
    personId: z.string().min(1, 'Choose somebody to allocate.'),
    percent: z.coerce
      .number()
      .int('Whole percentages only.')
      .min(1, 'At least 1%.')
      .max(100, 'At most 100% of somebody on one project.'),
    startsOn: z.string().min(1, 'Enter a start date.'),
    endsOn: z.string().min(1, 'Enter an end date.'),
    note: z.string().max(MAX_NOTE).optional(),
  })
  // Both dates are inclusive everywhere in this module, so a one-day allocation has `from === to`
  // and only `to < from` is wrong. Checked here rather than left to the server because it is the
  // one range rule that needs no knowledge the browser lacks.
  .refine((values) => values.endsOn >= values.startsOn, {
    path: ['endsOn'],
    message: 'The end date cannot be before the start date.',
  });

type AllocationFormInput = z.input<typeof AllocationSchema>;
type AllocationFormValues = z.output<typeof AllocationSchema>;

type FormField = 'personId' | 'percent' | 'startsOn' | 'endsOn' | 'note';

const FIELDS: readonly FormField[] = ['personId', 'percent', 'startsOn', 'endsOn', 'note'];

const TestId = 'ProjectStaffing.Dialog';

export function AllocationDialog({
  projectId,
  allocation,
  onClose,
}: {
  projectId: string;
  /** The allocation being edited, or `undefined` to create one. */
  allocation?: Allocation;
  onClose: () => void;
}) {
  const editing = allocation !== undefined;
  const members = useProjectMembers(projectId, true);
  const create = useCreateAllocation(projectId);
  const update = useUpdateAllocation(projectId);
  const save = editing ? update : create;

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<AllocationFormInput, unknown, AllocationFormValues>({
    resolver: zodResolver(AllocationSchema),
    defaultValues: {
      personId: allocation?.personId ?? '',
      percent: allocation?.percent ?? 50,
      startsOn: allocation?.startsOn ?? '',
      endsOn: allocation?.endsOn ?? '',
      note: allocation?.note ?? '',
    },
    mode: 'onBlur',
  });

  // Focus only, no `reset` — the dialog is conditionally rendered and mounts fresh each time it
  // opens, and resetting here is what wiped a sibling dialog's server errors in §30.
  useEffect(() => {
    setFocus(editing ? 'percent' : 'personId');
  }, [editing, setFocus]);

  const submit = handleSubmit(async (values) => {
    const note = values.note?.trim() || null;
    try {
      if (allocation) {
        /*
          A TRUE delta — only what moved. `personId` is not here because the patch schema has none:
          see the file header on why moving an allocation between people is a delete and a create.
        */
        const patch: AllocationPatch = {};
        if (values.percent !== allocation.percent) patch.percent = values.percent;
        if (values.startsOn !== allocation.startsOn) patch.startsOn = values.startsOn;
        if (values.endsOn !== allocation.endsOn) patch.endsOn = values.endsOn;
        if (note !== allocation.note) patch.note = note;
        await update.mutateAsync({ allocationId: allocation.id, patch });
      } else {
        await create.mutateAsync({
          personId: values.personId,
          percent: values.percent,
          startsOn: values.startsOn,
          endsOn: values.endsOn,
          note,
        });
      }
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  /*
    THE inline conflict. Read off the live mutation rather than pushed into `errors.root`, because
    it is not a form error: it is the server describing a row that already exists, and it carries
    both halves — the kind (`title`) and this instance's dates (`detail`). `applyServerError` below
    deliberately leaves a 409 alone so it cannot also land in the generic banner.
  */
  const conflict =
    save.error instanceof ApiError && save.error.status === 409 ? save.error : null;

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
        <form onSubmit={submit} noValidate aria-busy={save.isPending}>
          <DialogHeader>
            <DialogTitle>
              {editing ?
                `Edit ${allocation.person.displayName}’s allocation`
              : 'Allocate somebody to this project'}
            </DialogTitle>
          </DialogHeader>

          <div className="mt-4 flex flex-col gap-4">
            {conflict && (
              <div data-testid={`${TestId}.Conflict`}>
                <Banner tone="bad">
                  <div className="min-w-0" role="alert">
                    <p className="m-0 text-[13px] font-semibold text-ink">
                      {conflict.title ?? 'Already allocated over those days'}
                    </p>
                    {/*
                      The server's own sentence, which NAMES THE RANGE it collided with. Rewriting it
                      here would be a second copy of a fact only the server has, and one that goes
                      stale the day the overlap rule changes.
                    */}
                    <p className="mt-1 mb-0">{conflict.detail}</p>
                  </div>
                </Banner>
              </div>
            )}

            {errors.root && (
              <div data-testid={`${TestId}.Error`}>
                <Banner tone="bad">
                  <span role="alert">{errors.root.message}</span>
                </Banner>
              </div>
            )}

            <Field
              label="Person"
              htmlFor="allocation-person"
              error={errors.personId?.message}
              help={
                editing ?
                  'An allocation cannot move between people — that is a removal and a new one, because the overlap rule keys on the person and the project.'
                : 'Anybody with a grant on this project. Someone who is not here needs the grant first, on Access & roles.'
              }
            >
              <NativeSelect
                id="allocation-person"
                required
                // DISABLED, not omitted, on an edit — §31 applied to a field. The value still
                // renders so the reader can see who this row is about.
                disabled={editing}
                aria-invalid={!!errors.personId}
                data-testid={`${TestId}.Person`}
                {...register('personId')}
              >
                <option value="">Choose somebody…</option>
                {editing && !(members.data ?? []).some((m) => m.id === allocation.personId) && (
                  // The member read is gated on `project:read` and may not have answered yet, or
                  // may not list somebody whose grant was revoked after they were allocated. The
                  // row is still theirs, so the select must still be able to show the name.
                  <option value={allocation.personId}>{allocation.person.displayName}</option>
                )}
                {(members.data ?? []).map((member) => (
                  <option key={member.id} value={member.id}>
                    {member.displayName}
                  </option>
                ))}
              </NativeSelect>
            </Field>

            <div className="flex gap-4">
              <Field
                label="Share of their week"
                htmlFor="allocation-percent"
                error={errors.percent?.message}
                help="1–100. What this project claims, not what is left."
              >
                <Input
                  id="allocation-percent"
                  type="number"
                  min={1}
                  max={100}
                  required
                  className="w-[110px] text-right font-mono tabular-nums"
                  aria-invalid={!!errors.percent}
                  data-testid={`${TestId}.Percent`}
                  {...register('percent')}
                />
              </Field>
              <Field
                label="From"
                htmlFor="allocation-starts"
                error={errors.startsOn?.message}
                help="Inclusive."
              >
                <Input
                  id="allocation-starts"
                  type="date"
                  required
                  aria-invalid={!!errors.startsOn}
                  data-testid={`${TestId}.StartsOn`}
                  {...register('startsOn')}
                />
              </Field>
              <Field
                label="To"
                htmlFor="allocation-ends"
                error={errors.endsOn?.message}
                help="Inclusive."
              >
                <Input
                  id="allocation-ends"
                  type="date"
                  required
                  aria-invalid={!!errors.endsOn}
                  data-testid={`${TestId}.EndsOn`}
                  {...register('endsOn')}
                />
              </Field>
            </div>

            <Field
              label="Note"
              htmlFor="allocation-note"
              error={errors.note?.message}
              help="Optional — “until the migration lands”, “shared with Support”."
            >
              <Input
                id="allocation-note"
                autoComplete="off"
                maxLength={MAX_NOTE}
                aria-invalid={!!errors.note}
                data-testid={`${TestId}.Note`}
                {...register('note')}
              />
            </Field>

            <p className="m-0 text-[11.5px] leading-[1.55] text-ink-4">
              A percentage is a claim on somebody&rsquo;s week, not a promise about which days.
              Absence zeroes those days everywhere, so an allocation over a week somebody is away
              does not quietly assume them.
            </p>
          </div>

          <DialogFooter>
            <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.Cancel`}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={save.isPending}
              data-testid={`${TestId}.Submit`}
            >
              {save.isPending ? 'Saving…'
              : editing ? 'Save allocation'
              : 'Allocate'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Maps a rejected write onto the form — **and deliberately leaves the 409 alone**.
 *
 * A conflict is rendered from the live mutation error, beside the dates it names (see the file
 * header). Landing it in `errors.root` as well would put the same refusal on screen twice, and
 * `describeError`'s generic conflict sentence would be the weaker of the two.
 *
 * Everything else keeps the house shape: `Problem.errors` lands per field, a stray key the form has
 * no control for goes to the form-level alert with the server's own sentence rather than being
 * dropped, and anything unmapped goes through `describeError`.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.status === 409) return;

  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    const stray: string[] = [];
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((FIELDS as readonly string[]).includes(field)) {
        setError(field as FormField, messages.join(' '));
        landed = true;
      } else {
        stray.push(messages.join(' '));
      }
    }
    if (stray.length > 0) {
      setError('root', stray.join(' '));
      return;
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}
