import { MoreVertical } from 'lucide-react';
import type { BoardIssueCard } from '../../api/types';
import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../../components/ui';
import {
  blockingTargetOf,
  columnRefusalFor,
  resolveColumnMove,
  unblockTargetOf,
  type ServerColumn,
} from './cardMove';

/**
 * The `⋮ Move to…` every card carries.
 *
 * **WCAG 2.2 Dragging Movements (2.5.7)**, and story B3's own non-functional requirement: *"Every
 * board action is reachable from the keyboard, including moving a card between columns."* The
 * mockup makes the same point in prose on the board itself — *"Dragging is never the only way"* —
 * and adds the second audience the criterion does not name: **this is also what a touch user gets.**
 *
 * Every column is listed, and the illegal ones are **disabled with the reason on them** rather than
 * hidden (§31's disable-don't-hide). A hidden control reads as "you may not", and a workflow that
 * has no In Progress → Done transition is configuration, not a permission.
 *
 * Both affordances end in {@link resolveColumnMove}, which is why a refusal reads identically
 * however it was attempted — the reason that function is a module and not a handler. The menu asks
 * it the same question the drop does, so a column can never be offered here and refused there.
 *
 * ## An immobile card gets no control at all, and still says why
 *
 * A card whose `allowedTransitions` is empty is an **Epic** (D-r): D31 derives an epic's status from
 * its stories, so no column is reachable and no target is the reason. A disabled trigger over a menu
 * of every column would repeat one sentence per column, so this renders the sentence once and
 * nothing to press — there is no gesture, keyboard path or pointer path that could succeed, which is
 * what "no affordance" has to mean to be honest rather than merely quiet.
 *
 * ## `Block…` and `Unblock` are transitions, and they are read off the card
 *
 * 021 §2.7 puts both in this menu, above the columns and separated from them, because neither is a
 * *column* choice: the project's `blocking` status may be claimed by a column on this board (all
 * three seeded boards claim it) or by none, and `Unblock` names a status rather than a place. Both
 * targets come from `allowedTransitions` — {@link blockingTargetOf} and {@link unblockTargetOf} —
 * so an entry is **absent** when the move is not legal from here rather than disabled with a reason
 * invented for it. That is the one place this menu departs from disable-don't-hide, and the reason
 * is that there is nothing to say: "you cannot block a blocked card" is not a capability anybody is
 * being denied.
 *
 * The sentence comes from `columnRefusalFor` rather than being written here, because a second copy
 * of it is a second copy to drift: its epic branch answers before reading the target at all — the
 * function's own header says so, and it is why the argument below is any column rather than a
 * meaningful one.
 */
export function MoveToMenu({
  card,
  columns,
  onMove,
  onBlock,
  onUnblock,
  disabled = false,
  disabledReason,
  testId,
}: {
  card: BoardIssueCard;
  columns: readonly ServerColumn[];
  onMove: (toColumnId: string) => void;
  /** Opens the reason dialog. Absent on a caller that never blocks — the drag overlay's copy. */
  onBlock?: () => void;
  /** Moves the card back to `blockedFromStatus`, with no dialog: the reason is already written. */
  onUnblock?: (statusId: string) => void;
  /** The caller's own refusal — a missing `issue:edit`, not a workflow rule. */
  disabled?: boolean;
  disabledReason?: string;
  testId: string;
}) {
  const blockTarget = blockingTargetOf(card);
  const unblockTarget = unblockTargetOf(card);

  if (card.allowedTransitions.length === 0) {
    return (
      <p data-testid={`${testId}.Immobile`} className="m-0 text-[10.5px] leading-[1.45] text-ink-4">
        {columns.length > 0 ? columnRefusalFor(card, columns[0]) : null}
      </p>
    );
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="quiet"
          size="sm"
          disabled={disabled}
          title={disabled ? disabledReason : undefined}
          aria-label={`Move ${card.key}`}
          data-testid={testId}
          className="size-6 min-h-6 flex-none p-0"
        >
          <MoreVertical aria-hidden className="size-3.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {unblockTarget && onUnblock && (
          <DropdownMenuItem
            data-testid={`${testId}.Unblock`}
            onSelect={() => onUnblock(unblockTarget.id)}
          >
            {`Unblock → ${unblockTarget.name}`}
          </DropdownMenuItem>
        )}
        {blockTarget && onBlock && (
          <DropdownMenuItem data-testid={`${testId}.Block`} onSelect={onBlock}>
            Block&hellip;
          </DropdownMenuItem>
        )}
        {((unblockTarget && onUnblock) || (blockTarget && onBlock)) && <DropdownMenuSeparator />}

        {columns.map((column) => {
          const move = resolveColumnMove(card, column.id, columns);
          return (
            <DropdownMenuItem
              key={column.id}
              disabled={move.outcome !== 'MOVE'}
              onSelect={() => {
                if (move.outcome === 'MOVE') onMove(move.toColumnId);
              }}
              data-testid={`${testId}.To.${column.name}`}
              className="flex-col items-start gap-0"
            >
              <span>{column.name}</span>
              {/*
                The refusal on its own second line, in the menu, where the reader already is. A
                disabled control cannot carry a tooltip — `disabled:pointer-events-none` means no
                hover ever reaches one — which is the trap the composite board's card spends a
                paragraph on and a menu item simply does not have.
              */}
              {move.outcome === 'REFUSED' && (
                <span className="text-[10.5px] leading-[1.4] text-ink-4">{move.reason}</span>
              )}
              {/*
                `NONE` is the card's own column, and the coarse column already holding its status
                (`resolveColumnMove`). Neither is a refusal: the drop would mean nothing, so the
                entry says nothing rather than inventing a reason for a non-event.
              */}
              {move.outcome === 'NONE' && (
                <span className="text-[10.5px] leading-[1.4] text-ink-4">already here</span>
              )}
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
