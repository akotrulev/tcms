import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import type { TimeEntry, TimeEntryInput, TimeSubjectType } from '../../api/types';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import { timeSubjectLabels } from '../../lib/labels';

/**
 * `timesheets.html`'s *Add an entry* panel — and the same form, seeded, for an amend.
 *
 * <h2>The `Against` select drives whether there is an Issue field at all</h2>
 *
 * Not a disabled input, and not a field that is ignored: the contract **refuses** an `issueKey` on
 * a non-ISSUE entry with a 400, and `ck_time_entry_subject_shape` says the same thing at the table.
 * So the field is removed, and the help text under the select is the mockup's own sentence — *"not
 * everything is an issue"* — which is the whole point of this screen existing rather than a Log
 * Time button on an issue page.
 *
 * <h2>The issue is typed as a key, because there is no cross-project picker to offer</h2>
 *
 * `GET /issues` requires a `projectId` and a fortnight spans projects (016 D-i), so a typeahead
 * here would have to ask which project first — two fields where the mockup has one, and a question
 * a person logging four hours against `ACME-124` does not have the answer to in that order. The key
 * is what they read off the branch name, the commit message and the board card. An unknown key is a
 * 404 from the server and lands **on this field**, which is where it can be fixed.
 *
 * <h2>Single-flight, and it is not an idempotency key</h2>
 *
 * `POST /timesheets/entries` declares `Idempotency-Key` and the server ignores it — `V9` gave
 * `time_entry` no column to store one — so `useLogTime` deliberately does not send the header. The
 * guard here is therefore the only one there is: `pending` disables the button, so a second press
 * cannot start a second request. It does **not** survive a timeout, and this form does not pretend
 * it does. That gap is recorded on `useLogTime` and closes with a column, not with a change here.
 */

const TEST_ID = 'Timesheet.EntryForm';

/**
 * The three refusals this form can make on its own, and no fourth.
 *
 * The bounds are the contract's (`0.25`–`24`) rather than a second opinion about a working day: a
 * person who worked a fourteen-hour day gets to say so, and 24 is the arithmetic limit rather than
 * a policy. Everything else — the key that does not exist, the project with time tracking off, the
 * fortnight that is closed — is decided against data this form does not hold, so it is the server's
 * and arrives as a field error or a banner.
 */
const EntrySchema = z
  .object({
    subjectType: z.enum(['ISSUE', 'MEETING', 'LEAVE', 'TRAINING', 'INTERNAL']),
    issueKey: z.string(),
    spentOn: z.string().min(1, 'Say which day.'),
    hours: z
      .string()
      .min(1, 'Say how long.')
      .refine((raw) => Number.isFinite(Number(raw)), 'Hours is a number, like 2 or 1.5.')
      .refine((raw) => Number(raw) >= 0.25, 'A quarter of an hour is the smallest entry.')
      .refine((raw) => Number(raw) <= 24, 'A day is 24 hours. Split it across the days it was.'),
    note: z.string(),
  })
  .refine((values) => values.subjectType !== 'ISSUE' || values.issueKey.trim() !== '', {
    path: ['issueKey'],
    message: 'Name the issue, or log this against a category instead.',
  });

type EntryFormValues = z.infer<typeof EntrySchema>;

export interface EntryFormProps {
  /** The day the form opens on — today, clamped into the period being looked at by the caller. */
  defaultDate: string;
  /**
   * The entry being amended, or null to add.
   *
   * **The caller keys this component on the entry's id**, so switching between add and amend
   * remounts it and the fields below are seeded from `defaultValues` alone. That is deliberately
   * instead of an effect calling `reset`: an effect would also fire on every refetch that handed
   * back an equal-but-new entry, wiping whatever the person had typed since — and it needs a
   * dependency list that lies about what it reads to avoid doing so.
   */
  editing: TimeEntry | null;
  pending: boolean;
  onSubmit: (input: TimeEntryInput) => Promise<unknown>;
  onCancelEdit?: () => void;
}

export function EntryForm({
  defaultDate,
  editing,
  pending,
  onSubmit,
  onCancelEdit,
}: EntryFormProps) {
  const form = useForm<EntryFormValues>({
    resolver: zodResolver(EntrySchema),
    defaultValues: editing ? valuesOf(editing) : blankValues(defaultDate),
  });

  const subjectType = form.watch('subjectType');
  const isIssue = subjectType === 'ISSUE';

  const submit = form.handleSubmit(async (values) => {
    try {
      await onSubmit({
        subjectType: values.subjectType,
        // Omitted rather than sent empty on a category entry: the contract refuses the property on
        // anything but an ISSUE, and `""` is a property.
        ...(values.subjectType === 'ISSUE' ? { issueKey: values.issueKey.trim() } : {}),
        spentOn: values.spentOn,
        hours: Number(values.hours),
        note: values.note.trim() || null,
      });
      form.reset(blankValues(defaultDate));
    } catch (caught) {
      if (caught instanceof ApiError && caught.fieldErrors) {
        for (const [field, messages] of Object.entries(caught.fieldErrors)) {
          if (isFormField(field)) form.setError(field, { message: messages.join(' ') });
          else form.setError('root', { message: messages.join(' ') });
        }
        return;
      }
      /*
        The 404 is an issue key nobody can find, and it is the one server refusal that belongs on a
        *field* rather than in the banner — the reader typed it, and it is one character away from
        being right. Everything else, the 409 included, is about the fortnight rather than about
        anything in this form.
      */
      if (caught instanceof ApiError && caught.status === 404 && isIssue) {
        form.setError('issueKey', { message: describeError(caught).body });
        return;
      }
      form.setError('root', { message: describeError(caught).body });
    }
  });

  /*
    Everything the server refused that is not about one of these five fields — the 403, and the 409
    that says the fortnight closed between this form rendering and the person pressing Add. Read
    from `formState` rather than from the mutation's own `error`, so it clears when the next attempt
    succeeds instead of outliving it.
  */
  const banner = form.formState.errors.root?.message;

  return (
    <form onSubmit={submit} noValidate aria-busy={pending} data-testid={TEST_ID}>
      <div className="flex flex-col gap-4">
        {banner && (
          <Banner tone="bad" data-testid={`${TEST_ID}.Error`}>
            <span role="alert">{banner}</span>
          </Banner>
        )}

        <div className="grid gap-4 md:grid-cols-2">
          <Field
            label="Against"
            htmlFor={`${TEST_ID}-subject`}
            help="Not everything is an issue. A meeting or a day off is logged here, in the same place."
            error={form.formState.errors.subjectType?.message}
          >
            <NativeSelect
              id={`${TEST_ID}-subject`}
              data-testid={`${TEST_ID}.Subject`}
              {...form.register('subjectType')}
            >
              {SUBJECT_ORDER.map((kind) => (
                <option key={kind} value={kind}>
                  {timeSubjectLabels[kind]}
                </option>
              ))}
            </NativeSelect>
          </Field>

          {isIssue && (
            <Field
              label="Issue"
              htmlFor={`${TEST_ID}-issue`}
              help="The key, from any project you can see."
              error={form.formState.errors.issueKey?.message}
            >
              <Input
                id={`${TEST_ID}-issue`}
                className="font-mono"
                placeholder="ACME-124"
                data-testid={`${TEST_ID}.Issue`}
                {...form.register('issueKey')}
              />
            </Field>
          )}

          <Field
            label="Date"
            htmlFor={`${TEST_ID}-date`}
            error={form.formState.errors.spentOn?.message}
          >
            <Input
              id={`${TEST_ID}-date`}
              type="date"
              data-testid={`${TEST_ID}.Date`}
              {...form.register('spentOn')}
            />
          </Field>

          <Field
            label="Hours"
            htmlFor={`${TEST_ID}-hours`}
            help="A quarter of an hour is the smallest entry."
            error={form.formState.errors.hours?.message}
          >
            <Input
              id={`${TEST_ID}-hours`}
              inputMode="decimal"
              placeholder="2.0"
              data-testid={`${TEST_ID}.Hours`}
              {...form.register('hours')}
            />
          </Field>
        </div>

        <Field
          label="Note"
          htmlFor={`${TEST_ID}-note`}
          error={form.formState.errors.note?.message}
        >
          <Input
            id={`${TEST_ID}-note`}
            placeholder="Optional"
            data-testid={`${TEST_ID}.Note`}
            {...form.register('note')}
          />
        </Field>

        <div className="flex items-center gap-2 border-t border-line pt-3">
          {editing && onCancelEdit && (
            <Button size="sm" type="button" onClick={onCancelEdit} data-testid={`${TEST_ID}.Cancel`}>
              Cancel
            </Button>
          )}
          <span className="flex-1" />
          <Button
            type="submit"
            variant="primary"
            size="sm"
            disabled={pending}
            data-testid={`${TEST_ID}.Save`}
          >
            {editing ? 'Save changes' : 'Add entry'}
          </Button>
        </div>
      </div>
    </form>
  );
}

/** The mockup's own option order: the issue first, then the four that are not one. */
const SUBJECT_ORDER: readonly TimeSubjectType[] = [
  'ISSUE',
  'MEETING',
  'LEAVE',
  'TRAINING',
  'INTERNAL',
];

const FORM_FIELDS = ['subjectType', 'issueKey', 'spentOn', 'hours', 'note'] as const;

function isFormField(name: string): name is (typeof FORM_FIELDS)[number] {
  return (FORM_FIELDS as readonly string[]).includes(name);
}

function blankValues(spentOn: string): EntryFormValues {
  return { subjectType: 'ISSUE', issueKey: '', spentOn, hours: '', note: '' };
}

function valuesOf(entry: TimeEntry): EntryFormValues {
  return {
    subjectType: entry.subjectType,
    issueKey: entry.subject?.issueKey ?? '',
    spentOn: entry.spentOn,
    // A string, because the input is one — `2` and `2.0` are the same number and only one of them
    // is what the person typed, so the form holds the text and the submit does the conversion.
    hours: String(entry.hours),
    note: entry.note ?? '',
  };
}
