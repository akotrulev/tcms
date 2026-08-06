import { KeyboardCode, type KeyboardCoordinateGetter } from '@dnd-kit/core';

/**
 * The keyboard drag for a **board**, which `@dnd-kit`'s two stock coordinate getters do not cover
 * (§106).
 *
 * `sortableKeyboardCoordinates` — the one §94 used for the step list — walks a *sortable* list and
 * needs `SortableContext`; this board has none, because moving a card between columns is a move
 * between containers rather than a reorder within one. The default getter
 * (`defaultKeyboardCoordinateGetter`) nudges the drag **25 pixels per keypress**, which across a
 * four-column board is roughly forty presses to cross the screen and lands wherever the arithmetic
 * stops. Either is technically "keyboard accessible" and neither is usable.
 *
 * So the left/right arrows **jump a whole column**, which is the unit the gesture is actually
 * about, and up/down are left to the stock 25px nudge for fine positioning within a column.
 *
 * **This is the second keyboard path, not the only one.** The card's "→ column" buttons remain the
 * simpler one and carry the disabled reason; §94's rule verbatim — the arrows stayed beside the
 * drag handle there for the same argument. But a focusable handle that did nothing on Space, or
 * one that moved by pixels, would be worse than no handle, which is what this exists to avoid.
 */

export interface Point {
  x: number;
  y: number;
}

/** A column's droppable rectangle, as `@dnd-kit` reports it. */
export interface ColumnRect {
  left: number;
  width: number;
  top: number;
  height: number;
}

/**
 * The translate that puts the dragged card in the middle of the next column along.
 *
 * Pure, and separated from the getter for the reason `resolveStepMove` records: this is the part
 * that can be *wrong*, and a jsdom pointer drag would exercise `@dnd-kit` instead of it.
 *
 * @param columns the columns' rects **in board order**, left to right
 * @param cardCenter where the dragged card's centre is right now, in the same coordinate space
 * @param translate the drag's current translate, which the return value extends rather than replaces
 * @param direction -1 for the column to the left, +1 for the one to the right
 * @returns the new translate, or **null at either end of the board** — a drag that silently
 *          wrapped from the last column to the first would move a card four columns on one
 *          keypress, and one that kept nudging past the edge would look like a stuck key.
 */
export function stepToAdjacentColumn(
  columns: readonly ColumnRect[],
  cardCenter: Point,
  translate: Point,
  direction: -1 | 1,
): Point | null {
  if (columns.length === 0) return null;

  /*
    Which column the card is over *now*, by nearest centre rather than by containment: mid-drag the
    card is routinely over a gutter, over two columns at once, or (with a wide card in a narrow
    column) over none of them by containment at all. Nearest-centre always answers, and answers the
    same thing containment would wherever containment answers.
  */
  const centers = columns.map((rect) => rect.left + rect.width / 2);
  let current = 0;
  for (let index = 1; index < centers.length; index += 1) {
    if (Math.abs(centers[index] - cardCenter.x) < Math.abs(centers[current] - cardCenter.x)) {
      current = index;
    }
  }

  const next = current + direction;
  if (next < 0 || next >= columns.length) return null;

  // A delta on the existing translate, never an absolute position: the translate is measured from
  // where the card started, and overwriting it would teleport the card by its own offset.
  return { x: translate.x + (centers[next] - cardCenter.x), y: translate.y };
}

/**
 * The `KeyboardSensor` coordinate getter built on the helper above.
 *
 * `columnOrder` is passed in rather than read from `droppableContainers`, whose iteration order is
 * registration order — which is DOM order today and is not a thing to depend on. The board's
 * columns have a defined left-to-right order and it is the one the arrows must follow.
 */
export function boardKeyboardCoordinates(columnOrder: readonly string[]): KeyboardCoordinateGetter {
  return (event, { currentCoordinates, context }) => {
    const horizontal =
      event.code === KeyboardCode.Right ? 1 : event.code === KeyboardCode.Left ? -1 : 0;
    // Up/down fall through to `@dnd-kit`'s own handling by returning nothing.
    if (horizontal === 0) return undefined;

    event.preventDefault();

    const { collisionRect, droppableRects } = context;
    if (!collisionRect) return undefined;

    const columns: ColumnRect[] = [];
    for (const id of columnOrder) {
      const rect = droppableRects.get(id);
      // A column with no measured rect is skipped rather than defaulted to zero, which would sit
      // at the far left and swallow every leftward press.
      if (rect) columns.push({ left: rect.left, width: rect.width, top: rect.top, height: rect.height });
    }

    return (
      stepToAdjacentColumn(
        columns,
        { x: collisionRect.left + collisionRect.width / 2, y: collisionRect.top + collisionRect.height / 2 },
        currentCoordinates,
        horizontal === 1 ? 1 : -1,
      ) ?? undefined
    );
  };
}
