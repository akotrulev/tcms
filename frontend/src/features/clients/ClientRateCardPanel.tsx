import { Link as RouterLink } from 'react-router-dom';
import { Lock } from 'lucide-react';
import { useRateCards } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { Button, Skeleton } from '../../components/ui';
import { plural } from '../../lib/format';

const TEST_ID = 'Client.RateCard';

/**
 * `client.html`'s *Rate card* panel — **a link into Billing, never a second editor**.
 *
 * <h2>The refusal is the panel, for the reader the mockup drew it for</h2>
 *
 * `client.html` renders this for an Account Manager as *"Not visible to you — Rates are Finance's.
 * An Account Manager knows the client and the hours; never the money."* 018 left it out entirely,
 * because with no rate card anywhere in the product that copy would have been a promise about a
 * screen nobody could reach. M8 built the screen, so the sentence is now a statement about a
 * boundary that exists — which is the kind that is worth rendering.
 *
 * <h2>One place edits a rate card, and it is `/billing`</h2>
 *
 * For a caller holding `ratecard:read` this shows the card's currency and how many rungs it
 * carries, and links to the Billing screen T5 built. A second editor here would be a second place
 * the ladder's *person beats role beats all-roles* rule is expressed (019 D-d), and two dialogs
 * writing one full-replacement `PUT` is how a rung disappears without anybody removing it.
 *
 * <h2>A client with no card is a row on that screen and a sentence here</h2>
 *
 * It is the state every new client is in, and nothing about it is a deficiency: approved hours are
 * still recorded against the client's projects, and they are counted and named on every draft
 * rather than dropped (019 D-g). No warning tone, and the call to action is only there for
 * somebody who could act on it.
 *
 * There is no `GET /clients/{id}/rate-card`, so this reads `GET /rate-cards` — every active client
 * and its card or `null` — and picks this client's slot out of it. That is one cached answer
 * shared with the Billing screen rather than a second endpoint for one row.
 */
export function ClientRateCardPanel({ clientId }: { clientId: string }) {
  const cards = useRateCards();

  return (
    <Panel title="Rate card" testId={TEST_ID}>
      <DataRegion
        testId={`${TEST_ID}.Region`}
        state={{ ...cards, refetch: () => void cards.refetch() }}
        skeleton={<Skeleton className="h-[64px] rounded-lg" />}
      >
        {(slots) => {
          const card = slots.find((slot) => slot.client.id === clientId)?.card ?? null;
          return (
            <div className="flex flex-col gap-2.5" data-testid={`${TEST_ID}.Body`}>
              {card ?
                <>
                  <span
                    className="font-mono text-[20px] leading-[1.1] font-medium tabular-nums text-ink"
                    data-testid={`${TEST_ID}.Currency`}
                  >
                    {card.currency}
                  </span>
                  <span className="text-[12.5px] text-ink-2">
                    {plural(card.entries.length, 'rate')} on this client’s card
                  </span>
                </>
              : <span className="text-[12.5px] text-ink-3" data-testid={`${TEST_ID}.None`}>
                  No rate card yet. Hours against this client’s projects are still recorded and
                  still approved — they cannot be priced until a card exists, and every draft
                  invoice counts and names them rather than dropping them.
                </span>
              }

              <div>
                <Button asChild size="sm" data-testid={`${TEST_ID}.Open`}>
                  <RouterLink to="/billing">{card ? 'Open in Billing' : 'Set one in Billing'}</RouterLink>
                </Button>
              </div>

              <span className="text-[11.5px] leading-[1.55] text-ink-4">
                A person’s rate wins over their role’s, and the ladder is read as of the day worked.
                Billing is where it is set.
              </span>
            </div>
          );
        }}
      </DataRegion>
    </Panel>
  );
}

/**
 * The same panel for a reader without `ratecard:read` — the mockup's own empty state, word for
 * word.
 *
 * Its own component rather than a branch inside the one above, so the refusal fires no request:
 * every read in this module is `enabled`-gated on the permission rather than allowed to 403 (see
 * `api/queries/billing.ts`' header), and a hook called with `enabled: false` inside a component
 * that renders none of its data is the shape that quietly becomes a live request on the next edit.
 */
export function ClientRateCardRefusal() {
  return (
    <Panel title="Rate card" testId={`${TEST_ID}.Refused`}>
      <div className="flex flex-col items-start gap-2" data-testid={`${TEST_ID}.RefusedBody`}>
        <Lock aria-hidden className="size-4 text-ink-4" />
        <h3 className="m-0 text-[13px] font-semibold text-ink">Not visible to you</h3>
        <p className="m-0 max-w-[46ch] text-[12.5px] leading-[1.55] text-ink-3">
          Rates are Finance’s. An Account Manager knows the client and the hours; never the money.
        </p>
      </div>
    </Panel>
  );
}
