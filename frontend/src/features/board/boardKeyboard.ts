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
 * about.
 *
 * ## Up/down jumps a whole swimlane, when there is more than one (012 T6)
 *
 * ~~Up/down are left to the stock 25px nudge for fine positioning within a column.~~ → that is now
 * only the **one-lane** case, and it is still right there: within a single column a 25px nudge is
 * fine positioning, which is what it is good at.
 *
 * On a board banded by epic the same arithmetic is worse than it is horizontally. Lanes are tall and
 * their heights vary with how much work each epic holds, so the number of presses to reach the next
 * one is both large and unpredictable — a reader cannot learn it. With lanes present, up/down
 * therefore steps a whole band, by the same nearest-centre rule the columns use.
 *
 * **This is the second keyboard path, not the only one.** The card's `⋮ Move to…` menu remains the
 * simpler one and carries the disabled reason; §94's rule verbatim — the arrows stayed beside the
 * drag handle there for the same argument. But a focusable handle that did nothing on Space, or one
 * that moved by pixels, would be worse than no handle, which is what this exists to avoid.
 */

export interface Point {
  x: number;
  y: number;
}

/**
 * A droppable band's rectangle, as `@dnd-kit` reports it — a column's, or a swimlane's.
 *
 * ~~`ColumnRect`.~~ → renamed in 012 T6 when the lane axis arrived: the shape was never
 * column-specific, and two aliases for one rectangle would have been the fork this module avoids.
 */
export interface BandRect {
  left: number;
  width: number;
  top: number;
  height: number;
}

/**
 * The translate that puts the dragged card in the middle of the next band along one axis.
 *
 * Shared by both directions rather than written twice, which is the whole reason the lane step could
 * be added without forking: this is the part that can be *wrong* — the nearest-centre resolution and
 * the edge rule — and a second copy on the y axis would be a second chance to get either of them
 * wrong.
 *
 * @param centers each band's centre along the axis, **in board order**
 * @param from where the dragged card's centre is right now, on the same axis
 * @param translate the drag's current translate, which the return value extends rather than replaces
 * @param axis which member of the translate this step moves
 * @param direction -1 for the band before, +1 for the band after
 */
function stepAlongAxis(
  centers: readonly number[],
  from: number,
  translate: Point,
  axis: 'x' | 'y',
  direction: -1 | 1,
): Point | null {
  if (centers.length === 0) return null;

  /*
    Which band the card is over *now*, by nearest centre rather than by containment: mid-drag the
    card is routinely over a gutter, over two bands at once, or (with a wide card in a narrow one)
    over none of them by containment at all. Nearest-centre always answers, and answers the same
    thing containment would wherever containment answers.
  */
  let current = 0;
  for (let index = 1; index < centers.length; index += 1) {
    if (Math.abs(centers[index] - from) < Math.abs(centers[current] - from)) {
      current = index;
    }
  }

  const next = current + direction;
  if (next < 0 || next >= centers.length) return null;

  // A delta on the existing translate, never an absolute position: the translate is measured from
  // where the card started, and overwriting it would teleport the card by its own offset.
  const step = centers[next] - from;
  return axis === 'x'
    ? { x: translate.x + step, y: translate.y }
    : { x: translate.x, y: translate.y + step };
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
  columns: readonly BandRect[],
  cardCenter: Point,
  translate: Point,
  direction: -1 | 1,
): Point | null {
  return stepAlongAxis(
    columns.map((rect) => rect.left + rect.width / 2),
    cardCenter.x,
    translate,
    'x',
    direction,
  );
}

/**
 * The translate that puts the dragged card in the middle of the next swimlane along.
 *
 * {@link stepToAdjacentColumn}'s other axis, with the same edge rule and for the same reason: a
 * wrap from the last lane to the first would move a card past every epic on the board in one press.
 *
 * @param lanes the lanes' rects **in board order**, top to bottom — which puts the catch-all
 *        "No epic" lane last, where the contract sends it
 */
export function stepToAdjacentLane(
  lanes: readonly BandRect[],
  cardCenter: Point,
  translate: Point,
  direction: -1 | 1,
): Point | null {
  return stepAlongAxis(
    lanes.map((rect) => rect.top + rect.height / 2),
    cardCenter.y,
    translate,
    'y',
    direction,
  );
}

/**
 * The `KeyboardSensor` coordinate getter built on the two helpers above.
 *
 * `columnOrder` and `laneOrder` are passed in rather than read from `droppableContainers`, whose
 * iteration order is registration order — which is DOM order today and is not a thing to depend on.
 * The board's columns and its lanes each have a defined order and it is the one the arrows follow.
 *
 * **`laneOrder` defaults to empty, and an empty lane list is the one-lane case**, so every existing
 * single-argument caller keeps the behaviour it has: up/down falls through to `@dnd-kit`'s own 25px
 * nudge. A caller with lanes registers a droppable per band and names their ids here, top to bottom.
 *
 * A lane whose rect has not been measured is skipped rather than defaulted to zero — the same rule
 * the columns follow, and for the same reason: a zeroed rect sits at the top of the board and would
 * swallow every upward press.
 */
export function boardKeyboardCoordinates(
  columnOrder: readonly string[],
  laneOrder: readonly string[] = [],
): KeyboardCoordinateGetter {
  return (event, { currentCoordinates, context }) => {
    const horizontal =
      event.code === KeyboardCode.Right ? 1 : event.code === KeyboardCode.Left ? -1 : 0;
    const vertical = event.code === KeyboardCode.Down ? 1 : event.code === KeyboardCode.Up ? -1 : 0;

    // Up/down on a board with one lane, and every other key, fall through to `@dnd-kit`'s own
    // handling by returning nothing.
    if (horizontal === 0 && (vertical === 0 || laneOrder.length === 0)) return undefined;

    event.preventDefault();

    const { collisionRect, droppableRects } = context;
    if (!collisionRect) return undefined;

    const cardCenter = {
      x: collisionRect.left + collisionRect.width / 2,
      y: collisionRect.top + collisionRect.height / 2,
    };
    const bands = measured(horizontal === 0 ? laneOrder : columnOrder, droppableRects);
    const direction = (horizontal !== 0 ? horizontal : vertical) === 1 ? 1 : -1;

    return (
      (horizontal === 0
        ? stepToAdjacentLane(bands, cardCenter, currentCoordinates, direction)
        : stepToAdjacentColumn(bands, cardCenter, currentCoordinates, direction)) ?? undefined
    );
  };
}

/**
 * The named bands that have a measured rect, in the order given.
 *
 * Typed structurally rather than against `@dnd-kit`'s `RectMap`, which is keyed on
 * `string | number`: what this needs is a lookup that answers a rectangle, and naming the library's
 * alias here would make the one pure part of this module depend on it.
 */
function measured(
  order: readonly string[],
  rects: { get(id: string): BandRect | undefined },
): BandRect[] {
  const bands: BandRect[] = [];
  for (const id of order) {
    const rect = rects.get(id);
    if (rect) bands.push({ left: rect.left, width: rect.width, top: rect.top, height: rect.height });
  }
  return bands;
}
