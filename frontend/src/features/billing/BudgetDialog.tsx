import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { useClearProjectBudget, useSetProjectBudget } from '../../api/queries';
import type { Budget } from '../../api/types';
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
import { budgetBasisLabels } from '../../lib/labels';

/**
 * One project's ceiling — hours or money, and the form makes the reader choose which.
 *
 * <h2>`currency` is required by one basis and forbidden by the other</h2>
 *
 * `ck_budget_shape` holds that rule in the database as well as in the contract, so the application
 * cannot drift from it: a `MONEY` basis **requires** a currency and an `HOURS` basis **forbids**
 * one. The field therefore appears and disappears with the basis rather than being disabled — a
 * greyed currency box beside an hours ceiling invites somebody to fill it in and find out at the
 * end that they could not.
 *
 * <h2>Nothing here decides what a Project Manager sees</h2>
 *
 * Whichever basis is chosen, the project's own screens read a **percentage** and never an amount
 * (019 D-h) — the project-scope endpoint has no `amount` and no `currency` member at all. So the
 * choice made here is a Finance decision about how the ceiling is *measured*, not about what the
 * team delivering against it is told. The help text says so, because the opposite assumption is
 * the natural one.
 *
 * <h2>Clearing a budget is not a deletion of anything the project needs</h2>
 *
 * A project with no budget is a complete state, not a deficient one: `settings-general.html`'s
 * Budget row reads *no budget set* and the overview draws no panel at all. It is still a write
 * somebody cannot undo without retyping the figure, so it confirms by naming the project — in
 * place, because this app never stacks modals and never uses `window.confirm`.
 */

const BudgetSchema = z
  .object({
    basis: z.enum(['HOURS', 'MONEY']),
    amount: z.coerce.number().positive('Enter a ceiling above zero.'),
    currency: z.string().trim(),
  })
  .superRefine((values, ctx) => {
    if (values.basis === 'MONEY' && !/^[A-Za-z]{3}$/.test(values.currency)) {
      ctx.addIssue({
        code: 'custom',
        path: ['currency'],
        message: 'A money ceiling needs its currency — three letters, like EUR.',
      });
    }
  });

type BudgetFormInput = z.input<typeof BudgetSchema>;
type BudgetFormValues = z.output<typeof BudgetSchema>;

const TEST_ID = 'Billing.Budget.Dialog';

export interface BudgetDialogProps {
  /** The project's row, or `null` when nothing is being edited. */
  budget: Budget | null;
  onClose: () => void;
}

export function BudgetDialog({ budget, onClose }: BudgetDialogProps) {
  const open = budget !== null;
  const projectId = budget?.projectId ?? '';

  const save = useSetProjectBudget(projectId);
  const clear = useClearProjectBudget();
  const [confirming, setConfirming] = useState(false);

  const {
    register,
    handleSubmit,
    reset,
    setError,
    watch,
    formState: { errors },
  } = useForm<BudgetFormInput, unknown, BudgetFormValues>({
    resolver: zodResolver(BudgetSchema),
    defaultValues: { basis: 'HOURS', amount: '', currency: 'EUR' },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (!budget) return;
    setConfirming(false);
    reset({
      basis: budget.basis,
      amount:
        budget.basis === 'MONEY' ?
          budget.amountMoney === null ? ''
          : String(budget.amountMoney.amount)
        : budget.amountHours === null ? ''
        : String(budget.amountHours),
      currency: budget.amountMoney?.currency ?? 'EUR',
    });
  }, [budget, reset]);

  const basis = watch('basis');
  const pending = save.isPending || clear.isPending;
  /** A project that has never had a budget arrives with both ceilings null — see `useBudgets`. */
  const hasBudget = budget !== null && (budget.amountHours !== null || budget.amountMoney !== null);

  const submit = handleSubmit(async (values) => {
    try {
      await save.mutateAsync({
        basis: values.basis,
        amount: values.amount,
        // Forbidden by `ck_budget_shape` on an HOURS basis, so it is not sent rather than sent empty.
        currency: values.basis === 'MONEY' ? values.currency.toUpperCase() : null,
      });
      onClose();
    } catch (error) {
      setError('root', { type: 'server', message: describeError(error).body });
    }
  });

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-md" data-testid={TEST_ID}>
        <form onSubmit={submit} noValidate aria-busy={pending}>
          <DialogHeader>
            <DialogTitle>{budget ? `Budget — ${budget.projectName}` : 'Budget'}</DialogTitle>
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
              label="Measured in"
              htmlFor="budget-basis"
              error={errors.basis?.message}
              help="Whichever this is, the project’s own screens show the burn as a percentage and never a rate."
            >
              <NativeSelect id="budget-basis" data-testid={`${TEST_ID}.Basis`} {...register('basis')}>
                <option value="HOURS">{budgetBasisLabels.HOURS}</option>
                <option value="MONEY">{budgetBasisLabels.MONEY}</option>
              </NativeSelect>
            </Field>

            <Field
              label={basis === 'MONEY' ? 'Ceiling' : 'Ceiling in hours'}
              htmlFor="budget-amount"
              error={errors.amount?.message}
            >
              <Input
                id="budget-amount"
                type="number"
                min="0"
                step={basis === 'MONEY' ? '0.01' : '0.5'}
                inputMode="decimal"
                aria-invalid={!!errors.amount}
                data-testid={`${TEST_ID}.Amount`}
                {...register('amount')}
              />
            </Field>

            {/*
              Present exactly when the basis requires it, and gone otherwise — the database's own
              rule rendered as the form's shape rather than as a validation message after the fact.
            */}
            {basis === 'MONEY' && (
              <Field
                label="Currency"
                htmlFor="budget-currency"
                error={errors.currency?.message}
                help="The ceiling’s own currency. It does not have to be the client’s — the burn is a percentage either way."
              >
                <Input
                  id="budget-currency"
                  autoComplete="off"
                  maxLength={3}
                  className="w-24 uppercase"
                  aria-invalid={!!errors.currency}
                  data-testid={`${TEST_ID}.Currency`}
                  {...register('currency')}
                />
              </Field>
            )}

            {basis === 'MONEY' && budget?.client == null && (
              <Banner tone="warn" data-testid={`${TEST_ID}.NoClient`}>
                <span>
                  This project has no client, so it has no rate card and its hours cannot be priced.
                  A money ceiling on it will burn at <strong>unknown</strong> rather than at zero —
                  which is honest, and probably not what you want. An hours ceiling measures this
                  project perfectly well.
                </span>
              </Banner>
            )}
          </div>

          {confirming && budget && (
            <div className="mt-4" data-testid={`${TEST_ID}.ConfirmClear`}>
              <Banner tone="bad">
                <div className="min-w-0">
                  <p className="m-0 text-[13px] font-semibold text-ink">
                    {`Remove the budget from ${budget.projectName}?`}
                  </p>
                  <p className="mt-1 mb-3">
                    The project keeps every hour anybody logged against it. What goes is the
                    ceiling: its overview stops drawing a burn panel and its General settings read
                    <em> no budget set</em>, which is an ordinary state and not a warning.
                  </p>
                  <div className="flex gap-2">
                    <Button
                      variant="danger"
                      size="sm"
                      disabled={pending}
                      onClick={() => {
                        void clear.mutateAsync(budget.projectId).then(onClose, (error: unknown) => {
                          setConfirming(false);
                          setError('root', { type: 'server', message: describeError(error).body });
                        });
                      }}
                      data-testid={`${TEST_ID}.ConfirmClear.Confirm`}
                    >
                      {`Remove the budget from ${budget.projectName}`}
                    </Button>
                    <Button size="sm" onClick={() => setConfirming(false)} data-testid={`${TEST_ID}.ConfirmClear.Cancel`}>
                      Keep it
                    </Button>
                  </div>
                </div>
              </Banner>
            </div>
          )}

          <DialogFooter>
            {hasBudget && !confirming && (
              <Button
                variant="danger"
                disabled={pending}
                onClick={() => setConfirming(true)}
                data-testid={`${TEST_ID}.Clear`}
              >
                Remove budget
              </Button>
            )}
            <span className="flex-1" />
            <Button onClick={onClose} data-testid={`${TEST_ID}.Cancel`}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" disabled={pending} data-testid={`${TEST_ID}.Submit`}>
              {pending ? 'Saving…' : 'Save budget'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
