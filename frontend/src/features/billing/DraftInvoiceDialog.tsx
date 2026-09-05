import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { useDraftInvoice, useRateCards } from '../../api/queries';
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
import { formatMonthRange, monthContaining } from './months';

/**
 * `billing.html`'s *+ Draft an invoice* — a client, a period, and deliberately nothing else.
 *
 * <h2>There is no line to add, and that absence is the product</h2>
 *
 * `InvoiceDraftInput` carries a `clientId`, two dates and the payment terms. The lines **are** the
 * approved hours that exist, computed on every read (019 D-c), and there is no field on this form
 * — or anywhere in the contract — that could add one by hand. *Billing is a report, not a
 * spreadsheet*, which is `invoice.html`'s own subtitle and is enforced by the shape of this input
 * rather than by a rule somebody could relax.
 *
 * <h2>The client picker lists clients with a rate card first, and does not hide the rest</h2>
 *
 * A client with no card can still be drafted for: every approved hour lands in the `NO_RATE`
 * exclusion bucket, counted and named, and the draft totals zero. That is a real and legible
 * answer — *"Without a card, approved hours are still recorded — they just cannot be priced"* — so
 * the option stays, with the consequence stated **before** the press rather than discovered after
 * it. Filtering those clients out would be this dialog deciding a Finance user may not look.
 *
 * <h2>The period defaults to the month the screen is on, and is editable</h2>
 *
 * Both ends are sent explicitly, because the server takes a range rather than a month. `months.ts`
 * seeds them; nothing here renders a period the server has already bounded.
 *
 * <h2>Payment terms carry no `default:` in the contract, and that is why this sends a number</h2>
 *
 * `termsDays` is optional and the server records 30 when it is omitted. The contract deliberately
 * declares no `default:` keyword — `openapi-typescript` emits a defaulted property as *required*,
 * which would make every draft that omitted it a compile error in both generated clients (018 T1's
 * finding). So the form shows 30 as its own initial value and always sends what the reader sees.
 */

const DraftSchema = z
  .object({
    clientId: z.string().min(1, 'Choose the client this invoice is for.'),
    periodStart: z.string().min(1, 'Enter the first day of the period.'),
    periodEnd: z.string().min(1, 'Enter the last day of the period.'),
    termsDays: z.coerce.number().int().min(0, 'Terms cannot be negative.').max(365, 'That is over a year.'),
  })
  // Both ends are inclusive, so a one-day period has `start === end` and only `end < start` is wrong.
  .refine((values) => values.periodEnd >= values.periodStart, {
    path: ['periodEnd'],
    message: 'The period cannot end before it starts.',
  });

type DraftFormInput = z.input<typeof DraftSchema>;
type DraftFormValues = z.output<typeof DraftSchema>;

const TEST_ID = 'Billing.Draft.Dialog';

export interface DraftInvoiceDialogProps {
  open: boolean;
  /** Any day inside the month the screen is showing — the period this seeds itself with. */
  on: string;
  onClose: () => void;
}

export function DraftInvoiceDialog({ open, on, onClose }: DraftInvoiceDialogProps) {
  const draft = useDraftInvoice();
  const cards = useRateCards(open);
  const navigate = useNavigate();

  const {
    register,
    handleSubmit,
    reset,
    setError,
    watch,
    formState: { errors },
  } = useForm<DraftFormInput, unknown, DraftFormValues>({
    resolver: zodResolver(DraftSchema),
    defaultValues: { clientId: '', periodStart: '', periodEnd: '', termsDays: 30 },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (!open) return;
    const month = monthContaining(on);
    reset({ clientId: '', periodStart: month.startsOn, periodEnd: month.endsOn, termsDays: 30 });
  }, [open, on, reset]);

  const chosen = watch('clientId');
  const slot = cards.data?.find((candidate) => candidate.client.id === chosen);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await draft.mutateAsync({
        clientId: values.clientId,
        periodStart: values.periodStart,
        periodEnd: values.periodEnd,
        termsDays: values.termsDays,
      });
      onClose();
      /*
        Straight to the draft, because the next thing a Finance user does is read what it caught and
        what it excluded. Returning to a list whose only change is one more row makes them find the
        thing they just made.
      */
      navigate(`/billing/invoices/${created.invoice.id}`);
    } catch (error) {
      setError('root', { type: 'server', message: describeError(error).body });
    }
  });

  const start = watch('periodStart');
  const end = watch('periodEnd');

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TEST_ID}>
        <form onSubmit={submit} noValidate aria-busy={draft.isPending}>
          <DialogHeader>
            <DialogTitle>Draft an invoice</DialogTitle>
          </DialogHeader>

          <div className="mt-4 flex flex-col gap-4">
            {errors.root && (
              <div data-testid={`${TEST_ID}.Error`}>
                <Banner tone="bad">
                  <span role="alert">{errors.root.message}</span>
                </Banner>
              </div>
            )}

            <Field
              label="Client"
              htmlFor="draft-client"
              error={errors.clientId?.message}
              help={cards.isError ? 'The client list did not answer. Try again in a moment.' : undefined}
            >
              <NativeSelect
                id="draft-client"
                disabled={cards.data === undefined}
                data-testid={`${TEST_ID}.Client`}
                {...register('clientId')}
              >
                <option value="">Choose a client…</option>
                {(cards.data ?? []).map((candidate) => (
                  <option key={candidate.client.id} value={candidate.client.id}>
                    {candidate.card === null ?
                      `${candidate.client.name} — no rate card`
                    : candidate.client.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>

            {/*
              Said before the press rather than discovered after it. A draft for a client with no
              card is a legitimate thing to make — it counts and names the hours it could not price
              — but a total of zero with no explanation is exactly the reading 019 D-g exists to
              prevent.
            */}
            {slot?.card === null && (
              <Banner tone="warn" data-testid={`${TEST_ID}.NoRateCard`}>
                <span>
                  This client has no rate card, so nothing on the draft can be priced. Every
                  approved hour will appear in the excluded bucket with its reason, and the total
                  will be zero. Set a card first if that is not what you want.
                </span>
              </Banner>
            )}

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Period from" htmlFor="draft-from" error={errors.periodStart?.message}>
                <Input
                  id="draft-from"
                  type="date"
                  aria-invalid={!!errors.periodStart}
                  data-testid={`${TEST_ID}.From`}
                  {...register('periodStart')}
                />
              </Field>
              <Field label="Period to" htmlFor="draft-to" error={errors.periodEnd?.message}>
                <Input
                  id="draft-to"
                  type="date"
                  aria-invalid={!!errors.periodEnd}
                  data-testid={`${TEST_ID}.To`}
                  {...register('periodEnd')}
                />
              </Field>
            </div>

            <Field
              label="Terms (days)"
              htmlFor="draft-terms"
              error={errors.termsDays?.message}
              help="Recorded on the invoice. Issuing does not send anything, so nothing here starts a clock."
            >
              <Input
                id="draft-terms"
                type="number"
                min="0"
                step="1"
                inputMode="numeric"
                className="w-24"
                aria-invalid={!!errors.termsDays}
                data-testid={`${TEST_ID}.Terms`}
                {...register('termsDays')}
              />
            </Field>

            <p className="m-0 max-w-[62ch] text-[12px] leading-[1.55] text-ink-4" data-testid={`${TEST_ID}.Note`}>
              {start && end ?
                `The draft will cover ${formatMonthRange(start, end)}. `
              : ''}
              Its lines are this period’s approved hours at the client’s rate-card rate, recomputed
              every time it is opened — so approving more hours changes it, and nothing here has to
              be refreshed. Nothing is locked until you issue it.
            </p>
          </div>

          <DialogFooter>
            <Button onClick={onClose} data-testid={`${TEST_ID}.Cancel`}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={draft.isPending}
              data-testid={`${TEST_ID}.Submit`}
            >
              {draft.isPending ? 'Drafting…' : 'Draft invoice'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
