/**
 * The three boards — a project's, your own, a team's — and the viewer's private note on a card.
 *
 * ~~`workItems.ts`.~~ → **renamed with the endpoint** (011 D-a). `GET /work-items` named a table
 * that no longer exists: `work_item` is gone, a row-backed card is a `tracker` issue, and the
 * operation moved to `GET /board` unchanged in shape and in all three of its board semantics.
 * `POST /work-items` and `PATCH /work-items/{id}` are gone outright — creating an issue is
 * `POST /issues` and moving one is `POST /issues/{key}/transitions`, both in `issues.ts`.
 *
 * The keys keep their `workItem*` names on purpose: the argument for one root is unchanged, and
 * renaming eleven call sites would say nothing new. `keys.ts` records that.
 */
import { useQuery, useMutation, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { BoardCard, CardAnnotation, CardAnnotationInput } from '../types';
import { keys } from './keys';

export function useBoard(projectId: string, mine: boolean): UseQueryResult<BoardCard[]> {
  return useQuery({
    queryKey: keys.workItems(projectId, mine),
    queryFn: ({ signal }) =>
      api.get<BoardCard[]>(`/board${qs({ projectId, mine: mine ? 'true' : undefined })}`, { signal }),
  });
}

/**
 * §6l's cross-project **My Work** board (§83) — everything assigned to the caller, in every project
 * they can see.
 *
 * The same operation as {@link useBoard} with the project left off, which is why nothing but the
 * path changed: the project has always been a query parameter. `qs` drops an `undefined`, so there
 * is no `projectId=` on the wire to be read as an empty one.
 *
 * **`mine=true` is hard-coded rather than a parameter.** Omitting the project with `mine=false` is
 * a **400** on the server ("Choose a board"), deliberately — there is no board that is every
 * project's work unfiltered. A hook that could send it would exist only to produce that error.
 *
 * Left on the default `interactive` budget even though the read behind it is the company-sized
 * one: this is the screen §6l expects somebody to keep open all day, so a 60-second ceiling would
 * mean a minute of spinner on the page a person looks at most. If it ever needs longer, the fix is
 * the server's generators, not this number.
 */
export function useMyWork(): UseQueryResult<BoardCard[]> {
  return useQuery({
    queryKey: keys.myWork,
    queryFn: ({ signal }) => api.get<BoardCard[]>(`/board${qs({ mine: 'true' })}`, { signal }),
  });
}

/**
 * §6l's **team board** (§84) — one team's work, across every project the CALLER can see.
 *
 * The third selector on the same operation: `projectId` narrows *where*, `teamId` and `mine`
 * narrow *whose*, and the response shape and sort are identical in all three cases. A
 * `/teams/{id}/board` route would be a second door onto one read model.
 *
 * **The bound is the caller's own project visibility, never the team's**, and that has a
 * consequence this hook's callers must not design around: **two people asking for the same team's
 * board legitimately receive different card sets.** So nothing built on this may present the
 * answer as the team's canonical workload — no "the team has N items", no capacity arithmetic, no
 * percentage of anything. The alternative the server refused is a cross-project leak with a team
 * id for a key.
 *
 * **Neither `mine` nor `projectId` is a parameter**, and that is why this hook cannot produce the
 * operation's second 400: `teamId` with `mine=true` selects two boards at once and is refused.
 *
 * **404 is a real answer here, unlike on the personal board**: this request names something that
 * can fail to resolve, and an unknown team, another company's team and an unreachable project are
 * one indistinguishable, empty-bodied 404 on purpose (§6t). `TeamBoardPage` supplies the sentence
 * the server deliberately does not.
 */
export function useTeamBoard(teamId: string): UseQueryResult<BoardCard[]> {
  return useQuery({
    queryKey: keys.teamBoard(teamId),
    queryFn: ({ signal }) => api.get<BoardCard[]>(`/board${qs({ teamId })}`, { signal }),
  });
}

/**
 * The viewer's **own** note, personal due date and flag on a card (story B5).
 *
 * Keyed on the caller server-side, so a teammate looking at the same issue does not see this — and
 * it costs no permission beyond seeing the card, because annotating something is not editing it.
 * That is the whole reason the table is separate from the row, and the reason this write lives
 * here rather than in `issues.ts`: it is a property of the *card as this person sees it*, and it
 * survives on a derived card that has no issue at all.
 */
export function usePutCardAnnotation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      sourceType,
      sourceId,
      input,
    }: {
      sourceType: string;
      sourceId: string;
      input: CardAnnotationInput;
    }) =>
      api.put<CardAnnotation>(
        `/board/annotations/${encodeURIComponent(sourceType)}/${encodeURIComponent(sourceId)}`,
        input,
      ),
    // The root, not one board's key: the same card is on the project board, My Work and every
    // team board its assignee belongs to, and a note that refreshed only the board you were
    // looking at would leave the others showing the previous text.
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workItemRoot }),
  });
}
