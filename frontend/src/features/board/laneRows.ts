import type { BoardIssueCard, SwimlaneBy } from '../../api/types';

/**
 * How a lane's cards divide into **one band and the rows that were earned** (021 §2.5).
 *
 * No JSX, for `cardMove.ts`'s reason: a module exporting a component *and* a function trips
 * oxlint's `react(only-export-components)`, and the split buys the thing that matters more — this
 * is the one part of the lane layout that can be *wrong* rather than merely unstyled, and it can
 * be asked directly instead of through a render.
 *
 * ## The rule, from the render's own words
 *
 * `docs/mockups/ia-redesign/README.md` §2 and §17: *"cards stack; rows are earned"*. A lane opens
 * with **one band** in which the epic's own card and every card-story sit in their status columns
 * like a plain kanban. A story earns a row of its own **only when its tasks are the cards on this
 * board** — any of them, even one. An item drawn as a card never also has a row header: without
 * that rule an epic in a status its board claims was drawn three times in one band (the lane bar,
 * a header, and the card) and a story with no tasks twice.
 *
 * ## Rows are grouped from the TASKS, not from the stories
 *
 * The obvious spelling — *"stories that have tasks here"* — loses a card, and silently. A board
 * claims a **subset** of the statuses since R7, so a story can be in a status this board does not
 * show while its tasks are in one it does; the story is then absent from the payload entirely and
 * its tasks belong to a row whose header there is no card for. Grouping by `parentKey` and looking
 * the story up afterwards reaches that case: {@link LaneRow.story} is null and the sub-bar renders
 * from the key alone. Grouping the other way round would have put those tasks in no row and no
 * band — cards on the board that render nowhere, which is the failure the whole layout exists to
 * avoid.
 *
 * ## Only the EPIC axis has rows
 *
 * §2.5 specifies bands and rows for the epic lane. Grouping by assignee, label, priority or
 * release is a different question about the same cards, and a task's story is not the axis there —
 * so those lanes stay flat and `rows` is empty, which is also why `lib/labels.ts::swimlaneByLabels`
 * is untouched by this task.
 */

/** One earned row: a story's sub-bar and the tasks under it that are cards on this board. */
export interface LaneRow {
  /** The story's key — the row's identity even when no card for it reached this board. */
  key: string;
  /** The story's own card, or null when this board does not claim the status it is in. */
  story: BoardIssueCard | null;
  /** Its tasks that are cards here. Never empty — an empty group earns no row. */
  tasks: BoardIssueCard[];
}

export interface LaneShape {
  /** The lane's opening band: the epic's card and every card-item that did not head a row. */
  band: BoardIssueCard[];
  rows: LaneRow[];
}

/**
 * @param cards the lane's cards as this board draws them
 * @param swimlaneBy the board's axis — rows exist for `EPIC` only
 */
export function laneRowsOf(
  cards: readonly BoardIssueCard[],
  swimlaneBy: SwimlaneBy,
): LaneShape {
  if (swimlaneBy !== 'EPIC') return { band: [...cards], rows: [] };

  const tasksByStory = new Map<string, BoardIssueCard[]>();
  for (const card of cards) {
    if (card.typeLevel !== 'TASK' || card.parentKey === null) continue;
    const held = tasksByStory.get(card.parentKey);
    if (held) held.push(card);
    else tasksByStory.set(card.parentKey, [card]);
  }

  const storyByKey = new Map(cards.filter((card) => card.typeLevel === 'STORY').map((c) => [c.key, c]));

  /*
    Ordered by the row's own rank so the rows keep the order the backlog gave them, with the key as
    the tiebreaker — the same comparison the cells inside a cell use, and for the same reason: a
    row that reshuffles between refetches is a row nobody can point at.

    A row whose story is absent sorts on its first task's rank, which is the only rank it has.
  */
  const rows: LaneRow[] = [...tasksByStory.entries()]
    .map(([key, tasks]) => ({ key, story: storyByKey.get(key) ?? null, tasks: sorted(tasks) }))
    .sort((a, b) => rankOf(a).localeCompare(rankOf(b), undefined, { numeric: true })
      || a.key.localeCompare(b.key));

  const inRows = new Set<string>();
  for (const row of rows) {
    inRows.add(row.key);
    for (const task of row.tasks) inRows.add(task.key);
  }

  return { band: cards.filter((card) => !inRows.has(card.key)), rows };
}

function rankOf(row: LaneRow): string {
  return row.story?.rank ?? row.tasks[0].rank;
}

/**
 * A cell's cards, in rank order with the key as the tiebreaker.
 *
 * `rank` is a decimal **as a string**, so `localeCompare` with `numeric` is the comparison that
 * orders `2048` after `512` without doing arithmetic the contract forbids doing on it. Exported
 * because the cell and the row grouping must sort the same way — two spellings of one comparison
 * is how a card ends up in a different place depending on which of them last touched it.
 */
export function sorted(cards: readonly BoardIssueCard[]): BoardIssueCard[] {
  return [...cards].sort(
    (a, b) => a.rank.localeCompare(b.rank, undefined, { numeric: true }) || a.key.localeCompare(b.key),
  );
}

/**
 * The epic cards a lane axis has filed in the wrong lane, keyed by the epic they belong to.
 *
 * **021 R10, and it is a re-file the client has to make.** An epic's `epicKey` is null — it has no
 * epic above it — so the server groups it into the catch-all "No epic" lane whatever its own status
 * is. §2.5 puts it in *its own* lane's band, in its own status column, draggable like any other
 * card; drawing it in the catch-all instead states the same work twice in one view, once beside a
 * roll-up measuring its children.
 *
 * Only epics that **have a lane here** are moved: an epic with no stories on this board has no lane
 * of its own, and suppressing it from the catch-all as well would delete it from the board
 * entirely. A board that silently omits a kind of work is worse than one drawing it in a plain
 * lane.
 */
export function epicCardsByLane(
  lanes: readonly { key: string | null; cards: readonly BoardIssueCard[] }[],
): Map<string, BoardIssueCard> {
  const laneKeys = new Set(lanes.map((lane) => lane.key).filter((key): key is string => key !== null));
  const found = new Map<string, BoardIssueCard>();
  for (const lane of lanes) {
    for (const card of lane.cards) {
      if (card.typeLevel === 'EPIC' && laneKeys.has(card.key)) found.set(card.key, card);
    }
  }
  return found;
}
