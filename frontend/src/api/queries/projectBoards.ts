/**
 * The **project** board's reads and writes, its backlog, and the project's labels (012 M2).
 *
 * ~~`boards.ts`~~ → **this file's name is the point of this file's name.** `board.ts` beside it
 * already holds the *composite* board's three reads — a different board, a different endpoint and a
 * different card shape (D-a) — so two modules one letter apart is how a wrong import survives
 * review. 009's execution breakdown named the short one and 012 T6 corrected it.
 *
 * ## One optimistic mutation in the module, and it is the rank one
 *
 * `useMoveCard` is **not** optimistic and `useRankIssue` **is**, which reads as a contradiction
 * until the difference is named: it is *information*, not preference (D-c).
 *
 * Legality of a **status** change is on the card — `BoardIssueCard.allowedTransitions` — so
 * `resolveColumnMove` refuses an illegal drop before a request is made, and scope §12.4 is verbatim
 * binding about what happens next: *"A drop that `issue_transition` forbids is refused at the drag,
 * with the reason on screen. It is never accepted optimistically and reverted."* There is nothing
 * left for an optimistic patch to buy except the one failure mode that sentence rules out.
 *
 * Legality of a **rank** change is the server's neighbours, which the client cannot know. So there
 * is nothing to refuse up front, and a rank drag with no optimistic patch is a row that hangs where
 * it was for a round trip and then jumps — which reads worse than a row that moves and, rarely,
 * snaps back saying why. `tcmsFaults.rank('conflict')` is the switch that proves the snap-back, and
 * it landed in the same task as this file, before the screen that uses it.
 */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type {
  BacklogRow,
  BoardConfig,
  BoardContents,
  BoardFilters,
  BoardInput,
  BoardSummary,
  CardMoveResult,
  IssueLabel,
  IssueLabelInput,
  IssueLabelsInput,
  RankResult,
  WipBreach,
} from '../types';
import { toast } from '../../notifications/toast';
import { keys } from './keys';

/** Every board over this project, in name order. Never empty — a project is seeded one. */
export function useBoards(projectId: string): UseQueryResult<BoardSummary[]> {
  return useQuery({
    queryKey: keys.boards(projectId),
    queryFn: ({ signal }) =>
      api.get<BoardSummary[]>(`/projects/${projectId}/boards`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * One board's **configuration** — its columns and its swimlane axis, and no cards.
 *
 * A read of its own rather than a member of the cards read, because the two have different
 * lifetimes (D-h): columns change when somebody edits them, cards on every transition anywhere in
 * the project. One combined read would re-fetch the column layout on every card move and make the
 * board relayout under the reader's hand.
 */
export function useBoardConfig(boardId: string): UseQueryResult<BoardConfig> {
  return useQuery({
    queryKey: keys.board(boardId),
    queryFn: ({ signal }) =>
      api.get<BoardConfig>(`/boards/${boardId}`, { signal, timeoutMs: TIMEOUTS.interactive }),
    enabled: boardId !== '',
  });
}

/**
 * One board's cards, in lanes, filtered.
 *
 * The whole filter object is in the key, so a slow answer for one chip set can never land under
 * another's. `qs` spells a repeated parameter for an array and drops an `undefined`, so an absent
 * filter never reaches the server as an empty one.
 *
 * **`BoardContents.breaches` is counted over the project and is rendered as sent.** It does not
 * shrink when a filter is applied, and nothing here recomputes it from the cards that arrived: a
 * WIP limit is a property of the column, so a header must report the same breach whatever happens
 * to be on screen.
 */
export function useBoardCards(
  boardId: string,
  filters: BoardFilters,
): UseQueryResult<BoardContents> {
  return useQuery({
    queryKey: keys.boardCards(boardId, filters),
    queryFn: ({ signal }) =>
      api.get<BoardContents>(`/boards/${boardId}/cards${qs({ ...filters })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: boardId !== '',
  });
}

/** The ranked backlog, under the same filter the board carries — one filter, three views (D34). */
export function useBacklog(
  projectId: string,
  filters: BoardFilters,
): UseQueryResult<BacklogRow[]> {
  return useQuery({
    queryKey: keys.backlog(projectId, filters),
    queryFn: ({ signal }) =>
      api.get<BacklogRow[]>(`/projects/${projectId}/backlog${qs({ ...filters })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * Move a card to a column. **Not optimistic**, per §12.4 and this module's header.
 *
 * Invalidates the cards of **every** board rather than this one's, which is what makes the module's
 * done-criterion true on screen instead of only in the database: a project has several boards over
 * the same statuses (D2), so *"a card moved on one board is in the matching column on the other"*
 * requires the other's cache to go with it. `keys.boardCardsRoot` reaches all of them and touches no
 * board's configuration, which is the shape that key comment argues for.
 *
 * The issue lists and the issue details go too, for the reason `useTransitionIssue` gives: a move
 * changes the parent epic's completion, and the epic is a different cache entry on a different
 * screen.
 *
 * `meta.handled` because the screen says which move was refused and what is legal instead, which is
 * strictly more useful than the global handler's "couldn't save".
 */
export function useMoveCard(boardId: string, projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, toColumnId }: { key: string; toColumnId: string }) =>
      api.post<CardMoveResult>(
        `/boards/${boardId}/cards/${encodeURIComponent(key)}/move`,
        { toColumnId },
        { timeoutMs: TIMEOUTS.interactive },
      ),
    meta: { handled: true },
    onSuccess: (result) => {
      void client.invalidateQueries({ queryKey: keys.boardCardsRoot });
      void client.invalidateQueries({ queryKey: keys.backlogRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.issuesRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
      if (result.breach) toast(breachMessage(result.breach), { variant: 'warning' });
    },
  });
}

/**
 * What a breach says at the moment it is caused — a **warning**, never an error.
 *
 * The move happened; story B3 is explicit that the limit is *reported, never enforced*, and a limit
 * that refused drops is one people route around by not using the board. So the sentence leads with
 * the fact rather than with an apology, and says who decides.
 *
 * It does not name the column: `CardMoveResult.breach` carries `columnId`, `limit` and `count` and
 * no name, and looking one up from a cache the invalidation above has just marked stale would be a
 * name that could disagree with the board. The card the reader just dropped is the referent.
 */
function breachMessage(breach: WipBreach): string {
  return `That column now holds ${breach.count} of a ${breach.limit} limit. The move went through — `
    + `a WIP limit is reported, not enforced.`;
}

/**
 * Place an issue between two neighbours. **Optimistic, with a snapshot rollback** — and this is the
 * one place in the product where that is right (see the module header for why).
 *
 * ## Exactly one neighbour, and "neither" is not "to the top" (D-q)
 *
 * The server answers **400** for both neighbours and for neither, and T4 landed it that way against
 * a `RankInput.afterKey` description that still says an empty body means *"to the top"*. That
 * sentence is stale: the top **is** reachable, as `beforeKey: <the current first row>`, so the
 * caller of this hook always names one row. The asymmetry settles it — answer "to the top" when 400
 * was meant and an empty `PUT` silently moves somebody's issue.
 *
 * ## `rebalanced: true` drops the patch rather than rolling it back
 *
 * It is a **success**, and the easiest thing here to get backwards in either direction. The
 * placement landed; what is now stale is every *other* row's rank, so the optimistic order is no
 * longer a prediction of anything and the list is invalidated. Treating it as a failure would snap
 * back a row the server did move; keeping the patch would show an order the server does not have.
 *
 * The patch, the snapshot and the rollback all target `keys.backlogRoot` rather than one filter's
 * key: a drag on a filtered list reorders rows in the unfiltered one too, and a rollback that
 * restored only the list on screen would leave the other holding the order the server refused.
 */
export function useRankIssue(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, afterKey, beforeKey }: RankPlacement) =>
      api.put<RankResult>(
        `/issues/${encodeURIComponent(key)}/rank`,
        afterKey === undefined ? { beforeKey } : { afterKey },
        { timeoutMs: TIMEOUTS.interactive },
      ),
    meta: { handled: true },
    onMutate: async (placement) => {
      // Cancelled first, or an in-flight read resolves after the patch and overwrites it with the
      // order from before the drag — the patch would then vanish with no error and no refetch.
      await client.cancelQueries({ queryKey: keys.backlogRoot(projectId) });
      const snapshot = client.getQueriesData<BacklogRow[]>({
        queryKey: keys.backlogRoot(projectId),
      });
      client.setQueriesData<BacklogRow[]>(
        { queryKey: keys.backlogRoot(projectId) },
        (rows) => (rows ? reorder(rows, placement) : rows),
      );
      return { snapshot };
    },
    onError: (_error, _placement, context) => {
      // The snap-back. `tcmsFaults.rank('conflict')` is what proves this line runs, and without it
      // a board that never fails is indistinguishable from one whose rollback was never written.
      for (const [key, data] of context?.snapshot ?? []) client.setQueryData(key, data);
    },
    onSuccess: (result) => {
      if (result.rebalanced) {
        void client.invalidateQueries({ queryKey: keys.backlogRoot(projectId) });
      }
    },
    onSettled: () => {
      void client.invalidateQueries({ queryKey: keys.backlogRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.boardCardsRoot });
    },
  });
}

/**
 * Where a row is going, as the endpoint spells it: the issue, and **one** neighbour.
 *
 * A union rather than two optional properties, so "both" and "neither" — the two payloads the server
 * answers 400 for — cannot be constructed by a caller at all. The cross-field rule no `required`
 * array can express is expressible here.
 */
export type RankPlacement =
  | { key: string; afterKey: string; beforeKey?: undefined }
  | { key: string; beforeKey: string; afterKey?: undefined };

/**
 * The backlog in the order the drag predicts — the optimistic patch, and nothing else.
 *
 * `position` is renumbered because it is the 1-based number the row *shows*, derived from rank order
 * and never stored: leaving it would draw the moved row with its old number beside its new place.
 * `rank` is deliberately **not** touched — the server owns the midpoint, this cannot compute it
 * without arithmetic on a decimal string the contract forbids doing arithmetic on, and the refetch
 * `onSettled` fires replaces the whole list within the round trip anyway.
 *
 * A placement naming a row this list does not hold returns the list unchanged: the drag was against
 * a different filter's list, and inventing a position in this one would be a reorder nobody asked
 * for.
 */
export function reorder(rows: readonly BacklogRow[], placement: RankPlacement): BacklogRow[] {
  const moved = rows.findIndex((row) => row.key === placement.key);
  const neighbourKey = placement.afterKey ?? placement.beforeKey;
  const neighbour = rows.findIndex((row) => row.key === neighbourKey);
  if (moved === -1 || neighbour === -1) return [...rows];

  const without = rows.filter((_row, index) => index !== moved);
  const at = without.findIndex((row) => row.key === neighbourKey);
  const insertAt = placement.afterKey === undefined ? at : at + 1;

  const next = [...without.slice(0, insertAt), rows[moved], ...without.slice(insertAt)];
  return next.map((row, index) => ({ ...row, position: index + 1 }));
}

export function useLabels(projectId: string): UseQueryResult<IssueLabel[]> {
  return useQuery({
    queryKey: keys.labels(projectId),
    queryFn: ({ signal }) =>
      api.get<IssueLabel[]>(`/projects/${projectId}/labels`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * Creating, renaming and deleting a project's labels — `board:configure`, and no `label:*` key
 * exists (D-e).
 *
 * All three invalidate the label list **and** every board's cards: a card wears its labels, so a
 * rename that refreshed only the settings screen would leave the old word on every card until
 * something else happened to refetch.
 */
export function useCreateLabel(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: IssueLabelInput) =>
      api.post<IssueLabel>(`/projects/${projectId}/labels`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    onSuccess: () => invalidateLabelReaders(client, projectId),
  });
}

export function useUpdateLabel(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ labelId, input }: { labelId: string; input: IssueLabelInput }) =>
      api.patch<IssueLabel>(`/labels/${labelId}`, input, { timeoutMs: TIMEOUTS.interactive }),
    onSuccess: () => invalidateLabelReaders(client, projectId),
  });
}

/**
 * Takes the project as well as the label, and needs it: the delete answers 204 with no body, so
 * there is nothing to read the project id back out of.
 */
export function useDeleteLabel(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (labelId: string) =>
      api.del<void>(`/labels/${labelId}`, { timeoutMs: TIMEOUTS.interactive }),
    onSuccess: () => invalidateLabelReaders(client, projectId),
  });
}

/**
 * Replacing an issue's labels — `issue:edit`, because it edits the issue (D-e).
 *
 * A `PUT` of the whole set rather than an add and a remove: the screen's control is a set of chips
 * and the server's rule is about the set, so two verbs would let a client hold a half-applied
 * selection.
 */
export function usePutIssueLabels(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, input }: { key: string; input: IssueLabelsInput }) =>
      api.put<IssueLabel[]>(`/issues/${encodeURIComponent(key)}/labels`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.boardCardsRoot });
      void client.invalidateQueries({ queryKey: keys.backlogRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.issuesRoot(projectId) });
    },
  });
}

function invalidateLabelReaders(client: ReturnType<typeof useQueryClient>, projectId: string) {
  void client.invalidateQueries({ queryKey: keys.labels(projectId) });
  void client.invalidateQueries({ queryKey: keys.boardCardsRoot });
  void client.invalidateQueries({ queryKey: keys.backlogRoot(projectId) });
}

/**
 * Defining a board — its name, its swimlane axis and its columns. `board:configure`.
 *
 * `columns` on the update is a **replacement, not a patch**: a column with an `id` is kept and
 * updated, one without is created, and one whose id is absent from the array is deleted. Position is
 * the array index. No issue moves either way, because a column never owned one.
 *
 * The create and the delete invalidate the picker's list; the update invalidates this board's
 * configuration **and** its cards, because a column removed or a status re-claimed changes which
 * column a card is in.
 */
export function useCreateBoard(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: BoardInput) =>
      api.post<BoardConfig>(`/projects/${projectId}/boards`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.boards(projectId) }),
  });
}

export function useUpdateBoard(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ boardId, input }: { boardId: string; input: BoardInput }) =>
      api.patch<BoardConfig>(`/boards/${boardId}`, input, { timeoutMs: TIMEOUTS.interactive }),
    onSuccess: (board) => {
      void client.invalidateQueries({ queryKey: keys.boards(projectId) });
      void client.invalidateQueries({ queryKey: keys.board(board.id) });
      void client.invalidateQueries({ queryKey: keys.boardCardsRoot });
    },
  });
}

export function useDeleteBoard(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (boardId: string) =>
      api.del<void>(`/boards/${boardId}`, { timeoutMs: TIMEOUTS.interactive }),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.boards(projectId) }),
  });
}
