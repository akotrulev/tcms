import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { useDiscardInvoice, useInvoice, useIssueInvoice } from '../../api/queries';
import type { Budget, InvoiceDetail } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Banner } from '../../components/kit';
import { Button } from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { formatDate, formatHours, plural } from '../../lib/format';
import { invoiceStatusLabels, invoiceStatusTones } from '../../lib/labels';
import { BurnMeter } from './BudgetPanel';
import { InvoiceEntries } from './InvoiceEntries';
import { InvoiceLines } from './InvoiceLines';
import { formatMoney } from './money';
import { formatMonthRange } from './months';

/**
 * `invoice.html`, **both states** — a draft and an issued invoice are one screen.
 *
 * <h2>An issued invoice renders read-only, and "read-only" means the controls are gone</h2>
 *
 * No Issue, no Discard, no edit affordance anywhere, and the number where the draft says *assigned
 * on issue*. This is the one place in this app that deliberately does not follow §31's
 * disable-don't-hide, and the reason is that the actions are not refused — they **do not exist**.
 * There is no operation in this module that can change an issued line (019 D-c): the lock is
 * structural rather than guarded, so a greyed *Issue invoice* button would be inventing a state
 * machine the server does not have.
 *
 * The lines themselves are stored rows carrying the rate **as applied**, so this screen shows the
 * same figures a month later after the rate card has moved — which is the point of issuing.
 *
 * <h2>Preview PDF is not built</h2>
 *
 * The mockup draws the button. There is no endpoint behind it and none is planned (019 D-q), so it
 * is **absent rather than drawn-and-inert** — `ApprovalsPage` records the same decision for its
 * `Approve all` and `Remind`, and a control that does nothing is worse than one that is not there.
 *
 * <h2>Both exclusion buckets are rendered, and neither is a footnote</h2>
 *
 * The amber note carries the unapproved hours with their names, exactly as the mockup does, and
 * links to the approvals queue. The second bucket — approved hours no rate card can price — is a
 * second note in the same place, which the mockup does not draw because its example company has a
 * card for every client. It is not optional: an hour that vanished from a draft with no explanation
 * is the defect this module exists to remove (019 D-g). Both are also rows in the Lines table, so
 * the hours reconcile in one column.
 *
 * <h2>A draft recalculates, and nothing on this screen refreshes it</h2>
 *
 * There is no recalculate button and no staleness flag, because a draft stores no lines: every read
 * recomputes them from the approved entries that exist now. *"Approve them and this draft
 * recalculates"* is therefore true for free — the reader approves a period on another screen and
 * this one is right the next time it is opened.
 *
 * Two drafts open over an overlapping period for one client will both offer the same hours, and
 * whichever issues first takes them. That is visible rather than silent: the second recomputes on
 * its next read and the hours are gone from it.
 */

const TEST_ID = 'Invoice';

export function InvoicePage() {
  const { invoiceId } = useParams<{ invoiceId: string }>();
  const navigate = useNavigate();
  const gate = useCanInCompany();
  const allowed = gate.can('billing:read');
  const invoice = useInvoice(allowed ? invoiceId : undefined);

  const issue = useIssueInvoice();
  const discard = useDiscardInvoice();
  const [confirmingDiscard, setConfirmingDiscard] = useState(false);

  const loaded = invoice.data;
  const isDraft = loaded?.invoice.status === 'DRAFT';
  const writing = issue.isPending || discard.isPending;

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title={titleOf(loaded)}
        documentTitle={loaded?.invoice.number ?? 'Draft invoice'}
        subtitle="Every line is approved time at the rate-card rate. Billing is a report, not a spreadsheet."
        meta={
          loaded && (
            <span className="flex items-center gap-2">
              <Badge tone={invoiceStatusTones[loaded.invoice.status]}>
                {invoiceStatusLabels[loaded.invoice.status]}
              </Badge>
              <Identifier variant="muted">
                {`${loaded.invoice.client.name} · ${formatMonthRange(loaded.invoice.periodStart, loaded.invoice.periodEnd)}`}
              </Identifier>
            </span>
          )
        }
        actions={
          /*
            Rendered only for a DRAFT. See this file's header: an issued invoice's controls are
            absent rather than disabled, because the operations do not exist rather than being
            refused — and `Preview PDF` is absent in both states for the same kind of reason.
          */
          isDraft && loaded ?
            <>
              <span title={gate.can('billing:invoice') ? undefined : gate.reason('billing:invoice')}>
                <Button
                  size="sm"
                  variant="danger"
                  disabled={!gate.can('billing:invoice') || writing}
                  onClick={() => setConfirmingDiscard(true)}
                  data-testid={`${TEST_ID}.Discard`}
                >
                  Discard
                </Button>
              </span>
              <span title={gate.can('billing:issue') ? undefined : gate.reason('billing:issue')}>
                <Button
                  size="sm"
                  variant="primary"
                  disabled={!gate.can('billing:issue') || writing}
                  onClick={() => issue.mutate(loaded.invoice.id)}
                  data-testid={`${TEST_ID}.Issue`}
                >
                  {issue.isPending ? 'Issuing…' : 'Issue invoice'}
                </Button>
              </span>
            </>
          : undefined
        }
      />

      {!allowed ?
        <Panel testId={`${TEST_ID}.Denied`}>
          <p className="m-0 text-[13px] text-ink-3" data-testid={`${TEST_ID}.DeniedText`}>
            {gate.pending ?
              'Checking your permissions…'
            : 'Invoices are visible to Finance and Company Admins.'}
          </p>
        </Panel>
      : <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...invoice, refetch: () => void invoice.refetch() }}
          skeleton={<GridSkeleton rows={6} />}
        >
          {(detail) => (
            <div className="flex flex-col gap-4">
              {(issue.isError || discard.isError) && (
                <Banner tone="bad" data-testid={`${TEST_ID}.WriteError`}>
                  <span role="alert">{describeError(issue.error ?? discard.error).body}</span>
                </Banner>
              )}

              {confirmingDiscard && detail.invoice.status === 'DRAFT' && (
                <Banner tone="bad" data-testid={`${TEST_ID}.ConfirmDiscard`}>
                  <div className="min-w-0">
                    <p className="m-0 text-[13px] font-semibold text-ink">
                      {`Discard this draft for ${detail.invoice.client.name}?`}
                    </p>
                    <p className="mt-1 mb-3">
                      Nothing is lost that anybody logged: a draft holds no lines of its own, so
                      discarding it only puts these hours back in reach of the next draft. There is
                      no undo, and the draft itself is not recoverable.
                    </p>
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        variant="danger"
                        disabled={writing}
                        onClick={() => {
                          void discard.mutateAsync(detail.invoice.id).then(
                            () => navigate('/billing'),
                            () => setConfirmingDiscard(false),
                          );
                        }}
                        data-testid={`${TEST_ID}.ConfirmDiscard.Confirm`}
                      >
                        {`Discard the draft for ${detail.invoice.client.name}`}
                      </Button>
                      <Button
                        size="sm"
                        onClick={() => setConfirmingDiscard(false)}
                        data-testid={`${TEST_ID}.ConfirmDiscard.Cancel`}
                      >
                        Keep it
                      </Button>
                    </div>
                  </div>
                </Banner>
              )}

              <ExclusionNotes detail={detail} />

              <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
                <div className="flex flex-col gap-4">
                  <Panel title="Lines" testId={`${TEST_ID}.Lines`}>
                    <InvoiceLines
                      lines={detail.lines}
                      excluded={detail.excluded}
                      total={detail.invoice.total}
                      testId={`${TEST_ID}.Lines.Table`}
                    />
                  </Panel>

                  <Panel
                    title={`Time entries behind these lines (${detail.entries.length})`}
                    testId={`${TEST_ID}.Entries`}
                  >
                    <InvoiceEntries entries={detail.entries} testId={`${TEST_ID}.Entries.Table`} />
                    <p
                      className="m-0 mt-3 border-t border-line pt-2.5 text-[11.5px] leading-[1.55] text-ink-4"
                      data-testid={`${TEST_ID}.Entries.Note`}
                    >
                      {detail.invoice.status === 'ISSUED' ?
                        'These lines are locked. Their rates are the rates as applied on the day this invoice was issued, so a rate card changed since does not move them.'
                      : 'Once issued, these lines lock — they become stored rows carrying the rate as applied, and nothing afterwards can change one.'}
                    </p>
                  </Panel>
                </div>

                <div className="flex flex-col gap-4">
                  <Panel title="Invoice" testId={`${TEST_ID}.Summary`}>
                    <dl className="m-0 grid grid-cols-[110px_minmax(0,1fr)] items-center gap-x-3 gap-y-2.5 text-[12.5px]">
                      <dt className="text-[11.5px] text-ink-3">Client</dt>
                      <dd className="m-0">
                        <RouterLink
                          className="text-ink underline-offset-2 hover:underline"
                          to={`/clients/${detail.invoice.client.id}`}
                        >
                          {detail.invoice.client.name}
                        </RouterLink>
                      </dd>

                      <dt className="text-[11.5px] text-ink-3">Bill to</dt>
                      <dd className="m-0" data-testid={`${TEST_ID}.BillTo`}>
                        {detail.billTo ?
                          <>
                            {detail.billTo.name}
                            {detail.billTo.email && (
                              <span className="mt-0.5 block font-mono text-[11px] text-ink-4">
                                {detail.billTo.email}
                              </span>
                            )}
                          </>
                        : /*
                            A client with no contact recorded is a state, not a blocker: issuing
                            sends nothing, so there is nothing an absent address prevents. Said in
                            a sentence rather than left as an em dash, which would read as a field
                            that failed to load.
                          */
                          <span className="text-ink-4">
                            No contact recorded. Issuing sends nothing, so nothing is blocked.
                          </span>
                        }
                      </dd>

                      <dt className="text-[11.5px] text-ink-3">Period</dt>
                      <dd className="m-0">
                        {formatMonthRange(detail.invoice.periodStart, detail.invoice.periodEnd)}
                      </dd>

                      <dt className="text-[11.5px] text-ink-3">Number</dt>
                      <dd className="m-0" data-testid={`${TEST_ID}.Number`}>
                        {detail.invoice.number ?
                          <Identifier variant="strong">{detail.invoice.number}</Identifier>
                        : <span className="font-mono text-ink-4">assigned on issue</span>}
                      </dd>

                      <dt className="text-[11.5px] text-ink-3">Currency</dt>
                      <dd className="m-0 font-mono">{detail.invoice.currency}</dd>

                      <dt className="text-[11.5px] text-ink-3">Terms</dt>
                      <dd className="m-0">{`${detail.termsDays} days`}</dd>

                      {detail.invoice.issuedAt && (
                        <>
                          <dt className="text-[11.5px] text-ink-3">Issued</dt>
                          <dd className="m-0" data-testid={`${TEST_ID}.Issued`}>
                            {`${formatDate(detail.invoice.issuedAt)}${detail.invoice.issuedBy ? ` · ${detail.invoice.issuedBy}` : ''}`}
                          </dd>
                        </>
                      )}

                      <dt className="text-[11.5px] text-ink-3">Total</dt>
                      <dd className="m-0 font-mono text-[14px] text-ink">
                        {formatMoney(detail.invoice.total)}
                      </dd>
                    </dl>
                    <p
                      className="m-0 mt-3 border-t border-line pt-2.5 text-[11.5px] leading-[1.55] text-ink-4"
                      data-testid={`${TEST_ID}.Summary.Note`}
                    >
                      Issuing locks the lines. It does not send anything — sending is your mail
                      client’s job.
                    </p>
                  </Panel>

                  {/*
                    The budgets this invoice moves, as they stand. Empty when none of the projects
                    it touches has one, which is an ordinary answer — a project with no budget is
                    not a project in a deficient state — so the panel is absent rather than showing
                    a heading over nothing.
                  */}
                  {detail.budgetEffect.length > 0 && (
                    <Panel title="Budget effect" testId={`${TEST_ID}.BudgetEffect`}>
                      <div className="flex flex-col gap-3">
                        {detail.budgetEffect.map((budget) => (
                          <BudgetEffectRow key={budget.projectId} budget={budget} />
                        ))}
                      </div>
                    </Panel>
                  )}
                </div>
              </div>
            </div>
          )}
        </DataRegion>
      }
    </div>
  );
}

/** `Draft invoice` until it is issued, then its number — the heading the mockup draws. */
function titleOf(detail: InvoiceDetail | undefined): string {
  if (!detail) return 'Invoice';
  return detail.invoice.number ?? 'Draft invoice';
}

/**
 * The two exclusion buckets, above the tables and never below them.
 *
 * 019 D-g: a draft reports three populations and silently drops none. The amber note is the
 * mockup's own, and the second one is the bucket the mockup does not draw because its example
 * company has a card for every client. Both carry hours and a reason; neither carries an amount,
 * because neither has one.
 */
function ExclusionNotes({ detail }: { detail: InvoiceDetail }) {
  if (detail.excluded.length === 0) return null;

  return (
    <div className="flex flex-col gap-2" data-testid={`${TEST_ID}.Excluded`}>
      {detail.excluded.map((bucket) => (
        <Banner key={bucket.reason} tone="warn" data-testid={`${TEST_ID}.Excluded.${bucket.reason}`}>
          <div className="min-w-0">
            {bucket.reason === 'NOT_APPROVED' ?
              <>
                <strong className="font-semibold text-ink">
                  {`${formatHours(bucket.hours)} are excluded because they are not approved.`}
                </strong>{' '}
                {bucket.detail && <span>{`${bucket.detail}. `}</span>}
                <span>
                  They are counted here so nobody wonders where they went.{' '}
                  <RouterLink
                    to="/timesheets/approvals"
                    className="text-steel underline-offset-4 hover:underline"
                  >
                    Approve them
                  </RouterLink>{' '}
                  and this draft recalculates.
                </span>
              </>
            : <>
                <strong className="font-semibold text-ink">
                  {`${formatHours(bucket.hours)} are approved but cannot be priced.`}
                </strong>{' '}
                {bucket.detail && <span>{`${bucket.detail}. `}</span>}
                <span>
                  This client’s rate card has no entry effective on those days — or there is no card
                  at all. Without one, approved hours are still recorded; they just cannot be priced,
                  and they are counted here rather than dropped.
                </span>
              </>
            }
          </div>
        </Banner>
      ))}
    </div>
  );
}

/** One project's ceiling and its burn, on the same meter the Billing screen's table uses. */
function BudgetEffectRow({ budget }: { budget: Budget }) {
  const ceiling =
    budget.basis === 'MONEY' && budget.amountMoney ? formatMoney(budget.amountMoney)
    : budget.amountHours !== null ? formatHours(budget.amountHours)
    : '—';
  const spent =
    budget.basis === 'MONEY' ?
      budget.pricedToDate ? formatMoney(budget.pricedToDate) : 'unknown'
    : formatHours(budget.approvedHours);

  return (
    <div className="flex flex-col gap-1.5" data-testid={`${TEST_ID}.BudgetEffect.${budget.projectId}`}>
      <div className="flex items-center justify-between gap-2">
        <RouterLink
          className="text-[12px] text-ink-2 underline-offset-2 hover:underline"
          to={`/projects/${budget.projectId}`}
        >
          {budget.projectName}
        </RouterLink>
        <span className="font-mono text-[11.5px] tabular-nums text-ink-3">
          {`${spent} / ${ceiling}`}
        </span>
      </div>
      <BurnMeter
        percent={budget.burnPercent}
        hasBudget={budget.amountHours !== null || budget.amountMoney !== null}
        testId={`${TEST_ID}.BudgetEffect.${budget.projectId}.Burn`}
      />
      <span className="text-[11px] text-ink-4">
        {`${plural(Math.round(budget.approvedHours), 'approved hour')} against this project so far.`}
      </span>
    </div>
  );
}
