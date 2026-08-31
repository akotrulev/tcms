import type { BoardCard } from '../../api/types';

/**
 * Where a card's key takes you.
 *
 * **Keyed on `sourceType`, not on `kind`, and that is the repair §81 owed.** Every card used to
 * route through the case list — a search for `linkedKey` — which was right while the only linked
 * cards were about cases. The reviewer's inbox draws cards for **suites and plans** too, and a
 * review of `TP-5` sent to a case search finds nothing and says "no cases match", which reads as the
 * plan having been deleted.
 *
 * `sourceType` is what the server puts the *artifact* in for exactly this reason (`BoardService`'s
 * card comment): a review has no URL of its own — §6g's panel is rendered on the artifact's own page
 * — so navigating to the artifact is navigating to the review.
 *
 * **A suite has no detail page**, which is why that arm is the case list filtered to it rather than
 * a route: the suite tree is the case list's left pane, and `SUITE` is the one subject type §6g can
 * review with no screen of its own. An unrecognised `sourceType` falls back to the search, which is
 * where the old behaviour is still correct — a card the SPA has no route for should still land
 * somewhere that mentions the thing. `EXECUTION` is deliberately one of those: its `sourceId` names
 * the execution, whose route needs the run id, and a card does not carry one.
 *
 * **A module of its own since 011 T7**, where `my-work.html`'s two tables replaced the personal
 * kanban: the tables render the same cards as rows and need the same destination, and a second copy
 * of this function is how one card comes to open two different screens depending on which of the
 * three boards you found it on. It sits beside `cardMove.ts` rather than being exported from
 * `BoardPage.tsx`, because a file that exports both a component and a function is a fast-refresh
 * warning and this project's lint gate is zero warnings.
 *
 * **The project comes from the CARD, not from the route (§83), and that is the second repair this
 * function has needed.** §81 fixed *which screen* a card opens and left the project as the board's
 * own — correct while every board was one project's, and silently wrong the moment §6l's
 * cross-project board exists: a review of a case in MOB, read on My Work, would have been sent to
 * `/projects/{OLB}/cases/{a case OLB does not contain}` — a 404 on the one affordance the card
 * exists to offer, and on the project board it is the same value, so nothing would have looked
 * different where anybody was testing. `projectId` has been **required** on every card since §53
 * (`BoardCard.projectId`), so there is no fallback arm and no route parameter to disagree with.
 */
export function linkFor(item: BoardCard): string {
  // An `ISSUE` card is the one kind that is a ROW, and since M1 it has a page of its own. Its
  // `id` IS the key — `ACME-124` — which is what `/issues/:key` takes, and what makes this the
  // only arm that needs no project segment: a key is unique in the only scope RLS lets a caller
  // see. Before M1 this fell through to a case search that could not have found it.
  if (item.kind === 'ISSUE' && !item.derived) return `/issues/${encodeURIComponent(item.id)}`;

  const projectId = item.projectId;
  const search = `/projects/${projectId}/cases?q=${encodeURIComponent(item.linkedKey ?? '')}`;
  if (!item.sourceId) return item.linkedKey ? search : `/projects/${projectId}/cases`;

  switch (item.sourceType) {
    case 'CASE':
      return `/projects/${projectId}/cases/${item.sourceId}`;
    case 'SUITE':
      return `/projects/${projectId}/cases?suiteId=${item.sourceId}`;
    case 'PLAN':
      return `/projects/${projectId}/plans/${item.sourceId}`;
    default:
      return search;
  }
}
