import { useDroppable } from '@dnd-kit/core';
import { ChevronDown, ChevronRight, Maximize2 } from 'lucide-react';
import { type CSSProperties } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import type { BoardIssueCard, BoardLane, CardElsewhere, SwimlaneBy } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Badge } from '../../components/kit';
import { Popover, PopoverContent, PopoverTrigger } from '../../components/ui/popover';
import { cn } from '../../lib/cn';
import { completionBases, issueStatusKindTones } from '../../lib/labels';
import { plural } from '../../lib/format';
import { TypeIcon } from '../tracker/TypeIcon';
import { BoardCardFace } from './BoardCardFace';
import { MoveToMenu } from './MoveToMenu';
import type { ServerColumn } from './cardMove';
import type { LaneRow, LaneShape } from './laneRows';
import { sorted } from './laneRows';

/**
 * One lane — story B3's second and third criteria, re-shaped by 021 §2.5.
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
 * a normal state look like a backlog of mistakes.
 *
 * <h2>Cards stack; rows are earned (021 §2.5)</h2>
 *
 * A lane opens with **one band** in which the epic's own card and every card-story sit in their
 * status columns like a plain kanban, and a story earns an indented **sub-bar row** of its own only
 * when its tasks are the cards here. The division is {@link laneRowsOf}'s and arrives as a prop:
 * `ProjectBoardView` needs the same shape to build the keyboard's lane order, and deriving it twice
 * is how the two come to disagree about which bands exist.
 *
 * <h2>One grid per lane, with every cell placed explicitly</h2>
 *
 * The band, every sub-bar and every row of task cards share **one** grid on the board's own track
 * list, which is what keeps the columns from drifting between rows — `12-dev-board.html`'s `.egrid`,
 * and render note 16: a per-row set of flex columns cannot guarantee it. Placement is explicit
 * (`gridColumn` / `gridRow`) rather than left to auto-flow, because the `Done` strip spans the whole
 * lane and auto-flow would then shift every cell after it by one.
 *
 * `gridTemplateRows` is declared for the same reason and is the easy half to miss: `grid-row: 1 / -1`
 * counts lines of the **explicit** grid, so on a grid whose rows are all implicit it spans exactly
 * one row and the strip silently stops spanning.
 *
 * <h2>A collapsed band registers no drop target</h2>
 *
 * The existing rule, now with a second thing it applies to. A collapsed lane renders no cell, and so
 * does a collapsed sub-bar — an unmeasured band is skipped by `boardKeyboardCoordinates` and cannot
 * be dropped on, so the arrows walk between the bands a card could actually land in and a drop
 * cannot move work the reader cannot see.
 */
export function Swimlane({
  lane,
  laneId,
  swimlaneBy,
  shape,
  columns,
  cellId,
  collapsed,
  onToggle,
  collapsedRows,
  onToggleRow,
  doneOpen,
  onToggleDone,
  projectId,
  onMove,
  onBlock,
  onUnblock,
  alsoOn,
  readiness,
  canMove,
  moveRefusal,
  testId,
}: {
  lane: BoardLane;
  /** This lane's stable identity — `lane.key`, or the catch-all's stand-in. */
  laneId: string;
  swimlaneBy: SwimlaneBy;
  /** The band and the earned rows, decided once in `ProjectBoardView` (see the header). */
  shape: LaneShape;
  columns: readonly ServerColumn[];
  cellId: (laneId: string, columnId: string) => string;
  collapsed: boolean;
  onToggle: () => void;
  /** Story keys whose sub-bar is collapsed, within this lane. */
  collapsedRows: ReadonlySet<string>;
  onToggleRow: (storyKey: string) => void;
  /** Whether this lane's `Done` strip is expanded — per lane, like collapse (D-l). */
  doneOpen: boolean;
  onToggleDone: () => void;
  projectId: string;
  onMove: (card: BoardIssueCard, toColumnId: string) => void;
  onBlock: (card: BoardIssueCard) => void;
  onUnblock: (card: BoardIssueCard, statusId: string) => void;
  /** The other boards claiming a card's status — see `BoardCardFace`'s `alsoOn`. */
  alsoOn: (card: BoardIssueCard) => readonly Pick<CardElsewhere, 'boardId' | 'boardName'>[];
  readiness: boolean;
  canMove: boolean;
  moveRefusal: string | undefined;
  testId: string;
}) {
  const cards = lane.cards.length;
  const banded = swimlaneBy !== 'NONE';
  const openRows = shape.rows.filter((row) => !collapsedRows.has(row.key));

  const cellProps = {
    columns,
    onMove,
    onBlock,
    onUnblock,
    alsoOn,
    readiness,
    canMove,
    moveRefusal,
  };

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
            'mb-2 flex flex-wrap items-center gap-[9px] rounded-md border border-line',
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
              {/*
                021 R10: the epic has a status of its own now, and when this board claims it the epic
                is a **card in the band** — so the bar carries identity and roll-up and nothing else.
                When it does not, the epic is on some other board and this says so.

                It cannot say *which* board, and that is a contract gap rather than a choice:
                `18-pm-board.html` reads `Refining · epic on Dev board →`, and `BoardLane` carries a
                key, a label, its cards and a completion — no status, and no card for an epic whose
                status this board does not claim, because `BoardCardService` skips it before the lane
                is built. So the honest sentence is the one that names the epic and not the board.
              */}
              {!shape.band.some((card) => card.key === lane.key) && (
                <RouterLink
                  to={`/issues/${encodeURIComponent(lane.key)}`}
                  data-testid={`${testId}.EpicElsewhere`}
                  className="text-[11px] text-ink-4 no-underline hover:text-steel hover:underline"
                >
                  epic not on this board &rarr;
                </RouterLink>
              )}
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
        <div
          data-testid={`${testId}.Grid`}
          className="grid items-start gap-3 [grid-template-columns:repeat(var(--board-columns),272px)]"
          style={{ gridTemplateRows: `repeat(${1 + 2 * openRows.length}, auto)` }}
        >
          {/* The band: row 1, one cell per column, and the `Done` strip spanning every row. */}
          {columns.map((column, index) =>
            column.done && !doneOpen ? (
              <DoneStrip
                key={column.id}
                id={cellId(laneId, column.id)}
                count={lane.cards.filter((card) => card.columnId === column.id).length}
                name={column.name}
                laneLabel={lane.label}
                index={index}
                onExpand={onToggleDone}
                testId={`${testId}.Done.${column.name}`}
              />
            ) : (
              <LaneCell
                key={column.id}
                id={cellId(laneId, column.id)}
                cards={
                  column.done
                    ? lane.cards.filter((card) => card.columnId === column.id)
                    : shape.band.filter((card) => card.columnId === column.id)
                }
                place={
                  column.done
                    ? { gridColumn: index + 1, gridRow: '1 / -1', alignSelf: 'stretch' }
                    : { gridColumn: index + 1, gridRow: 1 }
                }
                onCollapseDone={column.done ? onToggleDone : undefined}
                testId={`${testId}.Cell.${column.name}`}
                {...cellProps}
              />
            ),
          )}

          {shape.rows.map((row) => {
            const rowOpen = !collapsedRows.has(row.key);
            const at = openRows.indexOf(row);
            return (
              <StoryRow
                key={row.key}
                row={row}
                open={rowOpen}
                /* A collapsed row's sub-bar still needs a line of its own; only its cells go. */
                gridRow={rowOpen ? 2 + 2 * at : undefined}
                laneId={laneId}
                cellId={cellId}
                onToggle={() => onToggleRow(row.key)}
                projectId={projectId}
                testId={`${testId}.Row.${row.key}`}
                {...cellProps}
              />
            );
          })}
        </div>
      )}
    </section>
  );
}

/**
 * A story's earned row — an indented sub-bar spanning the lane, and its tasks beneath.
 *
 * The sub-bar is *"the same idiom as the epic bar, one indent in"* (render note 2): key, title,
 * `done/total`, the story's status, the elsewhere link and the `⋯`. What it does **not** carry is
 * anything a card carries — the story is not drawn as a card in the band while it heads a row, so
 * the one thing it must not lose is its `Move to…`, which is here.
 *
 * **`done/total` is the story's own `subtaskDone/subtaskTotal`, not a count of what is on screen.**
 * A board claims a subset of the statuses, so some of a story's tasks are legitimately on another
 * board; counting the cells would report `0/2` for a story with four tasks and would change when
 * somebody applied a filter. A row whose story card is absent has no such figure and says how many
 * tasks are *here* instead, which is the only number it honestly has.
 */
function StoryRow({
  row,
  open,
  gridRow,
  laneId,
  columns,
  cellId,
  onToggle,
  projectId,
  onMove,
  onBlock,
  onUnblock,
  alsoOn,
  readiness,
  canMove,
  moveRefusal,
  testId,
}: {
  row: LaneRow;
  open: boolean;
  gridRow: number | undefined;
  laneId: string;
  columns: readonly ServerColumn[];
  cellId: (laneId: string, columnId: string) => string;
  onToggle: () => void;
  projectId: string;
  onMove: (card: BoardIssueCard, toColumnId: string) => void;
  onBlock: (card: BoardIssueCard) => void;
  onUnblock: (card: BoardIssueCard, statusId: string) => void;
  alsoOn: (card: BoardIssueCard) => readonly Pick<CardElsewhere, 'boardId' | 'boardName'>[];
  readiness: boolean;
  canMove: boolean;
  moveRefusal: string | undefined;
  testId: string;
}) {
  const { story } = row;
  const rowLaneId = `${laneId}#${row.key}`;

  return (
    <>
      <div
        data-testid={testId}
        style={{ gridColumn: '1 / -1', ...(gridRow === undefined ? {} : { gridRow }) }}
        className={cn(
          'ml-[18px] flex flex-wrap items-center gap-[7px] rounded-md border border-line',
          'bg-wash-1 px-2.5 py-1.5',
        )}
      >
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={open}
          aria-label={open ? `Collapse ${row.key}` : `Expand ${row.key}`}
          data-testid={`${testId}.Toggle`}
          className={cn(
            'flex size-5 flex-none items-center justify-center rounded-sm border-0 bg-transparent',
            'cursor-pointer p-0 text-ink-3 hover:text-ink',
            'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
          )}
        >
          {open ? (
            <ChevronDown aria-hidden className="size-3.5" />
          ) : (
            <ChevronRight aria-hidden className="size-3.5" />
          )}
        </button>

        {story && (
          <TypeIcon
            type={{
              name: story.typeName,
              level: story.typeLevel,
              countsTowardCompletion: story.countsTowardCompletion,
            }}
            data-testid={`${testId}.Type`}
          />
        )}
        <Identifier variant="muted" className="text-[11px] text-ink-3">
          {row.key}
        </Identifier>
        <RouterLink
          to={`/issues/${encodeURIComponent(row.key)}`}
          data-testid={`${testId}.Title`}
          className="text-[12px] font-medium text-ink no-underline hover:text-steel hover:underline"
        >
          {story?.title ?? row.key}
        </RouterLink>

        <span data-testid={`${testId}.Tasks`} className="text-[11px] text-ink-4">
          {story
            ? `${story.subtaskDone}/${story.subtaskTotal} tasks`
            : `${plural(row.tasks.length, 'task')} here`}
        </span>

        {story && (
          <Badge tone={issueStatusKindTones[story.status.kind]} data-testid={`${testId}.Status`}>
            {story.status.name}
          </Badge>
        )}

        {story && <Elsewhere elsewhere={story.elsewhere} projectId={projectId} testId={testId} />}

        <span className="flex-1" />

        {story && (
          <MoveToMenu
            card={story}
            columns={columns}
            onMove={(toColumnId) => onMove(story, toColumnId)}
            onBlock={() => onBlock(story)}
            onUnblock={(statusId) => onUnblock(story, statusId)}
            disabled={!canMove}
            disabledReason={moveRefusal}
            testId={`${testId}.Move`}
          />
        )}
      </div>

      {open
        && columns.map((column, index) =>
          /*
            The `Done` column has one cell for the whole lane, in the band, spanning every row — so
            a story row draws nothing under it. Render note 28: a per-row zero under a collapsed
            column is noise.
          */
          column.done ? null : (
            <LaneCell
              key={column.id}
              id={cellId(rowLaneId, column.id)}
              cards={row.tasks.filter((card) => card.columnId === column.id)}
              place={{
                gridColumn: index + 1,
                ...(gridRow === undefined ? {} : { gridRow: gridRow + 1 }),
              }}
              columns={columns}
              onMove={onMove}
              onBlock={onBlock}
              onUnblock={onUnblock}
              alsoOn={alsoOn}
              readiness={readiness}
              canMove={canMove}
              moveRefusal={moveRefusal}
              testId={`${testId}.Cell.${column.name}`}
            />
          ),
        )}
    </>
  );
}

/**
 * The `n on other boards` link — §2.5's, and the popover it opens when the set spans more than one.
 *
 * `elsewhere` is a count of the **not-shown-here** set and never a subtraction: a status may sit on
 * two boards, so *shown here* and *elsewhere* legitimately overlap and `total − shown` would
 * double-count. Nothing is rendered when the set is empty, which is the ordinary case.
 */
function Elsewhere({
  elsewhere,
  projectId,
  testId,
}: {
  elsewhere: readonly CardElsewhere[];
  projectId: string;
  testId: string;
}) {
  if (elsewhere.length === 0) return null;
  const total = elsewhere.reduce((sum, entry) => sum + entry.count, 0);
  const link = (boardId: string) => `/projects/${projectId}/work?view=board&board=${boardId}`;

  if (elsewhere.length === 1) {
    return (
      <RouterLink
        to={link(elsewhere[0].boardId)}
        data-testid={`${testId}.Elsewhere`}
        className="text-[11px] text-steel no-underline hover:underline"
      >
        {`${total} on ${elsewhere[0].boardName} →`}
      </RouterLink>
    );
  }

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-testid={`${testId}.Elsewhere`}
          className={cn(
            'cursor-pointer rounded-sm border-0 bg-transparent p-0 text-[11px] text-steel underline',
            'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
          )}
        >
          {`${total} on other boards`}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto min-w-[180px] p-2">
        <ul
          className="m-0 flex list-none flex-col gap-1 p-0 text-[12px]"
          data-testid={`${testId}.Elsewhere.List`}
        >
          {elsewhere.map((entry) => (
            <li key={entry.boardId}>
              <RouterLink
                to={link(entry.boardId)}
                data-testid={`${testId}.Elsewhere.${entry.boardName}`}
                className="text-ink no-underline hover:text-steel hover:underline"
              >
                {`${entry.count} on ${entry.boardName}`}
              </RouterLink>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  );
}

/**
 * A `Done` column, collapsed to one narrow strip spanning the lane (021 D-l, render note 28).
 *
 * *Tested is not shipped* (021 **R12** — ~~`Done` is a Dev/QA column~~ → it is the PM board's,
 * collapsed to a count) — `Done` is the PM board's and nothing else's, and the PO's drag into it
 * is the deploy confirmation. `Ready to deploy` is the tester's last word; the deploy confirmation
 * is the PO's, and it is the figure release readiness reads. So it is **still a drop target while collapsed**: the count is what is
 * hidden, not the gesture, and a column you cannot drop into would remove the one move this whole
 * arrangement exists to make deliberate.
 *
 * One strip for the lane rather than a cell per row, `align-self: stretch` because the lane grid is
 * `align-items: start`.
 */
function DoneStrip({
  id,
  count,
  name,
  laneLabel,
  index,
  onExpand,
  testId,
}: {
  id: string;
  count: number;
  name: string;
  laneLabel: string;
  index: number;
  onExpand: () => void;
  testId: string;
}) {
  const { setNodeRef, isOver } = useDroppable({ id });

  return (
    <div
      ref={setNodeRef}
      data-testid={testId}
      data-over={isOver ? 'true' : undefined}
      style={{ gridColumn: index + 1, gridRow: '1 / -1', alignSelf: 'stretch' }}
      className={cn(
        'flex min-h-[64px] flex-col items-center gap-2 rounded-lg border border-line bg-wash-1 p-2',
        'transition-colors duration-150 motion-reduce:transition-none',
        isOver && 'border-[color:var(--color-steel)] bg-wash-2',
      )}
    >
      <span data-testid={`${testId}.Count`} className="font-mono text-[13px] font-semibold text-ink-2">
        {count}
      </span>
      <button
        type="button"
        onClick={onExpand}
        aria-label={`Expand the ${name} column of the ${laneLabel} lane`}
        data-testid={`${testId}.Expand`}
        className={cn(
          'flex size-5 flex-none items-center justify-center rounded-sm border-0 bg-transparent',
          'cursor-pointer p-0 text-ink-4 hover:text-ink-2',
          'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
        )}
      >
        <Maximize2 aria-hidden className="size-3.5" />
      </button>
    </div>
  );
}

/**
 * One (lane, column) cell — the thing a card is dropped **on**.
 *
 * A component rather than a `map` body because `useDroppable` is a hook, which is the same reason
 * the composite board's `BoardColumn` exists.
 *
 * **Empty at rest, outlined only while a drag is in progress** (render note 29). The ghost tile that
 * used to name the second path is gone: §2.5 puts a card in one of several bands now, so a lane with
 * three rows drew as many instructions as cards, and the sentence lives once on the board instead.
 * `isOver` from `@dnd-kit` is the render's `.is-dragging` class.
 */
function LaneCell({
  id,
  cards,
  place,
  columns,
  onMove,
  onBlock,
  onUnblock,
  alsoOn,
  readiness,
  onCollapseDone,
  canMove,
  moveRefusal,
  testId,
}: {
  id: string;
  cards: readonly BoardIssueCard[];
  place: CSSProperties;
  columns: readonly ServerColumn[];
  onMove: (card: BoardIssueCard, toColumnId: string) => void;
  onBlock: (card: BoardIssueCard) => void;
  onUnblock: (card: BoardIssueCard, statusId: string) => void;
  alsoOn: (card: BoardIssueCard) => readonly Pick<CardElsewhere, 'boardId' | 'boardName'>[];
  readiness: boolean;
  /** Present on an **expanded** `Done` column, so the reader can put it back. */
  onCollapseDone?: () => void;
  canMove: boolean;
  moveRefusal: string | undefined;
  testId: string;
}) {
  const { setNodeRef, isOver } = useDroppable({ id });

  return (
    <div
      ref={setNodeRef}
      data-testid={testId}
      data-over={isOver ? 'true' : undefined}
      style={place}
      className={cn(
        'flex min-h-[64px] flex-col gap-2 rounded-lg border p-2 transition-colors duration-150',
        'motion-reduce:transition-none',
        isOver ? 'border-[color:var(--color-steel)] bg-wash-1' : 'border-line bg-paper',
      )}
    >
      {onCollapseDone && (
        <button
          type="button"
          onClick={onCollapseDone}
          data-testid={`${testId}.Collapse`}
          className={cn(
            'cursor-pointer self-end rounded-sm border-0 bg-transparent p-0 text-[10.5px] text-ink-4',
            'hover:text-ink-2 focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
          )}
        >
          collapse
        </button>
      )}
      {sorted(cards).map((card) => (
        <BoardCardFace
          key={card.key}
          card={card}
          columns={columns}
          onMove={(toColumnId) => onMove(card, toColumnId)}
          onBlock={() => onBlock(card)}
          onUnblock={(statusId) => onUnblock(card, statusId)}
          alsoOn={alsoOn(card)}
          readiness={readiness}
          canMove={canMove}
          moveRefusal={moveRefusal}
          testId={`${testId}.Card.${card.key}`}
        />
      ))}
    </div>
  );
}
