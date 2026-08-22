import type { ExecutionAssignmentChange } from '../../api/types'

/**
 * Selection arithmetic for a run's checklist, out of the page.
 *
 * The page owns the state; this owns what the state means. `tallyByAssignee` in particular is
 * display logic over a server result and belongs beside `lib/results.ts`' family rather than in a
 * dialog body — a second implementation of "how many did Ana get" is how two views of one act come
 * to disagree.
 */

/** Adds or removes one row. The array is the identity a `Checkbox` reads its `checked` from. */
export function toggleSelected(selection: string[], id: string): string[] {
  return selection.includes(id) ? selection.filter((each) => each !== id) : [...selection, id]
}

/**
 * Every row currently in view — the filter chips decide what that is, not this.
 *
 * The run detail is not paged: it renders the whole checklist narrowed by the chip, so "in view"
 * is the filter's output and never a page of it. The bar's caption says exactly that.
 */
export function selectAll(rows: { id: string }[]): string[] {
  return rows.map((row) => row.id)
}

/** One person's share of a preview, for the dialog's split. */
export type AssigneeTally = {
  assigneeId: string
  assigneeName: string | null
  count: number
}

/**
 * Per-person counts for a preview, in the order the people were listed.
 *
 * **Seeded from `order` so somebody who receives NOTHING still appears with 0.** That is the case a
 * coordinator most needs to see — a person expecting work and getting none — and it is exactly the
 * one a `groupBy` over `changed` silently drops. The caller's order is preserved because it is what
 * decides who takes the remainder, and a tally that re-sorted would stop reading like the control
 * above it.
 *
 * An unassignment contributes to no tally: `changed` rows landing on nobody have a null
 * `assigneeId`, and "nobody" is not a person with a share.
 */
export function tallyByAssignee(
  changed: ExecutionAssignmentChange[],
  order: string[],
): AssigneeTally[] {
  const names = new Map<string, string | null>()
  const counts = new Map<string, number>()
  for (const id of order) counts.set(id, 0)

  for (const change of changed) {
    if (!change.assigneeId) continue
    names.set(change.assigneeId, change.assigneeName ?? null)
    counts.set(change.assigneeId, (counts.get(change.assigneeId) ?? 0) + 1)
  }

  return [...counts.entries()].map(([assigneeId, count]) => ({
    assigneeId,
    assigneeName: names.get(assigneeId) ?? null,
    count,
  }))
}
