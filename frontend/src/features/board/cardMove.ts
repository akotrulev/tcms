import type { WorkItem, WorkItemStatus } from '../../api/types';
import { workItemStatusLabels } from '../../lib/labels';

/**
 * Where a finished drag of a **board card** lands, and the sentence it is refused with (§106).
 *
 * ## Why this is a module, and why it is not `stepReorder.ts`
 *
 * Same argument as §94's: the decision is the only part of drag-and-drop that can be *wrong*
 * rather than merely unwired, and it is the only part a component test cannot reach — driving a
 * real pointer drag through jsdom exercises `@dnd-kit`, not this, and `onDragEnd` is a prop of a
 * context provider with no other way in. So the decision lives out here where it can be asked
 * directly, and the component keeps the wiring.
 *
 * It is a **second** module rather than a generalisation of `resolveStepMove` because the two
 * answer different questions. A step drag is a *reorder within one list* and its answer is a pair
 * of indexes. A card drag is a *move between containers*, its answer is a target column, and — the
 * part that has no analogue at all in the step list — **it can be legally refused**. A shared
 * helper would have to carry the refusal for a caller that has none.
 *
 * ## The four outcomes, and why three of them are not moves
 *
 *  - **Dropped outside every column** (`over` is null): nothing. The most common way a drag ends.
 *  - **Dropped back in the column it came from**: nothing, and this one is the §94 defect in its
 *    board spelling. `move(i, i)` on the step list merely marked a form dirty; here the equivalent
 *    is a **`PATCH` that writes an unchanged status**, taking a version, an audit row and a board
 *    refresh for a gesture that changed nothing.
 *  - **Dropped on an id that is not a column**: nothing. A stale droppable id from a drag that
 *    outlived a re-render, which is the third arm `resolveStepMove` also has.
 *  - **Dropped on a column the card may not enter**: **refused, with the reason**, and refused
 *    *here* rather than sent and reverted. `ui-rules`: never render an action the backend will
 *    always reject — `PATCH /work-items/{id}` answers 409 for exactly this, and a card that
 *    visibly lands and then snaps back is the worse version of the same information.
 *
 * ## The sentences live here because two affordances must agree
 *
 * The card carries a row of "→ column" buttons as well (the non-drag route, and the better
 * keyboard one). Both paths end in this function, so a refusal reads identically however it was
 * attempted. They were written inline in `Board.handleDrop` until §106, where a second caller
 * could only have re-worded them.
 */

export type CardMove =
  | { outcome: 'NONE' }
  | { outcome: 'REFUSED'; reason: string }
  | { outcome: 'MOVE'; to: WorkItemStatus };

const NONE: CardMove = { outcome: 'NONE' };

/**
 * What a derived card mirrors, in the sentence that refuses a drop.
 *
 * `Record<Union, …>`'s shape: a new `kind` is a compile error here rather than a card whose
 * refusal names the wrong source — which is precisely what §81 found, every derived card that was
 * not an execution being called a review.
 */
const derivedNouns: Record<WorkItem['kind'], { indefinite: string; bare: string }> = {
  TASK: { indefinite: 'a task', bare: 'task' },
  EXECUTION: { indefinite: 'an execution', bare: 'execution' },
  REVIEW: { indefinite: 'a review', bare: 'review' },
  DOC_UPDATE: { indefinite: 'a documentation page', bare: 'page' },
  CASE_MAINTENANCE: { indefinite: 'a test case', bare: 'case' },
  DEFECT: { indefinite: 'a defect', bare: 'defect' },
};

/**
 * @param item the card being moved, or null when the drag has no active card
 * @param over the droppable the drag ended over — a column code, or null/undefined
 * @param columns the board's columns, so an `over` that is not one of them is a stale id rather
 *        than a status this client has never heard of
 */
export function resolveCardMove(
  item: WorkItem | null | undefined,
  over: string | number | null | undefined,
  columns: readonly WorkItemStatus[],
): CardMove {
  if (!item) return NONE;
  if (over === null || over === undefined) return NONE;

  const target = columns.find((column) => column === String(over));
  if (target === undefined) return NONE;
  if (target === item.status) return NONE;

  if (!item.allowedTransitions.includes(target)) {
    return { outcome: 'REFUSED', reason: refusalFor(item, target) };
  }

  return { outcome: 'MOVE', to: target };
}

/**
 * Exported for the button path, which knows the move is refused before it asks — a disabled
 * control needs the same sentence on its tooltip that a drop would have produced.
 */
export function refusalFor(item: WorkItem, target: WorkItemStatus): string {
  if (item.derived) {
    const noun = derivedNouns[item.kind];
    return `${item.linkedKey ?? 'That card'} mirrors ${noun.indefinite}, so it can't be moved to ${
      workItemStatusLabels[target]
    }. Update the ${noun.bare} and the card follows.`;
  }
  return `That card can't move to ${workItemStatusLabels[target]} from ${workItemStatusLabels[item.status]}.`;
}
