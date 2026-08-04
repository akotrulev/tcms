/**
 * Where a finished drag of a step row lands (§94).
 *
 * ## Why this is a module and not four lines inside `onDragEnd`
 *
 * It is the only part of drag-and-drop that can be *wrong* rather than merely unwired, and it is
 * the only part a component test cannot reach: driving a real pointer drag in jsdom exercises
 * `@dnd-kit`, not this decision, and `onDragEnd` is a prop of a context provider with no other way
 * in. So the decision moves out here where it can be asked directly, and the component keeps the
 * wiring.
 *
 * Three of the four answers are "do nothing", and each is a state a real drag produces:
 *
 *  - dropped outside the list (`over` is null);
 *  - dropped back where it started — `move(i, i)` reorders nothing and marks the form **dirty**,
 *    which on an edit form turns Save on and offers to write a new version of an unchanged case;
 *  - dropped on an id this list no longer contains, which is what a drag that outlives a remove
 *    or a re-render of the field array looks like.
 */
export interface StepMove {
  from: number;
  to: number;
}

/**
 * `ids` is react-hook-form's field ids in render order — the same values `SortableContext` was
 * handed, so `active`/`over` can only ever be one of them or stale.
 */
export function resolveStepMove(
  ids: readonly string[],
  active: string | number,
  over: string | number | null | undefined,
): StepMove | null {
  if (over === null || over === undefined) return null;
  if (active === over) return null;

  const from = ids.indexOf(String(active));
  const to = ids.indexOf(String(over));
  if (from < 0 || to < 0) return null;

  return { from, to };
}
