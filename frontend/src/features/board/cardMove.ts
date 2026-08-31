import type { BoardCard, IssueStatusKind, IssueStatusRef } from '../../api/types';
import { issueStatusKinds } from '../../lib/labels';

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
 * ## The columns are the PROJECT's, not the contract's (011 D-c)
 *
 * ~~`WorkItemStatus`, a five-member enum, and `workItemColumns` derived from its labels.~~ →
 * gone. A project names its own statuses, so there is no fixed set to lay the board out from —
 * and there is no single request that answers "every status on this board" either, because My
 * Work and a team board span projects with different workflows.
 *
 * So the columns are **assembled from the cards**, by {@link columnsFor}: a card states its own
 * status, and every card that could move states the full status ref — id, kind and position — for
 * each place it may go. The union of those two is every column the reader can act on.
 *
 * The consequence worth stating: **a status no card holds and no card may reach is not drawn.**
 * That is the right answer for a board (an empty column nobody can drop into is furniture) and
 * the wrong one for the workflow settings screen, which reads `GET /projects/{id}/workflow` and
 * must show every status including the unused ones.
 *
 * ## The four outcomes, and why three of them are not moves
 *
 *  - **Dropped outside every column** (`over` is null): nothing. The most common way a drag ends.
 *  - **Dropped back in the column it came from**: nothing, and this one is the §94 defect in its
 *    board spelling. `move(i, i)` on the step list merely marked a form dirty; here the equivalent
 *    is a transition that writes an unchanged status, taking an audit row and a board refresh for
 *    a gesture that changed nothing.
 *  - **Dropped on an id that is not a column**: nothing. A stale droppable id from a drag that
 *    outlived a re-render, which is the third arm `resolveStepMove` also has.
 *  - **Dropped on a column the card may not enter**: **refused, with the reason**, and refused
 *    *here* rather than sent and reverted. `ui-rules`: never render an action the backend will
 *    always reject — `POST /issues/{key}/transitions` answers 409 for exactly this, and a card
 *    that visibly lands and then snaps back is the worse version of the same information.
 *
 * ## The sentences live here because two affordances must agree
 *
 * The card carries a "Move to…" menu as well (the non-drag route, and the better keyboard one).
 * Both paths end in this function, so a refusal reads identically however it was attempted.
 */

/**
 * A column on the board — a status as the cards themselves describe it.
 *
 * `id` is null for a column assembled from a card's own `status`, which carries only a name and a
 * kind: the id is not needed to *draw* the column, and a move into it is keyed off the moving
 * card's own `allowedTransitions` rather than off this, because two projects on a cross-project
 * board spell "In Progress" with two different ids.
 */
export interface BoardColumn {
  id: string | null;
  name: string;
  kind: IssueStatusKind;
  /** The workflow position, when a card named one. Unknown positions sort last *within* a kind. */
  position: number | null;
}

/**
 * The board's left-to-right order, and it is **kind first, position second**.
 *
 * Position alone is wrong and the first draft of this was: a card's own `status` carries no
 * position (the contract sends a name and a kind there), so the only columns with one are the ones
 * something may move *into* — and the leftmost column of a real workflow is usually not a
 * transition target from anywhere. `Ready` therefore sorted **last**, behind Done, on any board
 * whose backlog is where issues start and never return to.
 *
 * Kind is on every column, always, and it is the axis a reader actually scans: not started, then
 * underway, then done. Position orders within a kind, which is what it is for — two `IN_PROGRESS`
 * statuses in the order the project arranged them.
 */
const KIND_RANK: Record<IssueStatusKind, number> = {
  NOT_STARTED: 0,
  IN_PROGRESS: 1,
  DONE: 2,
};

/**
 * The board's columns, left to right, assembled from the cards on it.
 *
 * Keyed by status **name** rather than id, which is what makes one column out of two projects'
 * "In Progress" on the cross-project board — the reading a person wants there, and the only one
 * available, since the two ids are genuinely different rows.
 */
export function columnsFor(items: readonly BoardCard[]): BoardColumn[] {
  const byName = new Map<string, BoardColumn>();

  for (const item of items) {
    if (!byName.has(item.status.name)) {
      byName.set(item.status.name, {
        id: null,
        name: item.status.name,
        kind: item.status.kind,
        position: null,
      });
    }
    // A transition ref carries the position and the id a card's own status does not, so it
    // upgrades an entry rather than being skipped as a duplicate.
    for (const target of item.allowedTransitions) {
      const existing = byName.get(target.name);
      if (!existing || existing.position === null) {
        byName.set(target.name, {
          id: target.id,
          name: target.name,
          kind: target.kind,
          position: target.position,
        });
      }
    }
  }

  return [...byName.values()].sort(
    (a, b) =>
      KIND_RANK[a.kind] - KIND_RANK[b.kind]
      || (a.position ?? Number.MAX_SAFE_INTEGER) - (b.position ?? Number.MAX_SAFE_INTEGER)
      || a.name.localeCompare(b.name),
  );
}

export type CardMove =
  | { outcome: 'NONE' }
  | { outcome: 'REFUSED'; reason: string }
  | { outcome: 'MOVE'; to: IssueStatusRef };

const NONE: CardMove = { outcome: 'NONE' };

/**
 * What a derived card mirrors, in the sentence that refuses a drop.
 *
 * `Record<Union, …>`'s shape: a new `kind` is a compile error here rather than a card whose
 * refusal names the wrong source — which is precisely what §81 found, every derived card that was
 * not an execution being called a review.
 *
 * `ISSUE` replaces `TASK` in M1 and keeps its words: an issue is still "a task" to the person
 * reading a refusal, and the row-backed card is the one kind that is never derived anyway.
 */
const derivedNouns: Record<BoardCard['kind'], { indefinite: string; bare: string }> = {
  ISSUE: { indefinite: 'an issue', bare: 'issue' },
  EXECUTION: { indefinite: 'an execution', bare: 'execution' },
  REVIEW: { indefinite: 'a review', bare: 'review' },
  DOC_UPDATE: { indefinite: 'a documentation page', bare: 'page' },
  CASE_MAINTENANCE: { indefinite: 'a test case', bare: 'case' },
  DEFECT: { indefinite: 'a defect', bare: 'defect' },
  QUARANTINE: { indefinite: 'a quarantined test', bare: 'quarantine' },
};

/**
 * @param item the card being moved, or null when the drag has no active card
 * @param over the droppable the drag ended over — a column name, or null/undefined
 * @param columns the board's columns, so an `over` that is not one of them is a stale id rather
 *        than a status this board has never heard of
 */
export function resolveCardMove(
  item: BoardCard | null | undefined,
  over: string | number | null | undefined,
  columns: readonly BoardColumn[],
): CardMove {
  if (!item) return NONE;
  if (over === null || over === undefined) return NONE;

  const target = columns.find((column) => column.name === String(over));
  if (target === undefined) return NONE;
  if (target.name === item.status.name) return NONE;

  // The moving card's OWN transition ref, not the column's: the column may have taken its id from
  // a different project's card, and a transition posted with that id names a status this issue's
  // workflow does not contain.
  const allowed = item.allowedTransitions.find((status) => status.name === target.name);
  if (!allowed) return { outcome: 'REFUSED', reason: refusalFor(item, target) };

  return { outcome: 'MOVE', to: allowed };
}

/**
 * Exported for the menu path, which knows the move is refused before it asks — a disabled control
 * needs the same sentence on it that a drop would have produced.
 *
 * An **Epic** is the one refusal that is not about this move in particular: D31 derives an epic's
 * status from its stories, so its `allowedTransitions` is empty whatever the target, and a
 * sentence about "from In Review" would send the reader looking for a workflow rule that is not
 * the reason.
 */
export function refusalFor(item: BoardCard, target: BoardColumn): string {
  if (item.derived) {
    const noun = derivedNouns[item.kind];
    return `${item.linkedKey ?? 'That card'} mirrors ${noun.indefinite}, so it can't be moved to `
      + `${target.name}. Update the ${noun.bare} and the card follows.`;
  }
  if (item.allowedTransitions.length === 0) {
    return `${item.title} can't be moved from the board. An epic's status is derived from its `
      + `stories — move those and this follows.`;
  }
  return `That card can't move to ${target.name} from ${item.status.name} `
    + `(${issueStatusKinds[item.status.kind]}). The project's workflow decides which moves exist.`;
}

/**
 * Why a card offers **no** move at all — the sentence on its disabled "Move to…" trigger.
 *
 * Separate from {@link refusalFor}, which answers about one target and needs one. A card with an
 * empty legal set has no target to be refused for, and the two reasons a set is empty are
 * different facts: a derived card follows its source, and an epic's status is derived from its
 * stories (D31). Both are configuration rather than a missing permission, which is why the
 * control is disabled **with this beside it** rather than hidden — a hidden control reads as "you
 * may not", and neither of these is.
 */
export function immobileReason(item: BoardCard): string {
  if (item.derived) {
    const noun = derivedNouns[item.kind];
    return `This card mirrors ${noun.indefinite}. Update the ${noun.bare} and the card follows.`;
  }
  return `An epic's status is derived from its stories, so it is not moved directly.`;
}
