import { useDraggable } from '@dnd-kit/core';
import { GripVertical } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import type { BoardIssueCard } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Badge } from '../../components/kit';
import { cn } from '../../lib/cn';
import { TypeIcon } from '../tracker/TypeIcon';
import { MoveToMenu } from './MoveToMenu';
import type { ServerColumn } from './cardMove';

/**
 * What is on this card, and what is deliberately not.
 *
 * Story B3's first criterion is the whole face — *"key, type, title, labels, assignee, due date,
 * estimate, and how many sub-tasks are done"* — and §12.2's instruction is to **design the card face
 * deliberately rather than letting it accrete**. So the list above is the list, and priority is
 * absent even though `BoardIssueCard` carries one: the mockup does not draw it, B3 does not ask for
 * it, and a board card is the surface where one more chip costs the scan.
 *
 * **Not here, and each has a module:** the `AI` tag (`issue.agent_eligible`, D30/M5), the `PR #482`
 * badge (M5's forge adapter), and logged hours (M6). The mockup draws all three because it draws
 * the finished product; drawing them now would need fields nothing writes (D-f). The mockup's
 * `TC-311 failed`, `4 cases pass` and `waiting on provider` badges are absent for a narrower
 * version of the same reason — this payload carries no test result and no blocked reason, so each
 * would be a sentence with nothing behind it.
 *
 * `countsTowardCompletion: false` renders "not in %" — a Spike is on the board and out of the
 * percentage, and a card that silently did not count would make the lane's meter unexplainable.
 *
 * ## Three shapes the mockup draws that this cannot
 *
 * **The whole card is not a link.** `work-board.html` wraps each card in an `<a>` and puts the `⋮`
 * button inside it, which is invalid HTML and unreachable by keyboard in that order; the title is
 * the link and the card is an `<article>`, which is the composite board's resolution of the same
 * conflict.
 *
 * **The drag is a handle, not the card.** Same reason the composite board's is: the card carries a
 * link and a menu, and making the whole card draggable turns every attempt to press one into the
 * start of a drag.
 *
 * **Label chips carry no colour.** `Label.color` is a design-token name (`label-1` … `label-10`) and
 * **those ten tokens are not declared in `index.css`** — the palette is a stylesheet decision that
 * arrives with the screen that creates labels. Inventing a hue here is the one thing the contract's
 * own comment forbids ("status and label colour is data the palette owns"), and a chip with the
 * wrong colour is worse than one with none, because the word is the thing being scanned either way.
 */
export function BoardCardFace({
  card,
  columns,
  onMove,
  canMove,
  moveRefusal,
  overlay = false,
  testId,
}: {
  card: BoardIssueCard;
  columns: readonly ServerColumn[];
  onMove: (toColumnId: string) => void;
  /** The caller's `issue:edit` verdict — a permission, not a workflow rule. */
  canMove: boolean;
  moveRefusal: string | undefined;
  /** The copy inside `DragOverlay`: no draggable registration, no fading of the original. */
  overlay?: boolean;
  testId: string;
}) {
  const movable = card.allowedTransitions.length > 0 && canMove;
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: card.key,
    // An epic follows its stories and a caller without `issue:edit` may not move anything, so
    // neither lifts at all rather than lifting and being refused on release (`ui-rules`). The
    // reason is still on screen, under the menu that would have been there.
    disabled: overlay || !movable,
  });

  return (
    <article
      ref={overlay ? undefined : setNodeRef}
      data-testid={testId}
      className={cn(
        'rounded-[5px] border border-line bg-panel px-2.5 py-2 shadow-e1',
        'transition-colors duration-150 hover:border-line-strong motion-reduce:transition-none',
        isDragging && 'opacity-40',
        overlay && 'shadow-e3',
      )}
    >
      <div className="mb-1.5 flex items-center gap-1.5">
        {movable && !overlay && (
          <button
            type="button"
            {...attributes}
            {...listeners}
            aria-label={`Drag ${card.key}`}
            data-testid={`${testId}.DragHandle`}
            className={cn(
              '-ml-1 flex size-6 flex-none cursor-grab touch-none items-center justify-center',
              'rounded-sm border-0 bg-transparent p-0 text-ink-4 hover:text-ink-2',
              'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
            )}
          >
            <GripVertical aria-hidden className="size-3.5" />
          </button>
        )}

        <TypeIcon
          type={{
            name: card.typeName,
            level: card.typeLevel,
            countsTowardCompletion: card.countsTowardCompletion,
          }}
          data-testid={`${testId}.Type`}
        />
        <Identifier variant="muted" testId={`${testId}.Key`} className="text-[11.5px] text-ink-3">
          {card.key}
        </Identifier>

        <span className="flex-1" />

        {!overlay && (
          <MoveToMenu
            card={card}
            columns={columns}
            onMove={onMove}
            disabled={!canMove}
            disabledReason={moveRefusal}
            testId={`${testId}.Move`}
          />
        )}
      </div>

      <RouterLink
        to={`/issues/${encodeURIComponent(card.key)}`}
        data-testid={`${testId}.Title`}
        className="mb-[7px] block text-[12.5px] leading-[1.35] text-ink no-underline hover:text-steel hover:underline"
      >
        {card.title}
      </RouterLink>

      {/* Chips WRAP — the mockup, and the responsive rule: the page body never scrolls sideways, so
          a card with five labels grows downward rather than pushing the column wider. */}
      <div
        data-testid={`${testId}.Meta`}
        className="flex flex-wrap items-center gap-[7px] text-[11px] text-ink-3"
      >
        {card.labels.map((label) => (
          <Badge key={label.id} tone="plain" data-testid={`${testId}.Label.${label.name}`}>
            {label.name}
          </Badge>
        ))}

        {!card.countsTowardCompletion && (
          <Badge tone="plain" data-testid={`${testId}.NotCounted`}>
            not in %
          </Badge>
        )}

        {card.subtaskTotal > 0 && (
          <Badge tone="plain" data-testid={`${testId}.Subtasks`}>
            {`${card.subtaskDone}/${card.subtaskTotal} sub-tasks`}
          </Badge>
        )}

        {card.dueDate && (
          /*
            `overdue` is the card's own, computed server-side at read time and never stored: a
            stored flag needs a job to flip it and is wrong for a day at a time. The word "due" and
            the date are the statement; the colour is a second reading of it, never the only one.
          */
          <span
            data-testid={`${testId}.Due`}
            className={cn(card.overdue && 'font-medium text-[color:var(--color-failed)]')}
          >
            {card.overdue ? `overdue ${card.dueDate}` : `due ${card.dueDate}`}
          </span>
        )}

        <span className="flex-1" />

        {card.estimateHours != null && (
          <Identifier variant="muted" testId={`${testId}.Estimate`} className="text-[11px] text-ink-3">
            {`${card.estimateHours}h`}
          </Identifier>
        )}

        {card.assignee && (
          <span
            data-testid={`${testId}.Assignee`}
            title={card.assignee.displayName}
            className={cn(
              'grid size-5 flex-none place-items-center rounded-full bg-[color:var(--color-viz-2)]',
              'font-mono text-[9px] font-semibold text-rail',
            )}
          >
            {/* The initials are decoration over the name, not a substitute for it: two colleagues
                share initials often enough that a board keyed only on them is a board that lies. */}
            <span aria-hidden>{initialsOf(card.assignee.displayName)}</span>
            <span className="sr-only">{card.assignee.displayName}</span>
          </span>
        )}
      </div>
    </article>
  );
}

/**
 * The first letter of the first two words, upper-cased.
 *
 * Local and unexported: it has one caller and `lib/format.ts` earns a display helper on the second
 * one. A name with one word answers one letter rather than padding, which is what a mononym should
 * look like.
 */
function initialsOf(displayName: string): string {
  return displayName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0].toUpperCase())
    .join('');
}
