import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { useCreateAbsence, useDeleteAbsence } from '../../api/queries';
import type { Absence, AbsenceKind } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Badge, Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import { formatDate } from '../../lib/format';
import { absenceKindLabels, absenceKindTones } from '../../lib/labels';
import { dayFractionLabel } from './capacityCopy';

/**
 * `member.html`'s Absence panel — and the one place 017 D-h is visible in the product.
 *
 * <h2>Your own absence needs no key; somebody else's needs `staffing:absence`</h2>
 *
 * The catalogue text is precise: `staffing:absence` covers *"record absence on somebody else's
 * behalf"*. Recording that you are away on Thursday is not an administrative act, and a product
 * that made it one would be a product where nobody records absence — which would make every
 * capacity figure in this module a lie by omission.
 *
 * So the Add control is enabled on **your own** person page for everybody, and on somebody else's
 * it costs the key and is disabled with the reason (§31). The server decides either way; this is
 * the affordance layer saying the same thing in advance.
 *
 * <h2>The mockup's sentence is kept verbatim, because D-a is what makes it literally true</h2>
 *
 * *"Capacity for those days is zero everywhere — staffing, utilisation and any plan built on
 * them."* Capacity is derived on every read rather than stored, so an absence recorded here is
 * subtracted the next time any of those five surfaces is opened. In a product that cached capacity
 * the same sentence would be aspirational, and this one would have to be softened.
 *
 * <h2>Absence is a fraction of a day, not a boolean and not a pair of times (017 D-d)</h2>
 *
 * `dayFraction` is what makes *half day* a value rather than a second table, and
 * {@link dayFractionLabel} is where the three readings of it are spelled once.
 *
 * <h2>The rows come from the person read rather than from `useAbsences`</h2>
 *
 * `PersonCapacity.absences` is already this window's absences, served beside the capacity figures
 * they were subtracted from. A second read of the same fact could answer over a different window,
 * and the panel would then list a day the hours above it do not account for.
 */
export interface AbsencePanelProps {
  userId: string;
  /** This person's absences in the window the person read used. */
  absences: readonly Absence[];
  /** Whether this is the signed-in person's own page — which is the whole of D-h. */
  isSelf: boolean;
  /** `staffing:absence`, for somebody else's page. Ignored when {@link isSelf}. */
  canRecordForOthers: boolean;
  /** The gate's own sentence, so the tooltip and the server's refusal agree. */
  reason: string | undefined;
  testId: string;
}

const AbsenceSchema = z
  .object({
    startsOn: z.string().min(1, 'Enter a start date.'),
    endsOn: z.string().min(1, 'Enter an end date.'),
    kind: z.enum(['LEAVE', 'SICK', 'HOLIDAY', 'OTHER']),
    dayFraction: z.coerce.number().min(0.1, 'At least a tenth of a day.').max(1, 'At most a whole day.'),
    note: z.string().max(500).optional(),
  })
  // Both ends are inclusive, so a one-day absence has `from === to` and only `to < from` is wrong.
  .refine((values) => values.endsOn >= values.startsOn, {
    path: ['endsOn'],
    message: 'The end date cannot be before the start date.',
  });

type AbsenceFormInput = z.input<typeof AbsenceSchema>;
type AbsenceFormValues = z.output<typeof AbsenceSchema>;

const KINDS: readonly AbsenceKind[] = ['LEAVE', 'SICK', 'HOLIDAY', 'OTHER'];

export function AbsencePanel({
  userId,
  absences,
  isSelf,
  canRecordForOthers,
  reason,
  testId,
}: AbsencePanelProps) {
  const [adding, setAdding] = useState(false);
  const remove = useDeleteAbsence(userId);

  /*
    D-h, in one expression. `isSelf` is checked FIRST and alone: a caller on their own page needs no
    key, so an `&&` here — or a gate consulted before the identity — would be the product deciding
    that recording your own leave is administrative.
  */
  const canAdd = isSelf || canRecordForOthers;
  const denial = isSelf ? undefined : reason;

  return (
    <Panel
      testId={testId}
      title="Absence"
      actions={
        adding ? null : (
          <span title={canAdd ? undefined : denial}>
            <Button
              size="sm"
              disabled={!canAdd}
              onClick={() => setAdding(true)}
              data-testid={`${testId}.Add`}
            >
              + Record absence
            </Button>
          </span>
        )
      }
    >
      <div className="flex flex-col gap-3">
        {remove.isError && (
          <div data-testid={`${testId}.Error`}>
            <Banner tone="bad">
              <span role="alert">{describeError(remove.error).body}</span>
            </Banner>
          </div>
        )}

        {adding && (
          <AbsenceForm userId={userId} onDone={() => setAdding(false)} testId={testId} />
        )}

        {absences.length === 0 ?
          <p className="m-0 text-[12.5px] text-ink-4" data-testid={`${testId}.None`}>
            Nothing recorded in this range.
          </p>
        : <ul className="m-0 flex list-none flex-col gap-2 p-0" data-testid={`${testId}.List`}>
            {absences.map((absence) => (
              <li
                key={absence.id}
                className="flex items-center gap-2"
                data-testid={`${testId}.${absence.id}`}
              >
                <span className="text-[12.5px] text-ink-2">
                  {/*
                    One date when it is one day — `member.html`'s `29 Aug` — and a range otherwise.
                    The spelling is `formatDate`'s ISO, not the mockup's `29 Aug`: this product has
                    one date format and a second one on this panel would be the first place two
                    dates on one screen looked like two different kinds of thing.
                  */}
                  {absence.startsOn === absence.endsOn ?
                    formatDate(absence.startsOn)
                  : `${formatDate(absence.startsOn)} – ${formatDate(absence.endsOn)}`}
                </span>
                <Badge tone={absenceKindTones[absence.kind]}>
                  {absenceKindLabels[absence.kind]}
                </Badge>
                <Badge tone="plain" data-testid={`${testId}.${absence.id}.Fraction`}>
                  {dayFractionLabel(absence.dayFraction)}
                </Badge>
                {absence.note && (
                  <span className="min-w-0 truncate text-[11.5px] text-ink-4">{absence.note}</span>
                )}
                <span className="flex-1" />
                <span title={canAdd ? undefined : denial}>
                  <Button
                    size="sm"
                    variant="quiet"
                    disabled={!canAdd || remove.isPending}
                    onClick={() => remove.mutate(absence.id)}
                    data-testid={`${testId}.${absence.id}.Remove`}
                  >
                    Remove
                  </Button>
                </span>
              </li>
            ))}
          </ul>
        }

        {/*
          `member.html`'s own line, kept word for word. It is literally true here rather than
          aspirational because capacity is derived on every read (017 D-a) — a product that stored
          it would have to soften this sentence to "will be, once something recomputes".
        */}
        <p
          className="m-0 text-[11.5px] leading-[1.5] text-ink-4"
          data-testid={`${testId}.Basis`}
        >
          Capacity for those days is zero everywhere — staffing, utilisation and any plan built on
          them.
        </p>
      </div>
    </Panel>
  );
}

function AbsenceForm({
  userId,
  onDone,
  testId,
}: {
  userId: string;
  onDone: () => void;
  testId: string;
}) {
  const create = useCreateAbsence(userId);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<AbsenceFormInput, unknown, AbsenceFormValues>({
    resolver: zodResolver(AbsenceSchema),
    defaultValues: { startsOn: '', endsOn: '', kind: 'LEAVE', dayFraction: 1, note: '' },
    mode: 'onBlur',
  });

  const submit = handleSubmit(async (values) => {
    try {
      await create.mutateAsync({
        startsOn: values.startsOn,
        endsOn: values.endsOn,
        kind: values.kind,
        dayFraction: values.dayFraction,
        note: values.note?.trim() || null,
      });
      onDone();
    } catch (error) {
      setError('root', { type: 'server', message: describeError(error).body });
    }
  });

  return (
    <form
      onSubmit={submit}
      noValidate
      aria-busy={create.isPending}
      className="rounded-[6px] border border-line p-3"
      data-testid={`${testId}.Form`}
    >
      <div className="flex flex-col gap-3">
        {errors.root && (
          <div data-testid={`${testId}.Form.Error`}>
            <Banner tone="bad">
              <span role="alert">{errors.root.message}</span>
            </Banner>
          </div>
        )}

        <div className="flex flex-wrap gap-3">
          <Field label="From" htmlFor="absence-starts" error={errors.startsOn?.message} help="Inclusive.">
            <Input
              id="absence-starts"
              type="date"
              required
              aria-invalid={!!errors.startsOn}
              data-testid={`${testId}.Form.StartsOn`}
              {...register('startsOn')}
            />
          </Field>
          <Field label="To" htmlFor="absence-ends" error={errors.endsOn?.message} help="Inclusive.">
            <Input
              id="absence-ends"
              type="date"
              required
              aria-invalid={!!errors.endsOn}
              data-testid={`${testId}.Form.EndsOn`}
              {...register('endsOn')}
            />
          </Field>
        </div>

        <div className="flex flex-wrap gap-3">
          <Field
            label="Kind"
            htmlFor="absence-kind"
            /*
              `HOLIDAY` is a member rather than a calendar (017 D-c). Said on the control because a
              reader who expected a company holiday calendar needs to know the row IS the mechanism.
            */
            help="A public holiday is one row per person — this product has no holiday calendar, and a row is what actually zeroes the day."
          >
            <NativeSelect
              id="absence-kind"
              className="w-[168px]"
              data-testid={`${testId}.Form.Kind`}
              {...register('kind')}
            >
              {KINDS.map((kind) => (
                <option key={kind} value={kind}>
                  {absenceKindLabels[kind]}
                </option>
              ))}
            </NativeSelect>
          </Field>
          <Field
            label="Share of each day"
            htmlFor="absence-fraction"
            error={errors.dayFraction?.message}
            help="1 is a whole day, 0.5 a half."
          >
            <Input
              id="absence-fraction"
              type="number"
              step="0.1"
              min={0.1}
              max={1}
              required
              className="w-[100px] text-right font-mono tabular-nums"
              aria-invalid={!!errors.dayFraction}
              data-testid={`${testId}.Form.Fraction`}
              {...register('dayFraction')}
            />
          </Field>
        </div>

        <Field label="Note" htmlFor="absence-note" error={errors.note?.message} help="Optional.">
          <Input
            id="absence-note"
            autoComplete="off"
            maxLength={500}
            aria-invalid={!!errors.note}
            data-testid={`${testId}.Form.Note`}
            {...register('note')}
          />
        </Field>

        <div className="flex justify-end gap-2">
          <Button variant="quiet" onClick={onDone} data-testid={`${testId}.Form.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={create.isPending}
            data-testid={`${testId}.Form.Save`}
          >
            {create.isPending ? 'Recording…' : 'Record absence'}
          </Button>
        </div>
      </div>
    </form>
  );
}
