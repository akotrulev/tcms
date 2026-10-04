import { useDraggable } from '@dnd-kit/core';
import { Ban, ChevronsUp, FlaskConical, GripVertical, ListChecks } from 'lucide-react';
import {
  useEffect,
  useRef,
  type KeyboardEventHandler,
  type MouseEventHandler,
  type PointerEventHandler,
} from 'react';
import { Link as RouterLink } from 'react-router-dom';
import type { BoardIssueCard, CardElsewhere } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Badge, ResultBar } from '../../components/kit';
import { cn } from '../../lib/cn';
import { priorityLabels } from '../../lib/labels';
import { labelFill } from '../../lib/statusColors';
import { AiEligibleMarker } from '../tracker/AiEligibleMarker';
import { TypeIcon } from '../tracker/TypeIcon';
import { describeLinkedCases } from './linkedCases';
import { MoveToMenu } from './MoveToMenu';
import { ReadinessSignals } from './ReadinessSignals';
import type { ServerColumn } from './cardMove';

const CLICK_AFTER_DRAG_WINDOW_MS = 300;

const PRIORITY_INK = {
  CRITICAL: 'text-[color:var(--color-failed)]',
  HIGH: 'text-[color:var(--color-warning)]',
} as const;

const CASES_INK = {
  passed: 'text-[color:var(--color-passed)]',
  failed: 'text-[color:var(--color-failed)]',
  untested: 'text-ink-3',
} as const;

/**
 * The board card: identity first, the linked-case result last.
 *
 * Row one is the grip, type, key, priority and assignee. Then the title, the readiness signals, a
 * wrapping meta line, and, when the summary is served, a cases row. A 3px edge bar along the card's
 * bottom edge draws the same summary in proportion.
 *
 * Three states of `linkedCases` stay distinct: `null` (not served) draws no row and no bar,
 * `total === 0` reads "no cases linked" with no bar, and a summary with nothing executed reads
 * "0/n run". `describeLinkedCases` owns the wording.
 *
 * Priority draws for CRITICAL and HIGH only, as a word beside the icon. Dragging: the whole card
 * takes the pointer; only the grip takes the keyboard, so the article never nests an interactive
 * role around the title link and the menu. The click that ends a pointer drag is swallowed.
 *
 * The epic card is this component too, with a left border as its only difference.
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
  /** Boards that claim this card's status, from the coverage matrix; not `card.elsewhere`. */
  alsoOn?: readonly Pick<CardElsewhere, 'boardId' | 'boardName'>[];
  /** Whether this board grooms work. */
  readiness?: boolean;
  /** The caller's `issue:edit` verdict. */
  canMove: boolean;
  moveRefusal: string | undefined;
  /** The copy inside `DragOverlay`: no draggable registration, no fading of the original. */
  overlay?: boolean;
  testId: string;
}) {
  const movable = card.allowedTransitions.length > 0 && canMove;
  const immobile = card.allowedTransitions.length === 0;
  const cases = describeLinkedCases(card.linkedCases);
  const summary = card.linkedCases;
  const edge = summary != null && summary.total > 0 ? summary : null;
  const priority =
    card.priority === 'CRITICAL' || card.priority === 'HIGH' ? card.priority : null;

  const { attributes, listeners, setNodeRef, setActivatorNodeRef, isDragging } = useDraggable({
    id: card.key,
    disabled: overlay || !movable,
  });

  const draggedRecently = useRef(false);
  useEffect(() => {
    if (isDragging) {
      draggedRecently.current = true;
      return;
    }
    if (!draggedRecently.current) return;
    const timer = setTimeout(() => {
      draggedRecently.current = false;
    }, CLICK_AFTER_DRAG_WINDOW_MS);
    return () => clearTimeout(timer);
  }, [isDragging]);

  const startPointerDrag = listeners?.onPointerDown as PointerEventHandler | undefined;
  const onPointerDown: PointerEventHandler<HTMLElement> = (event) => {
    draggedRecently.current = false;
    startPointerDrag?.(event);
  };
  const swallowClickThatEndsDrag: MouseEventHandler<HTMLElement> = (event) => {
    if (!draggedRecently.current) return;
    draggedRecently.current = false;
    event.preventDefault();
    event.stopPropagation();
  };

  const moveMenu = (
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
  );
  const revealOnHover =
    'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 focus-within:opacity-100';

  return (
    <article
      ref={overlay ? undefined : setNodeRef}
      data-testid={testId}
      onPointerDown={movable && !overlay ? onPointerDown : undefined}
      onClickCapture={swallowClickThatEndsDrag}
      className={cn(
        'group relative overflow-hidden rounded-lg border border-line bg-raised px-[11px] pt-[9px] pb-2.5 shadow-e1',
        'transition-colors duration-150 hover:border-line-strong motion-reduce:transition-none',
        movable && !overlay && 'cursor-grab',
        edge && 'pb-3',
        isDragging && 'opacity-40',
        overlay && 'shadow-e3 ring-2 ring-steel',
        card.typeLevel === 'EPIC' && 'border-l-2 border-l-[color:var(--color-viz-2)]',
      )}
    >
      <div className="flex min-h-[22px] items-center gap-1.5">
        {movable && !overlay && (
          <button
            type="button"
            ref={setActivatorNodeRef}
            {...attributes}
            onKeyDown={listeners?.onKeyDown as KeyboardEventHandler | undefined}
            aria-label={`Drag ${card.key}`}
            data-testid={`${testId}.DragHandle`}
            className={cn(
              '-ml-1.5 flex size-5 flex-none cursor-grab touch-none items-center justify-center',
              'rounded-sm border-0 bg-transparent p-0 text-ink-4 hover:text-ink-2',
              'opacity-0 group-hover:opacity-100 focus-visible:opacity-100',
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

        {priority && (
          <span
            data-testid={`${testId}.Priority`}
            className={cn('inline-flex items-center gap-0.5 text-[11px] font-semibold', PRIORITY_INK[priority])}
          >
            <ChevronsUp aria-hidden size={12} />
            {priorityLabels[priority]}
          </span>
        )}

        {card.assignee && (
          <span
            data-testid={`${testId}.Assignee`}
            title={card.assignee.displayName}
            className={cn(
              'grid size-[22px] flex-none place-items-center rounded-full bg-[color:var(--color-viz-2)]',
              'font-mono text-[9px] font-semibold text-rail',
            )}
          >
            <span aria-hidden>{initialsOf(card.assignee.displayName)}</span>
            <span className="sr-only">{card.assignee.displayName}</span>
          </span>
        )}
      </div>

      <RouterLink
        to={`/issues/${encodeURIComponent(card.key)}`}
        data-testid={`${testId}.Title`}
        className="mt-[5px] mb-2 block text-[13.5px] leading-[1.4] font-medium text-ink no-underline hover:text-steel hover:underline"
      >
        {card.title}
      </RouterLink>

      {readiness && <ReadinessSignals card={card} testId={`${testId}.Readiness`} />}

      <div
        data-testid={`${testId}.Meta`}
        className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11.5px] text-ink-3"
      >
        {card.labels.map((label) => (
          <span
            key={label.id}
            data-testid={`${testId}.Label.${label.name}`}
            className="inline-flex items-center gap-1.5"
          >
            <span
              aria-hidden
              className="size-[7px] flex-none rounded-full"
              style={{ backgroundColor: labelFill(label.color) }}
            />
            {label.name}
          </span>
        ))}

        {card.blockedFromStatus && (
          <span
            data-testid={`${testId}.BlockedFrom`}
            className="inline-flex items-center gap-1 font-semibold text-[color:var(--color-blocked)]"
          >
            <Ban aria-hidden size={12} />
            {`from ${card.blockedFromStatus.name}`}
          </span>
        )}

        {alsoOn.map((board) => (
          <Badge key={board.boardId} tone="steel" data-testid={`${testId}.AlsoOn.${board.boardName}`}>
            {`also on ${board.boardName}`}
          </Badge>
        ))}

        <AiEligibleMarker eligible={card.agentEligible} data-testid={`${testId}.AiEligible`} />

        {card.subtaskTotal > 0 && (
          <span
            data-testid={`${testId}.Subtasks`}
            className={cn(
              'inline-flex items-center gap-1',
              card.subtaskDone === card.subtaskTotal && 'text-[color:var(--color-passed)]',
            )}
          >
            <ListChecks aria-hidden size={12} />
            <Identifier variant="muted" className="text-[11px] text-inherit">
              {`${card.subtaskDone}/${card.subtaskTotal}`}
            </Identifier>
            <span className="sr-only">{' sub-tasks'}</span>
          </span>
        )}

        {card.dueDate && (
          <span
            data-testid={`${testId}.Due`}
            className={cn(card.overdue && 'font-semibold text-[color:var(--color-failed)]')}
          >
            {card.overdue ? `overdue ${card.dueDate}` : `due ${card.dueDate}`}
          </span>
        )}

        {card.estimateHours != null && (
          <Identifier variant="muted" testId={`${testId}.Estimate`} className="text-[11px] text-ink-3">
            {`${card.estimateHours}h`}
          </Identifier>
        )}

        {!card.countsTowardCompletion && (
          <Badge tone="plain" data-testid={`${testId}.NotCounted`}>
            not in %
          </Badge>
        )}
      </div>

      {card.allStoriesDone && (
        <p
          data-testid={`${testId}.AllStoriesDone`}
          className="m-0 mt-1.5 text-[11px] leading-[1.4] text-[color:var(--color-passed)]"
        >
          Every story under this epic is done.
        </p>
      )}

      {cases && (
        <div
          data-testid={`${testId}.LinkedCases`}
          className="mt-1.5 flex items-center gap-2 text-[11.5px] text-ink-3"
        >
          <FlaskConical aria-hidden size={13} className="flex-none" />
          <span
            data-testid={`${testId}.LinkedCases.Headline`}
            className={cn('font-mono text-[11px]', CASES_INK[cases.tone === 'passed' || cases.tone === 'failed' ? cases.tone : 'untested'])}
          >
            {cases.headline}
          </span>
          {cases.blocked > 0 && (
            <span
              data-testid={`${testId}.LinkedCases.Blocked`}
              className="text-[color:var(--color-blocked)]"
            >
              {`${cases.blocked} blocked`}
            </span>
          )}
          <RouterLink
            to={`/issues/${encodeURIComponent(card.key)}`}
            data-testid={`${testId}.LinkedCases.Run`}
            className="text-ink-4 no-underline hover:text-steel hover:underline"
          >
            Run linked cases
          </RouterLink>
          <span className="flex-1" />
          {!overlay && !immobile && <div className={revealOnHover}>{moveMenu}</div>}
        </div>
      )}

      {!overlay && immobile && <div className="mt-1.5">{moveMenu}</div>}
      {!overlay && !immobile && !cases && (
        <div className={cn('absolute right-1.5 bottom-1.5', revealOnHover)}>{moveMenu}</div>
      )}

      {edge && (
        <ResultBar
          variant="edge"
          data-testid={`${testId}.ResultEdge`}
          passed={edge.passing}
          failed={edge.failing}
          blocked={edge.blocked}
          untested={Math.max(0, edge.total - edge.passing - edge.failing - edge.blocked)}
        />
      )}
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
