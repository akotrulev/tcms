import { zodResolver } from '@hookform/resolvers/zod';
import { Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import {
  useCreateRateCard,
  useDeleteRateCard,
  useRoles,
  useUpdateRateCard,
  useUsers,
} from '../../api/queries';
import type { ClientRef, RateCardScope, RateCardSlot } from '../../api/types';
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
  Skeleton,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import { rateCardScopeLabels } from '../../lib/labels';

/**
 * One client's rate card — `billing.html`'s *New rate card* and its per-row *Edit*.
 *
 * <h2>The whole ladder is one form, because the `PUT` is a whole replacement</h2>
 *
 * `PUT /rate-cards/{id}` replaces the card **entries included**: the entries sent are the entries
 * the card then has, and one omitted is one removed. So there is no per-entry endpoint to build a
 * per-row editor against, and a dialog that edited one row would have to send the other four back
 * unchanged anyway — with every one of them a chance to drop a rung nobody was looking at. The
 * mockup's row-level pencil opens *this*, seeded with the card it is on.
 *
 * <h2>The ladder is ordered, and the form says so rather than implying a list</h2>
 *
 * A `PERSON` entry naming whoever logged the hour beats a `ROLE` entry naming a role they hold on
 * that project, which beats `ALL_ROLES` (019 D-d) — and where two `ROLE` entries both match, the
 * **higher rate wins**, which is deterministic by construction and a decision rather than a coin
 * toss. None of that is decided here; it is the server's, and this form's job is to say it out
 * loud so a Finance user adding a person rate knows it will override the role rate rather than
 * average with it.
 *
 * <h2>The two pickers are populatable, unlike `ClientCreateDialog`'s account manager</h2>
 *
 * `GET /users` costs `company:read`, which the non-assignable **Company Member** baseline grants
 * every member on joining, and `GET /roles` costs nothing beyond a session. So both are reachable
 * for the Finance user this screen exists for — which is why a `PERSON` rung can be added here
 * where `client-new.html`'s account-manager picker had to be left out (018): that one needed
 * `staffing:directory`, and a Company Admin holding `client:create` need not hold it.
 *
 * Both lists fail **open into a disabled picker with a sentence**, not into an empty one: a select
 * with no options and no explanation reads as a company with no people in it.
 *
 * <h2>One dialog, two openings, and the client is a field in only one of them</h2>
 *
 * A row's pencil opens it on that row's slot, where the client is already decided and is a heading
 * rather than an input — `uq_rate_card_client` is one card per client, so moving one between
 * clients is not an edit this contract admits, and the `PUT` ignores `clientId` for that reason.
 * The panel's *New rate card* button opens it with no slot, and only then is there a picker: over
 * the clients that have **no** card, because those are the only ones a create can succeed for.
 *
 * <h2>Deleting a card does not un-price an issued invoice</h2>
 *
 * The Remove button confirms by naming the client, per the house rule for a destructive action.
 * What it does *not* do is change any invoice already issued — those lines are stored rows
 * carrying the rate as applied (019 D-c) — and what it does do is put every future approved hour
 * for that client into the `NO_RATE` exclusion bucket, counted and named on the next draft rather
 * than silently dropped. The confirmation says both.
 */

const SCOPES: readonly RateCardScope[] = ['ALL_ROLES', 'ROLE', 'PERSON'];

const EntrySchema = z
  .object({
    scope: z.enum(['PERSON', 'ROLE', 'ALL_ROLES']),
    personId: z.string(),
    roleId: z.string(),
    hourlyRate: z.coerce.number().positive('Enter a rate above zero.'),
    effectiveFrom: z.string().min(1, 'Enter the day this rate starts.'),
  })
  /*
    `ck_rate_card_entry_shape` holds the same rule in the database, so the application cannot drift
    from it: `PERSON` needs `personId`, `ROLE` needs `roleId`, `ALL_ROLES` needs neither. Checked
    here as well as there because a 400 that lands on nothing is a form the reader cannot fix.
  */
  .superRefine((values, ctx) => {
    if (values.scope === 'PERSON' && values.personId === '') {
      ctx.addIssue({ code: 'custom', path: ['personId'], message: 'Choose the person this rate is for.' });
    }
    if (values.scope === 'ROLE' && values.roleId === '') {
      ctx.addIssue({ code: 'custom', path: ['roleId'], message: 'Choose the role this rate is for.' });
    }
  });

/*
  The empty-ladder rule is checked in the submit handler rather than as a `.min(1)` here, and that
  is a typing decision rather than a preference: an array-level zod issue lands on `errors.entries`
  as a sibling of the per-row errors, and reading it back needs a cast that would then be the one
  place a per-row message could be rendered as a form-level one. The guard below sets `root`, which
  is where every other form-level refusal in this dialog already goes.
*/
const CardSchema = z.object({
  clientId: z.string(),
  currency: z
    .string()
    .trim()
    .length(3, 'Three letters — the ISO code, like EUR or CHF.')
    .regex(/^[A-Za-z]{3}$/, 'Three letters — the ISO code, like EUR or CHF.'),
  entries: z.array(EntrySchema),
});

type CardFormInput = z.input<typeof CardSchema>;
type CardFormValues = z.output<typeof CardSchema>;

const TEST_ID = 'Billing.RateCard.Dialog';

export interface RateCardDialogProps {
  open: boolean;
  /**
   * The slot the row's pencil was on, or `null` when the reader pressed *New rate card* — in which
   * case {@link unset} is the picker.
   */
  slot: RateCardSlot | null;
  /** The clients with no card yet. Only read when {@link slot} is `null`. */
  unset: readonly ClientRef[];
  onClose: () => void;
}

export function RateCardDialog({ open, slot, unset, onClose }: RateCardDialogProps) {
  const card = slot?.card ?? null;

  const create = useCreateRateCard();
  const update = useUpdateRateCard(card?.id ?? '');
  const remove = useDeleteRateCard();

  const people = useUsers();
  const roles = useRoles();

  /** Whether the Remove button has been pressed once — see the confirmation below. */
  const [confirming, setConfirming] = useState(false);

  const {
    control,
    register,
    handleSubmit,
    reset,
    setError,
    watch,
    formState: { errors },
  } = useForm<CardFormInput, unknown, CardFormValues>({
    resolver: zodResolver(CardSchema),
    defaultValues: { clientId: '', currency: 'EUR', entries: [] },
    mode: 'onBlur',
  });

  const entries = useFieldArray({ control, name: 'entries' });

  /*
    Seeded from the slot on mount, and re-seeded if the slot changes underneath.

    The parent mounts this **only while it is open**, because the two pickers below read `/users`
    and `/roles`: a permanently mounted dialog would make two requests on every load of the Billing
    screen for a form nobody opened. `reset` rather than `defaultValues` alone so a refetch that
    lands a changed card while the dialog is up cannot leave the form showing the old ladder.
  */
  useEffect(() => {
    if (!open) return;
    setConfirming(false);
    reset({
      clientId: slot?.client.id ?? '',
      currency: card?.currency ?? 'EUR',
      entries: (card?.entries ?? []).map((entry) => ({
        scope: entry.scope,
        personId: entry.personId ?? '',
        roleId: entry.roleId ?? '',
        hourlyRate: String(entry.hourlyRate),
        effectiveFrom: entry.effectiveFrom,
      })),
    });
  }, [open, slot, card, reset]);

  const submit = handleSubmit(async (values) => {
    const clientId = slot?.client.id ?? values.clientId;
    if (clientId === '') {
      setError('clientId', { type: 'validate', message: 'Choose the client this card is for.' });
      return;
    }
    if (values.entries.length === 0) {
      setError('root', {
        type: 'validate',
        message:
          'A card with no rates prices nothing — every approved hour would land in the “no rate” bucket. Add at least one rate.',
      });
      return;
    }
    const input = {
      clientId,
      currency: values.currency.toUpperCase(),
      entries: values.entries.map((entry) => ({
        scope: entry.scope,
        personId: entry.scope === 'PERSON' ? entry.personId : null,
        roleId: entry.scope === 'ROLE' ? entry.roleId : null,
        hourlyRate: entry.hourlyRate,
        effectiveFrom: entry.effectiveFrom,
      })),
    };
    try {
      if (card) await update.mutateAsync(input);
      else await create.mutateAsync(input);
      onClose();
    } catch (error) {
      /*
        The 409 on create names a client that already has a card — one per client, `uq_rate_card_client`
        — and it is the one refusal a reader can act on from here, so it lands on the form rather
        than in the notification stack. `ClientCreateDialog.applyServerError` makes the same split.
      */
      setError('root', { type: 'server', message: describeError(error).body });
      if (error instanceof ApiError && error.fieldErrors?.currency) {
        setError('currency', { type: 'server', message: error.fieldErrors.currency.join(' ') });
      }
    }
  });

  const pending = create.isPending || update.isPending || remove.isPending;
  const pickersPending = people.isPending || roles.isPending;
  const scopes = watch('entries');

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-2xl" data-testid={TEST_ID}>
        <form onSubmit={submit} noValidate aria-busy={pending}>
          <DialogHeader>
            <DialogTitle>
              {!slot ? 'New rate card'
              : card ? `Rate card — ${slot.client.name}`
              : `New rate card — ${slot.client.name}`}
            </DialogTitle>
          </DialogHeader>

          <div className="mt-4 flex flex-col gap-4">
            {errors.root && (
              <div data-testid={`${TEST_ID}.Error`}>
                <Banner tone="bad">
                  <span role="alert">{errors.root.message}</span>
                </Banner>
              </div>
            )}

            {/*
              Only where there is a choice to make. On a row's pencil the client is in the title
              above, and rendering a disabled select saying the same thing twice would suggest it is
              a field somebody could unlock.
            */}
            {!slot && (
              <Field
                label="Client"
                htmlFor="rate-card-client"
                error={errors.clientId?.message}
                help="Only clients with no card yet. One card per client — edit an existing one from its row."
              >
                <NativeSelect
                  id="rate-card-client"
                  data-testid={`${TEST_ID}.Client`}
                  {...register('clientId')}
                >
                  <option value="">Choose a client…</option>
                  {unset.map((client) => (
                    <option key={client.id} value={client.id}>
                      {client.name}
                    </option>
                  ))}
                </NativeSelect>
              </Field>
            )}

            <Field
              label="Currency"
              htmlFor="rate-card-currency"
              error={errors.currency?.message}
              help="The card carries the currency, so an invoice for this client is always in it — and a cross-client invoice is structurally impossible."
            >
              <Input
                id="rate-card-currency"
                autoComplete="off"
                maxLength={3}
                className="w-24 uppercase"
                aria-invalid={!!errors.currency}
                data-testid={`${TEST_ID}.Currency`}
                {...register('currency')}
              />
            </Field>

            <div className="flex flex-col gap-2">
              <p className="m-0 text-[12.5px] text-ink-2" data-testid={`${TEST_ID}.LadderNote`}>
                A person’s rate beats their role’s, and a role’s beats <em>All roles</em>. Where two
                role rates both match, the higher one wins. Each entry applies from its own date, so
                a rise mid-year is a second entry rather than an edit to the first.
              </p>
              {/*
                The ladder waits for both pickers, and this is a correctness wait rather than a
                cosmetic one. A `PERSON` rung is seeded with a person **id**; rendered into a select
                whose options have not arrived, the browser falls back to the placeholder — so the
                form would show *Choose a person…* over a rung that is in fact set, and a reader
                would either "fix" it or save something they did not read. `isPending` rather than
                `data === undefined`, so a failed list falls through to the disabled picker and its
                sentence instead of waiting forever.
              */}
              {pickersPending ?
                <div className="flex flex-col gap-2" data-testid={`${TEST_ID}.Entries.Loading`}>
                  <Skeleton className="h-16 rounded-[8px]" />
                  <Skeleton className="h-16 rounded-[8px]" />
                </div>
              : <ul className="m-0 flex list-none flex-col gap-3 p-0" data-testid={`${TEST_ID}.Entries`}>
                {entries.fields.map((field, index) => {
                  const scope = scopes?.[index]?.scope ?? 'ALL_ROLES';
                  const row = errors.entries?.[index];
                  return (
                    <li
                      key={field.id}
                      className="grid grid-cols-1 items-start gap-2 rounded-[8px] border border-line p-2.5 md:grid-cols-[130px_minmax(0,1fr)_110px_140px_auto]"
                      data-testid={`${TEST_ID}.Entry.${index}`}
                    >
                      <Field label="Applies to" htmlFor={`rce-scope-${index}`} error={row?.scope?.message}>
                        <NativeSelect
                          id={`rce-scope-${index}`}
                          data-testid={`${TEST_ID}.Entry.${index}.Scope`}
                          {...register(`entries.${index}.scope` as const)}
                        >
                          {SCOPES.map((value) => (
                            <option key={value} value={value}>
                              {value === 'ALL_ROLES' ? rateCardScopeLabels.ALL_ROLES
                              : value === 'ROLE' ? 'A role'
                              : 'A person'}
                            </option>
                          ))}
                        </NativeSelect>
                      </Field>

                      {scope === 'PERSON' ?
                        <Field
                          label="Person"
                          htmlFor={`rce-person-${index}`}
                          error={row?.personId?.message}
                          help={people.isError ? 'The people list did not answer, so this rung cannot be set right now.' : undefined}
                        >
                          <NativeSelect
                            id={`rce-person-${index}`}
                            disabled={people.data === undefined}
                            data-testid={`${TEST_ID}.Entry.${index}.Person`}
                            {...register(`entries.${index}.personId` as const)}
                          >
                            <option value="">Choose a person…</option>
                            {(people.data ?? []).map((person) => (
                              <option key={person.id} value={person.id}>
                                {person.displayName}
                              </option>
                            ))}
                          </NativeSelect>
                        </Field>
                      : scope === 'ROLE' ?
                        <Field
                          label="Role"
                          htmlFor={`rce-role-${index}`}
                          error={row?.roleId?.message}
                          help={roles.isError ? 'The role list did not answer, so this rung cannot be set right now.' : undefined}
                        >
                          <NativeSelect
                            id={`rce-role-${index}`}
                            disabled={roles.data === undefined}
                            data-testid={`${TEST_ID}.Entry.${index}.Role`}
                            {...register(`entries.${index}.roleId` as const)}
                          >
                            <option value="">Choose a role…</option>
                            {(roles.data ?? []).map((role) => (
                              <option key={role.id} value={role.id}>
                                {role.name}
                              </option>
                            ))}
                          </NativeSelect>
                        </Field>
                      : <Field label="Applies to" htmlFor={`rce-all-${index}`}>
                          <p
                            id={`rce-all-${index}`}
                            className="m-0 py-1.5 text-[12.5px] text-ink-3"
                            data-testid={`${TEST_ID}.Entry.${index}.AllRoles`}
                          >
                            Everybody on this client’s projects.
                          </p>
                        </Field>
                      }

                      <Field label="Rate / hour" htmlFor={`rce-rate-${index}`} error={row?.hourlyRate?.message}>
                        <Input
                          id={`rce-rate-${index}`}
                          type="number"
                          min="0"
                          step="0.01"
                          inputMode="decimal"
                          aria-invalid={!!row?.hourlyRate}
                          data-testid={`${TEST_ID}.Entry.${index}.Rate`}
                          {...register(`entries.${index}.hourlyRate` as const)}
                        />
                      </Field>

                      <Field label="From" htmlFor={`rce-from-${index}`} error={row?.effectiveFrom?.message}>
                        <Input
                          id={`rce-from-${index}`}
                          type="date"
                          aria-invalid={!!row?.effectiveFrom}
                          data-testid={`${TEST_ID}.Entry.${index}.From`}
                          {...register(`entries.${index}.effectiveFrom` as const)}
                        />
                      </Field>

                      <Button
                        size="icon"
                        variant="quiet"
                        onClick={() => entries.remove(index)}
                        aria-label={`Remove rate ${index + 1}`}
                        data-testid={`${TEST_ID}.Entry.${index}.Remove`}
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </li>
                  );
                })}
                </ul>
              }

              <div>
                <Button
                  size="sm"
                  disabled={pickersPending}
                  onClick={() =>
                    entries.append({
                      scope: 'ALL_ROLES',
                      personId: '',
                      roleId: '',
                      hourlyRate: '',
                      effectiveFrom: '',
                    })
                  }
                  data-testid={`${TEST_ID}.AddEntry`}
                >
                  Add a rate
                </Button>
              </div>
            </div>
          </div>

          {/*
            The destructive action's confirmation, in place rather than in a second dialog: this app
            never stacks modals and never uses `window.confirm`. It names the client, and it names
            both consequences — the one a reader expects and the one they do not.
          */}
          {confirming && card && (
            <div className="mt-4" data-testid={`${TEST_ID}.ConfirmRemove`}>
              <Banner tone="bad">
                <div className="min-w-0">
                  <p className="m-0 text-[13px] font-semibold text-ink">
                    {`Remove the rate card for ${slot?.client.name}?`}
                  </p>
                  <p className="mt-1 mb-3">
                    Invoices already issued keep their figures — their lines carry the rate as it
                    was applied. What changes is everything after: this client’s approved hours can
                    no longer be priced, so they land in the <em>no rate</em> bucket on the next
                    draft, counted and named rather than dropped.
                  </p>
                  <div className="flex gap-2">
                    <Button
                      variant="danger"
                      size="sm"
                      disabled={pending}
                      onClick={removeCard}
                      data-testid={`${TEST_ID}.ConfirmRemove.Confirm`}
                    >
                      {`Remove the card for ${slot?.client.name}`}
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => setConfirming(false)}
                      data-testid={`${TEST_ID}.ConfirmRemove.Cancel`}
                    >
                      Keep it
                    </Button>
                  </div>
                </div>
              </Banner>
            </div>
          )}

          <DialogFooter>
            {card && !confirming && (
              <Button
                variant="danger"
                disabled={pending}
                onClick={() => setConfirming(true)}
                data-testid={`${TEST_ID}.Remove`}
              >
                Remove card
              </Button>
            )}
            <span className="flex-1" />
            <Button onClick={onClose} data-testid={`${TEST_ID}.Cancel`}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={pending || pickersPending}
              data-testid={`${TEST_ID}.Submit`}
            >
              {pending ? 'Saving…' : card ? 'Save card' : 'Create card'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );

  function removeCard() {
    if (!card) return;
    void remove.mutateAsync(card.id).then(onClose, (error: unknown) => {
      setConfirming(false);
      setError('root', { type: 'server', message: describeError(error).body });
    });
  }
}
