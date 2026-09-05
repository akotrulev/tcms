import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useBudgets } from '../../api/queries';
import type { Budget } from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatHours, formatPercent } from '../../lib/format';
import { BudgetDialog } from './BudgetDialog';
import { formatMoney } from './money';

/**
 * `billing.html`'s *Project budgets* table — the **money** view of a ceiling, on `billing:read`.
 *
 * <h2>Every project, including the ones with no budget and no client</h2>
 *
 * The mockup draws *Internal Platform · None (internal) · budget — · 126h approved · no budget set*
 * in the same table as three billed projects, **with no warning tone and no call to action**, and
 * that is 019 D-p rather than a styling choice: `project.client_id` is nullable forever, a company
 * that builds its own products has no clients at all, and nothing in this product may tell such a
 * company it is missing one. A row here is a project, not a problem.
 *
 * <h2>The burn is not capped at 100, and clamping it would hide the row this panel exists for</h2>
 *
 * Bergstrom Portal is 512 approved hours against a 500-hour ceiling — **102%**, drawn in a danger
 * tone with the overflow as a second segment of the meter. A `Math.min(percent, 100)` anywhere in
 * here would render it as a full green bar, which is the one reading a Finance user must never get
 * from this screen.
 *
 * <h2>`null` burn is *unknown*, and it is not zero</h2>
 *
 * A `MONEY` ceiling on a project whose client has no rate card cannot be priced, so `burnPercent`
 * comes back `null`. Zero would read as *nothing spent* on a project that has spent a quarter, so
 * the meter is absent and the cell says so in a word.
 */

const TEST_ID = 'Billing.Budgets';

export interface BudgetPanelProps {
  /** `billing:read` — the money view. Gated rather than allowed to 403. */
  canRead: boolean;
  /** `billing:budget`, at COMPANY scope. Enables the row control; never hides it. */
  canSet: boolean;
  /** The gate's own sentence, so a disabled control's tooltip and the server's refusal agree. */
  reason: string | undefined;
}

export function BudgetPanel({ canRead, canSet, reason }: BudgetPanelProps) {
  const budgets = useBudgets({ enabled: canRead });
  const [editing, setEditing] = useState<Budget | null>(null);

  return (
    <>
      <Panel title="Project budgets" testId={TEST_ID}>
        <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...budgets, refetch: () => void budgets.refetch() }}
          isEmpty={(data) => data.length === 0}
          skeleton={<GridSkeleton rows={4} />}
          empty={{
            kind: 'no-records',
            title: 'No projects yet',
            body: 'A budget is a ceiling on a project, so this table fills itself as projects are created. Setting one is optional — a project with no budget still records every hour against it.',
          }}
        >
          {(rows) => (
            <Table data-testid={`${TEST_ID}.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Project</TableHeaderCell>
                  <TableHeaderCell>Client</TableHeaderCell>
                  <TableHeaderCell>Budget</TableHeaderCell>
                  <TableHeaderCell align="right">Approved</TableHeaderCell>
                  <TableHeaderCell>Burn</TableHeaderCell>
                  <TableHeaderCell>
                    <span className="sr-only">Set</span>
                  </TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((budget) => (
                  <TableRow key={budget.projectId} data-testid={`${TEST_ID}.Row.${budget.projectId}`}>
                    <TableCell>
                      <RouterLink
                        className="text-ink underline-offset-2 hover:underline"
                        to={`/projects/${budget.projectId}`}
                      >
                        {budget.projectName}
                      </RouterLink>
                    </TableCell>
                    <TableCell>
                      {/*
                        `None (internal)` in the ghost tone, exactly as the mockup words it, and
                        with no warning and no prompt beside it — 019 D-p. The em dash this app
                        uses for an absent value would be wrong here: this is not a missing client,
                        it is a project that has none.
                      */}
                      {budget.client ?
                        budget.client.name
                      : <span className="text-ink-4" data-testid={`${TEST_ID}.Internal.${budget.projectId}`}>
                          None (internal)
                        </span>
                      }
                    </TableCell>
                    <TableCell className="font-mono tabular-nums">{ceilingOf(budget)}</TableCell>
                    <TableCell align="right" className="font-mono tabular-nums">
                      {formatHours(budget.approvedHours)}
                    </TableCell>
                    <TableCell className="w-[190px]">
                      <BurnMeter
                        percent={budget.burnPercent}
                        hasBudget={budget.amountHours !== null || budget.amountMoney !== null}
                        testId={`${TEST_ID}.Burn.${budget.projectId}`}
                      />
                    </TableCell>
                    <TableCell align="right">
                      {/* Disabled with a why-tooltip rather than hidden (§31). */}
                      <span title={canSet ? undefined : reason}>
                        <Button
                          size="sm"
                          disabled={!canSet}
                          onClick={() => setEditing(budget)}
                          data-testid={`${TEST_ID}.Set.${budget.projectId}`}
                        >
                          {budget.amountHours !== null || budget.amountMoney !== null ? 'Edit' : 'Set'}
                        </Button>
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DataRegion>

        <p
          className="m-0 border-t border-line px-3.5 py-2.5 text-[11.5px] leading-[1.55] text-ink-4"
          data-testid={`${TEST_ID}.Note`}
        >
          A budget can be in hours or in money. Either way the project’s own Overview shows the burn
          and never the rate.
        </p>
      </Panel>

      <BudgetDialog budget={editing} onClose={() => setEditing(null)} />
    </>
  );
}

/** The ceiling as the row shows it — `600h`, `€40,000.00`, or the em dash for a project with none. */
function ceilingOf(budget: Budget): string {
  if (budget.basis === 'MONEY' && budget.amountMoney) return formatMoney(budget.amountMoney);
  if (budget.basis === 'HOURS' && budget.amountHours !== null) return formatHours(budget.amountHours);
  return '—';
}

export interface BurnMeterProps {
  /** `null` is *unknown* — a money ceiling nothing can price. Never rendered as zero. */
  percent: number | null;
  /** Whether there is a ceiling at all. Without one there is no burn to draw, only hours. */
  hasBudget: boolean;
  testId: string;
}

/**
 * The meter, and **the overflow is a second segment rather than a clipped bar**.
 *
 * At or under the ceiling the whole bar is the burn. Over it, the track is the *whole* overrun and
 * the green share is `100 / percent` of it — so 102% draws as a nearly-full green bar with a thin
 * red tail, and 200% draws as half and half. The alternative, clamping the fill at 100%, makes
 * every overrun look identical to a project that landed exactly on budget.
 *
 * Exported because `invoice.html`'s *Budget effect* panel draws the same figure for the projects an
 * invoice touches, and two meters that disagreed about how an overrun looks would be worse than
 * either.
 *
 * The colours come through inline `var(--color-…)` rather than a utility class because the status
 * palette is not bridged into Tailwind's names — `kit/Badge`'s `style` escape hatch records the
 * same constraint, and `OverAllocatedPanel` is the precedent for reading `--color-danger` this way.
 */
export function BurnMeter({ percent, hasBudget, testId }: BurnMeterProps) {
  if (!hasBudget) {
    return (
      <span className="text-[12px] text-ink-4" data-testid={`${testId}.None`}>
        no budget set
      </span>
    );
  }
  if (percent === null) {
    return (
      <span
        className="text-[12px] text-ink-4"
        title="A money ceiling on a project whose client has no rate card. The hours are recorded; nothing can price them, so the burn is unknown rather than zero."
        data-testid={`${testId}.Unknown`}
      >
        unknown
      </span>
    );
  }

  const over = percent > 100;
  const track = Math.max(percent, 100);
  const done = (Math.min(percent, 100) / track) * 100;

  return (
    <span className="flex items-center gap-2" data-testid={testId}>
      <span className="flex h-1.5 flex-1 overflow-hidden rounded-full bg-wash-3">
        <i
          className="block h-full"
          style={{ width: `${done}%`, backgroundColor: 'var(--color-success)' }}
        />
        {over && (
          <i
            className="block h-full"
            style={{ width: `${100 - done}%`, backgroundColor: 'var(--color-danger)' }}
          />
        )}
      </span>
      <span
        className="font-mono text-[11px] tabular-nums"
        style={over ? { color: 'var(--color-danger)' } : undefined}
        data-testid={`${testId}.Percent`}
      >
        {formatPercent(percent, 0)}
      </span>
    </span>
  );
}
