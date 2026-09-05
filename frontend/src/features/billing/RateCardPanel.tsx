import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Pencil } from 'lucide-react';
import { useRateCards } from '../../api/queries';
import type { RateCardEntry, RateCardSlot } from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { rateCardScopeLabels } from '../../lib/labels';
import { RateCardDialog } from './RateCardDialog';
import { formatRate } from './money';

/**
 * `billing.html`'s *Rate cards* panel — one row per rung, and one row per client that has none.
 *
 * <h2>A client with no card is a row, not a gap</h2>
 *
 * `GET /rate-cards` answers a `RateCardSlot[]`: every active client, with its card **or `null`**.
 * The mockup's *Kessler Health · no rate card yet · — · — · Set* is that null, and it is the state
 * every new client is in. A panel that listed only the cards would render a company's newest
 * customers as though they did not exist, which is the one thing a Finance user opens this to
 * check.
 *
 * <h2>Its own `DataRegion`, like every other panel on this screen</h2>
 *
 * 017 D-m's rule: a summary outage must not empty the invoice list, and a rate-card outage must not
 * empty the budget table beside it. Each panel owns its failure, so a failed read here leaves the
 * rest of the screen exactly where it was.
 *
 * <h2>The effective date is on the entry, and the column proves it</h2>
 *
 * `billing.html` carries two Acme roles *from 1 Jan 2026* and one Acme person *from 1 Jul 2026*,
 * which is why the date lives on the rung rather than on the card (019 D-d). A rise mid-year is a
 * second entry, and the ladder is evaluated **as of the day worked** — so an invoice for August
 * prices August's hours at August's rate whatever the card says today.
 */

const TEST_ID = 'Billing.RateCards';

export interface RateCardPanelProps {
  /** `ratecard:read`. Gated rather than allowed to 403 — see `api/queries/billing.ts`' header. */
  canRead: boolean;
  /** `ratecard:manage`. Decides whether the row controls are enabled, never whether they render. */
  canManage: boolean;
  /** The gate's own sentence, so a disabled control's tooltip and the server's refusal agree. */
  reason: string | undefined;
}

/** One rendered line: a rung of a card, or the whole absence of one. */
interface Row {
  key: string;
  slot: RateCardSlot;
  /** The rung, or `null` for a client with no card and for a card with no rungs yet. */
  entry: RateCardEntry | null;
}

export function RateCardPanel({ canRead, canManage, reason }: RateCardPanelProps) {
  const cards = useRateCards(canRead);
  const [editing, setEditing] = useState<RateCardSlot | null>(null);
  const [creating, setCreating] = useState(false);

  const slots = cards.data ?? [];
  const unset = slots.filter((slot) => slot.card === null).map((slot) => slot.client);

  return (
    <>
      <Panel
        title="Rate cards"
        testId={TEST_ID}
        actions={
          <span
            title={
              !canManage ? reason
              : unset.length === 0 ?
                'Every client already has a rate card. Edit one from its row.'
              : undefined
            }
          >
            <Button
              size="sm"
              disabled={!canManage || unset.length === 0}
              onClick={() => setCreating(true)}
              data-testid={`${TEST_ID}.New`}
            >
              New rate card
            </Button>
          </span>
        }
      >
        <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...cards, refetch: () => void cards.refetch() }}
          isEmpty={(data) => data.length === 0}
          skeleton={<GridSkeleton rows={3} />}
          empty={{
            kind: 'no-records',
            title: 'No clients to price',
            body: 'A rate card belongs to a client, and this company has none recorded. Nothing here is missing — a company that builds its own products never records one, and its hours are still logged and still roll up to its projects.',
          }}
        >
          {(loaded) => (
            <Table data-testid={`${TEST_ID}.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Client</TableHeaderCell>
                  <TableHeaderCell>Applies to</TableHeaderCell>
                  <TableHeaderCell align="right">Rate</TableHeaderCell>
                  <TableHeaderCell>Effective</TableHeaderCell>
                  <TableHeaderCell>
                    <span className="sr-only">Edit</span>
                  </TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rowsOf(loaded).map((row) => (
                  <TableRow key={row.key} data-testid={`${TEST_ID}.Row.${row.key}`}>
                    <TableCell>
                      <RouterLink
                        className="text-ink underline-offset-2 hover:underline"
                        to={`/clients/${row.slot.client.id}`}
                      >
                        {row.slot.client.name}
                      </RouterLink>
                    </TableCell>
                    <TableCell>
                      {row.entry ?
                        <span>
                          {row.entry.appliesTo}
                          {row.entry.scope !== 'ALL_ROLES' && (
                            <span className="ml-1.5 text-ink-4">{`(${rateCardScopeLabels[row.entry.scope]})`}</span>
                          )}
                        </span>
                      : <span className="text-ink-4">
                          {row.slot.card ? 'no rates on this card yet' : 'no rate card yet'}
                        </span>
                      }
                    </TableCell>
                    <TableCell align="right" className="font-mono tabular-nums">
                      {row.entry && row.slot.card ?
                        formatRate(row.entry.hourlyRate, row.slot.card.currency)
                      : <span className="text-ink-4">—</span>}
                    </TableCell>
                    <TableCell>
                      {row.entry ?
                        `from ${formatDate(row.entry.effectiveFrom)}`
                      : <span className="text-ink-4">—</span>}
                    </TableCell>
                    <TableCell align="right">
                      {/*
                        Disabled with a why-tooltip rather than hidden (§31). A reader without
                        `ratecard:manage` still holds `ratecard:read` — that is the whole reason
                        this panel rendered for them — so an absent control would tell them the
                        product cannot do this rather than that they may not.
                      */}
                      <span title={canManage ? undefined : reason}>
                        {row.slot.card ?
                          <Button
                            size="icon"
                            variant="quiet"
                            disabled={!canManage}
                            onClick={() => setEditing(row.slot)}
                            aria-label={`Edit the rate card for ${row.slot.client.name}`}
                            data-testid={`${TEST_ID}.Edit.${row.slot.client.id}`}
                          >
                            <Pencil className="size-4" />
                          </Button>
                        : <Button
                            size="sm"
                            disabled={!canManage}
                            onClick={() => setEditing(row.slot)}
                            data-testid={`${TEST_ID}.Set.${row.slot.client.id}`}
                          >
                            Set
                          </Button>
                        }
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
          A person’s rate wins over their role’s rate. Without a card, approved hours are still
          recorded — they just cannot be priced, and they are counted and named on every draft
          rather than dropped.
        </p>
      </Panel>

      {/*
        Mounted only while it is open, and that is not a tidiness preference: the dialog reads
        `/users` and `/roles` to populate its two rungs, so a permanently mounted one would make two
        requests on every load of this screen for a form nobody opened. TanStack keeps both answers
        past the unmount, so reopening is still warm.
      */}
      {(editing !== null || creating) && (
        <RateCardDialog
          open
          slot={editing}
          unset={unset}
          onClose={() => {
            setEditing(null);
            setCreating(false);
          }}
        />
      )}
    </>
  );
}

/**
 * The slots, flattened to one line per rung — and **one line for a client with none**.
 *
 * A card with an empty ladder gets a line too, reading *no rates on this card yet*: that state is
 * reachable (nothing stops a card being saved and its rungs removed later on the server), and a
 * client that silently vanished from this table because its card had no rows would be the worst of
 * the three — the panel would look complete while missing a customer.
 */
function rowsOf(slots: readonly RateCardSlot[]): Row[] {
  return slots.flatMap((slot): Row[] => {
    const entries = slot.card?.entries ?? [];
    if (entries.length === 0) return [{ key: slot.client.id, slot, entry: null }];
    return entries.map((entry) => ({ key: `${slot.client.id}:${entry.id}`, slot, entry }));
  });
}
