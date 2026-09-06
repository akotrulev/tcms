import { useDraggable } from '@dnd-kit/core';
import { GripVertical } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import type { BoardIssueCard, CardElsewhere } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Badge } from '../../components/kit';
import { cn } from '../../lib/cn';
import { labelFill } from '../../lib/statusColors';
import { AiEligibleMarker } from '../tracker/AiEligibleMarker';
import { TypeIcon } from '../tracker/TypeIcon';
import { MoveToMenu } from './MoveToMenu';
import { ReadinessSignals } from './ReadinessSignals';
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
 * ~~**Label chips carry no colour.** `Label.color` is a design-token name (`label-1` … `label-10`)
 * and **those ten tokens are not declared in `index.css`** — the palette is a stylesheet decision
 * that arrives with the screen that creates labels.~~ → **012 T9 declared them**, on the screen
 * where a label's colour is chosen, so the chips are filled from `labelFill`. The caution the old
 * sentence carried still holds and is why the fill is a token rather than a hue picked here: a chip
 * with the wrong colour is worse than one with none, because the word is what is being scanned
 * either way, and an unrecognised token falls back to the plain chip rather than to nothing.
 *
 * <h2>What 021 added, and the one thing it deliberately did not</h2>
 *
 * Four statements, each from a field the card already carries: the `⛔ from <status>` chip
 * (`blockedFromStatus`, so a blocked card sitting on somebody else's board says whose problem it
 * was), the `also on <board>` chip, §2.6's readiness signals on a grooming board, and the
 * `all stories done` nudge on an epic. ~~And a fifth.~~ **`linkedCases` is not rendered here and
 * that is T8's**, with the QA card face it belongs to: the key is always present and is **null**
 * until T4 serves it, so a face drawing it now would have to tell *not served* from *none linked*
 * for a payload that is only ever the first — and `?? 0` on either would read as "nothing failed",
 * which is the dishonest reading of the same zero the renders spend a paragraph on.
 *
 * **`also on` is not `elsewhere`.** `BoardIssueCard.elsewhere` is the *descendants* a row header's
 * board does not show, which is the sub-bar's link; *this card is also drawn on that board* is a
 * different statement with no field of its own, derived by the caller from the coverage matrix —
 * the boards other than this one whose columns claim this card's status. Reading `elsewhere` for it
 * would put a chip on a card saying something true about its children.
 *
 * **The epic card is this component, not a second one.** `12-dev-board.html` gives it a `.epiccard`
 * emphasis and nothing else structural; a second renderer for it would be the fourth grid renderer
 * `ui-rules` §8 exists to refuse, and every display rule below would then be implemented once or
 * twice depending on which card you were looking at.
 */
export function BoardCardFace({
  card,
  columns,
  onMove,
  onBlock,
  onUnblock,
  alsoOn = [],
  readiness = false,
  canMove,
  moveRefusal,
  overlay = false,
  testId,
}: {
  card: BoardIssueCard;
  columns: readonly ServerColumn[];
  onMove: (toColumnId: string) => void;
  onBlock?: () => void;
  onUnblock?: (statusId: string) => void;
  /**
   * The other boards that claim this card's **status**, derived by the caller from the coverage
   * matrix. Not `card.elsewhere`, which is a statement about the card's descendants.
   */
  alsoOn?: readonly Pick<CardElsewhere, 'boardId' | 'boardName'>[];
  /** Whether this board grooms work — `cardMove.ts::groomsWork`, not a board name. */
  readiness?: boolean;
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
        // `.epiccard` — the render's emphasis on the one card in a band that is the lane itself.
        card.typeLevel === 'EPIC' && 'border-l-2 border-l-[color:var(--color-viz-2)]',
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
            onBlock={onBlock}
            onUnblock={onUnblock}
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

      {readiness && <ReadinessSignals card={card} testId={`${testId}.Readiness`} />}

      {/*
        021 D-d's nudge, and it is a **sentence, not a control**: every counted story under this epic
        is done and the epic is not, which is a thing only the PO can decide to act on. The server
        never moves an epic on its own — "every story is finished" and "this body of work is
        finished" are different claims — so the card says the first and offers the second through
        the same `Move to…` every card has.
      */}
      {card.allStoriesDone && (
        <p
          data-testid={`${testId}.AllStoriesDone`}
          className="m-0 mb-[7px] text-[10.5px] leading-[1.4] text-[color:var(--color-passed)]"
        >
          Every story under this epic is done.
        </p>
      )}

      {/* Chips WRAP — the mockup, and the responsive rule: the page body never scrolls sideways, so
          a card with five labels grows downward rather than pushing the column wider. */}
      <div
        data-testid={`${testId}.Meta`}
        className="flex flex-wrap items-center gap-[7px] text-[11px] text-ink-3"
      >
        {/*
          `⛔ from <status>` — the origin, in words, on the face. It is what stops somebody on the
          Dev board claiming a card a tester blocked: `Blocked` is claimed by all three boards, so
          without this line the same card reads identically to everyone who can see it.
        */}
        {card.blockedFromStatus && (
          <Badge tone="blocked" data-testid={`${testId}.BlockedFrom`}>
            {`⛔ from ${card.blockedFromStatus.name}`}
          </Badge>
        )}

        {card.labels.map((label) => (
          <Badge
            key={label.id}
            tone="plain"
            style={{ backgroundColor: labelFill(label.color), color: 'var(--color-ink)' }}
            data-testid={`${testId}.Label.${label.name}`}
          >
            {label.name}
          </Badge>
        ))}

        <AiEligibleMarker
          eligible={card.agentEligible}
          data-testid={`${testId}.AiEligible`}
        />

        {!card.countsTowardCompletion && (
          <Badge tone="plain" data-testid={`${testId}.NotCounted`}>
            not in %
          </Badge>
        )}

        {alsoOn.map((board) => (
          <Badge key={board.boardId} tone="steel" data-testid={`${testId}.AlsoOn.${board.boardName}`}>
            {`also on ${board.boardName}`}
          </Badge>
        ))}

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
