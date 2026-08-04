/** The kanban board's work items. Instance-scoped paths, project-scoped keys. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useRef } from 'react';
import { api, newIntentKey, qs } from '../client';
import type { WorkItem, WorkItemInput, WorkItemStatus } from '../types';
import { keys } from './keys';

export function useWorkItems(projectId: string, mine: boolean): UseQueryResult<WorkItem[]> {
  return useQuery({
    queryKey: keys.workItems(projectId, mine),
    queryFn: ({ signal }) =>
      api.get<WorkItem[]>(`/work-items${qs({ projectId, mine: mine ? 'true' : undefined })}`, { signal }),
  });
}

/**
 * §6l's cross-project **My Work** board (§83) — everything assigned to the caller, in every project
 * they can see.
 *
 * The same operation as {@link useWorkItems} with the project left off, which is why no path
 * changed: `/work-items` has been instance-scoped since Phase 1 and the project has always been a
 * query parameter. `qs` drops an `undefined`, so there is no `projectId=` on the wire to be read as
 * an empty one.
 *
 * **`mine=true` is hard-coded rather than a parameter.** Omitting the project with `mine=false` is
 * a **400** on the server ("Choose a board"), deliberately — there is no board that is every
 * project's work unfiltered. A hook that could send it would exist only to produce that error.
 *
 * Left on the default `interactive` budget even though the read behind it is the company-sized one
 * (`BoardService.personalBoard` says so in its own header): this is the screen §6l expects somebody
 * to keep open all day, so a 60-second ceiling would mean a minute of spinner on the page a person
 * looks at most. If it ever needs longer, the fix is the server's generators, not this number.
 */
export function useMyWork(): UseQueryResult<WorkItem[]> {
  return useQuery({
    queryKey: keys.myWork,
    queryFn: ({ signal }) => api.get<WorkItem[]>(`/work-items${qs({ mine: 'true' })}`, { signal }),
  });
}

/**
 * §6l's **team board** (§84) — one team's work, across every project the CALLER can see.
 *
 * The third selector on the same operation, which is why there is no new path here either:
 * `projectId` narrows *where*, `teamId` and `mine` narrow *whose*, and the response shape and sort
 * are identical in all three cases. A `/teams/{id}/work-items` route would be a second door onto
 * one read model.
 *
 * **The bound is the caller's own project visibility, never the team's**, and that has a
 * consequence this hook's callers must not design around: **two people asking for the same team's
 * board legitimately receive different card sets.** So nothing built on this may present the answer
 * as the team's canonical workload — no "the team has N items", no capacity arithmetic, no
 * percentage of anything. What the reader is holding is *this team's work, as much of it as they
 * are entitled to see*. The alternative the server refused is a cross-project leak with a team id
 * for a key: the case keys, titles and project names of every project any member was granted,
 * rendered to somebody granted none of them.
 *
 * **Neither `mine` nor `projectId` is a parameter**, and that is why this hook cannot produce the
 * operation's second 400. `teamId` with `mine=true` selects two boards at once and is refused; a
 * hook able to send it would exist only to make that error. The `projectId` conjunction is legal
 * (§6l's "swimlanes by member") and is left unbuilt rather than half-built — see `TeamBoardPage`.
 *
 * **404 is a real answer here, unlike on the personal board**: this request names something that
 * can fail to resolve. An unknown team, another company's team and a `projectId` the caller cannot
 * reach are one indistinguishable, empty-bodied 404 on purpose, or the operation becomes a
 * company-wide team-existence oracle answerable with a guessed UUID (§6t). `TeamBoardPage` supplies
 * the sentence the server deliberately does not.
 */
export function useTeamBoard(teamId: string): UseQueryResult<WorkItem[]> {
  return useQuery({
    queryKey: keys.teamBoard(teamId),
    queryFn: ({ signal }) => api.get<WorkItem[]>(`/work-items${qs({ teamId })}`, { signal }),
  });
}

/**
 * Moves a card.
 *
 * Takes no board: `PATCH /work-items/{id}` names no project either — the card's own row carries it,
 * which is what lets this one hook serve the project board and the personal one. The invalidation
 * is the **root** rather than one board's key for the reason `keys.myWork` records: the same card
 * appears on both, so refreshing only the board you happened to be looking at leaves the other
 * holding a card in the column it just left.
 */
export function useMoveWorkItem() {
  const client = useQueryClient();
  return useMutation({
    // `status` is the contract's enum rather than a bare string. Until §53 it was `string` on both
    // sides and the board compared it against English labels — see `lib/labels.ts` for the defect
    // that hid behind an unconstrained type on a field the mock and the page both invented.
    mutationFn: ({ id, status }: { id: string; status: WorkItemStatus }) =>
      api.patch<WorkItem>(`/work-items/${id}`, { status }),
    // The board says which card failed to move and where it snapped back to; that is strictly
    // more useful than "couldn't save", so the global handler stays out.
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: WORK_ITEM_ROOT }),
  });
}

/** Every board's key starts here — see `keys.myWork` on why the two share a root. */
const WORK_ITEM_ROOT = ['work-items'] as const;

/**
 * Creates a task.
 *
 * `mine` was a parameter until §83, only ever used to name the one board key to invalidate — which
 * meant a task created while the "Everyone" chip was selected left the `mine=true` cache holding a
 * board without it, and the personal board (§83) would have made that a third stale copy. The root
 * invalidation covers every board at once; see `keys.myWork`.
 */
export function useCreateWorkItem(projectId: string) {
  const client = useQueryClient();
  /*
    One key per mounted dialog — that is, per user intent to add *this* task. It survives
    re-renders and failed attempts, so pressing "Add task" again after a timeout sends the same
    key and the server answers with the card it already made instead of a second one.

    **This only works because `TaskCreateDialog` is conditionally rendered.** A dialog left
    mounted behind `open={false}` holds one key for the life of the page, and the second task
    somebody typed would come back as a replay of the first — a create the user watched succeed
    that stored nothing new, which is worse than no idempotency at all. `RunCreateDialog` and
    `PlanCreateDialog` are already mounted that way and say so; this one was the outlier until §54.
  */
  const intentKey = useRef(newIntentKey());
  return useMutation({
    // `Omit<…, 'projectId'>` rather than `Record<string, unknown>`: the project comes from the
    // hook's own argument, so a caller passing one would have it silently overwritten — and an
    // untyped body was how this hook sat unused for sixteen slices without anyone noticing it
    // could not have compiled against a real payload.
    mutationFn: (input: Omit<WorkItemInput, 'projectId'>) =>
      api.post<WorkItem>('/work-items', { ...input, projectId }, { idempotencyKey: intentKey.current }),
    onSuccess: () => client.invalidateQueries({ queryKey: WORK_ITEM_ROOT }),
  });
}
