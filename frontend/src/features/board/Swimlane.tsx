import { useDroppable } from '@dnd-kit/core';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import type { BoardIssueCard, BoardLane, SwimlaneBy } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { cn } from '../../lib/cn';
import { completionBases } from '../../lib/labels';
import { plural } from '../../lib/format';
import { BoardCardFace } from './BoardCardFace';
import type { ServerColumn } from './cardMove';

/**
 * One lane — story B3's second and third criteria.
 *
 * **Collapsing hides the cards and keeps the number.** The mockup says so on the screen: *"Collapsed
 * to a single roll-up. Nothing is hidden from the counts — only from the screen."* The percentage
 * comes from the lane's own `completion`, which the server computed over every card in it, so a
 * collapsed lane and an expanded one cannot disagree — and nothing here recomputes it, which is D8's
 * one-number-one-place applied to the one component most tempted to do its own arithmetic.
 *
 * The **"No epic" lane is last and is not an error** — the mockup states it: *"a story without an
 * epic is a normal story"*. It arrives last from the server (the contract says so), so this
 * component does not sort; what it must not do is style the catch-all as a warning, which would make
 * a normal state look like a backlog of mistakes. It gets the muted heading colour every other lane
 * label would get and no tone at all.
 *
 * The lane header carries the completion meter and its **basis** — `by estimate` or
 * `by count · 4 of 10` — because `IssueCompletion.basis` is on the wire deliberately and a
 * percentage that changes meaning silently is D8's named failure.
 *
 * ## The droppable is the cell, and the id scheme is the caller's
 *
 * A drop target on a swimlane board is a (lane, column) cell, so a column cannot be one droppable —
 * it has one rect per lane. The cell ids come in as `cellId`, which `ProjectBoardView` owns along
 * with the parsing back to a column id: one file spells the scheme, so the two halves cannot drift.
 *
 * A **collapsed lane registers nothing**, which is what makes it a non-target for both a drop and
 * the keyboard's lane step — an unmeasured band is skipped by `boardKeyboardCoordinates`, so the
 * arrows walk between the lanes a card could actually land in.
 */
export function Swimlane({
  lane,
  laneId,
  swimlaneBy,
  columns,
  cellId,
  collapsed,
  onToggle,
  onMove,
  canMove,
  moveRefusal,
  testId,
}: {
  lane: BoardLane;
  /** This lane's stable identity — `lane.key`, or the catch-all's stand-in. */
  laneId: string;
  swimlaneBy: SwimlaneBy;
  columns: readonly ServerColumn[];
  cellId: (laneId: string, columnId: string) => string;
  collapsed: boolean;
  onToggle: () => void;
  onMove: (card: BoardIssueCard, toColumnId: string) => void;
  canMove: boolean;
  moveRefusal: string | undefined;
  testId: string;
}) {
  const cards = lane.cards.length;
  const banded = swimlaneBy !== 'NONE';

  return (
    <section
      data-testid={testId}
      /*
        The label is the lane's own words, so `getAllByRole('region', { name: /lane/i })` returns
        the bands top to bottom — which is the assertion that the catch-all is last.
      */
      aria-label={banded ? `${lane.label} lane` : 'Board lane'}
      className="mb-3 last:mb-0"
    >
      {banded && (
        <div
          data-testid={`${testId}.Head`}
          className={cn(
            'mb-2 flex flex-wrap items-center gap-[9px] rounded-[6px] border border-line',
            'bg-panel px-3 py-2',
          )}
        >
          <button
            type="button"
            onClick={onToggle}
            aria-expanded={!collapsed}
            aria-label={
              collapsed ? `Expand the ${lane.label} swimlane` : `Collapse the ${lane.label} swimlane`
            }
            data-testid={`${testId}.Toggle`}
            className={cn(
              'flex size-6 flex-none items-center justify-center rounded-sm border-0 bg-transparent',
              'cursor-pointer p-0 text-ink-3 hover:text-ink',
              'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
            )}
          >
            {collapsed ? (
              <ChevronRight aria-hidden className="size-4" />
            ) : (
              <ChevronDown aria-hidden className="size-4" />
            )}
          </button>

          {/*
            An epic lane's label links to the epic; every other axis groups by something that is not
            an issue, so there is nowhere for a link to go and a fake one would be worse than none.
            `lane.key` is null on the catch-all whatever the axis, which is the other half of the
            same condition.
          */}
          {swimlaneBy === 'EPIC' && lane.key ? (
            <>
              <RouterLink
                to={`/issues/${encodeURIComponent(lane.key)}`}
                data-testid={`${testId}.Link`}
                className="text-[13px] font-semibold text-ink no-underline hover:text-steel hover:underline"
              >
                {lane.label}
              </RouterLink>
              <Identifier variant="muted" className="text-[11.5px] text-ink-3">
                {lane.key}
              </Identifier>
            </>
          ) : (
            <span
              data-testid={`${testId}.Label`}
              className={cn('text-[13px]', lane.key === null ? 'text-ink-3' : 'font-semibold text-ink')}
            >
              {lane.label}
            </span>
          )}

          <span className="flex-1" />

          {/*
            The roll-up, and it stays on screen when the lane does not — the mockup's rule. The
            percentage, the meter and the basis are three renderings of one number the server sent;
            the only expression here is the meter's width, which is that number being drawn.
          */}
          <span data-testid={`${testId}.Percent`} className="font-mono text-[11.5px] text-ink-2">
            {`${lane.completion.percent}%`}
          </span>
          <span
            aria-hidden
            className="flex h-1.5 w-[110px] flex-none overflow-hidden rounded-full bg-wash-3"
          >
            <span
              className="block h-full bg-[color:var(--color-passed)]"
              style={{ width: `${lane.completion.percent}%` }}
            />
          </span>
          <span data-testid={`${testId}.Basis`} className="text-[11px] italic text-ink-4">
            {lane.completion.basis === 'ESTIMATE'
              ? completionBases.ESTIMATE
              : `${completionBases.COUNT} · ${lane.completion.done} of ${lane.completion.total}`}
          </span>
        </div>
      )}

      {collapsed ? (
        <p
          data-testid={`${testId}.Collapsed`}
          className="m-0 pl-[14px] text-[11.5px] text-ink-4"
        >
          {`${plural(cards, 'card')} collapsed to a single roll-up. Nothing is hidden from the counts — only from the screen.`}
        </p>
      ) : (
        /*
          The lane's own grid, on the SAME `--cols` template the header row above it uses — set once
          on their shared ancestor. Two grids rather than one with `display: contents` bands: a
          `display: contents` section is a box the layout cannot see, and browsers have removed such
          elements from the accessibility tree, which would delete the one thing this section is for.
          Fixed-width columns make the two templates align without either knowing about the other.
        */
        <div className="grid items-start gap-3 [grid-template-columns:repeat(var(--board-columns),272px)]">
          {columns.map((column) => (
            <LaneCell
              key={column.id}
              id={cellId(laneId, column.id)}
              cards={lane.cards.filter((card) => card.columnId === column.id)}
              columns={columns}
              onMove={onMove}
              canMove={canMove}
              moveRefusal={moveRefusal}
              testId={`${testId}.Cell.${column.name}`}
            />
          ))}
        </div>
      )}
    </section>
  );
}

/**
 * One (lane, column) cell — the thing a card is dropped **on**.
 *
 * A component rather than a `map` body because `useDroppable` is a hook, which is the same reason
 * the composite board's `BoardColumn` exists.
 *
 * The cards are sorted by `rank`, which is a decimal **as a string**: `localeCompare` with
 * `numeric` is the comparison that orders `2048` after `512` without parsing a value the contract
 * forbids doing arithmetic on. The key is the tiebreaker, so cards do not reshuffle between
 * refetches.
 */
function LaneCell({
  id,
  cards,
  columns,
  onMove,
  canMove,
  moveRefusal,
  testId,
}: {
  id: string;
  cards: readonly BoardIssueCard[];
  columns: readonly ServerColumn[];
  onMove: (card: BoardIssueCard, toColumnId: string) => void;
  canMove: boolean;
  moveRefusal: string | undefined;
  testId: string;
}) {
  const { setNodeRef, isOver } = useDroppable({ id });

  const ordered = [...cards].sort(
    (a, b) => a.rank.localeCompare(b.rank, undefined, { numeric: true }) || a.key.localeCompare(b.key),
  );

  return (
    <div
      ref={setNodeRef}
      data-testid={testId}
      data-over={isOver ? 'true' : undefined}
      className={cn(
        'flex min-h-[64px] flex-col gap-2 rounded-[8px] border border-line bg-paper p-2',
        'transition-colors duration-150 motion-reduce:transition-none',
        isOver && 'border-[color:var(--color-steel)] bg-wash-1',
      )}
    >
      {ordered.length === 0 ? (
        /* The mockup's ghost tile, and it names the second path rather than only the gesture. */
        <p className="m-0 rounded-[5px] border border-dashed border-line px-2 py-3 text-center text-[11.5px] text-ink-4">
          Drop here — or use a card&rsquo;s ⋮ Move to…
        </p>
      ) : (
        ordered.map((card) => (
          <BoardCardFace
            key={card.key}
            card={card}
            columns={columns}
            onMove={(toColumnId) => onMove(card, toColumnId)}
            canMove={canMove}
            moveRefusal={moveRefusal}
            testId={`${testId}.Card.${card.key}`}
          />
        ))
      )}
    </div>
  );
}
