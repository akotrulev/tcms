/** Plan §6g's review record — the trail behind a submitted case, suite or plan. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type {
  ProjectSettings,
  ProjectSettingsUpdate,
  Review,
  ReviewSubjectType,
} from '../types';
import { base, keys } from './keys';

/**
 * The project's §6g policy — **read by every artifact screen, not just the settings one**.
 *
 * §6g's first requirement is that "a project that never turns review on should see no extra UI at
 * all", so the review panel's very existence depends on this. The endpoint is therefore gated on
 * project visibility rather than on `project:settings`: a Viewer must be able to learn that review
 * is off, or the requirement is unimplementable for exactly the readers it is about.
 *
 * No `enabled` gate on the project id, unlike the subject-keyed queries below — a project route
 * cannot render without its `projectId`, and every sibling project-scoped query here is written the
 * same way.
 */
export function useProjectSettings(projectId: string): UseQueryResult<ProjectSettings> {
  return useQuery({
    queryKey: keys.projectSettings(projectId),
    queryFn: ({ signal }) =>
      api.get<ProjectSettings>(`${base(projectId)}/settings`, { signal }),
  });
}

/**
 * Replaces the project's settings. Needs `project:settings`.
 *
 * A `PUT` of the whole value, matching the contract: the form submits every control, so there is no
 * partial-update shape for a caller to get wrong.
 *
 * Invalidates rather than writing the response into the cache, for `useRecordReviewVerdict`'s
 * reason — and one more that is specific to this key: **turning review on or off changes what every
 * open artifact screen should render**, so the panels have to re-ask rather than keep a copy taken
 * before the switch moved.
 */
export function useUpdateProjectSettings(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (update: ProjectSettingsUpdate) =>
      api.put<ProjectSettings>(`${base(projectId)}/settings`, update),
    // The form shows the 403 and the field errors in place, beside the controls they are about.
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: keys.projectSettings(projectId) }),
  });
}

/**
 * Every review of one subject, newest first — usually one, often none.
 *
 * An empty array is the ordinary answer for an artifact nobody has submitted, so the panel renders
 * it as "not in review" rather than as an empty state that suggests something is missing.
 *
 * `enabled` on the subject id for the reason every dependent query here has one: a detail route
 * renders before its params resolve, and a request with `subjectId=undefined` in the URL would be
 * answered — with a 400 — and cached under a key the real id never reads.
 */
export function useReviews(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string | undefined,
): UseQueryResult<Review[]> {
  return useQuery({
    queryKey: keys.reviews(projectId, subjectType, subjectId ?? ''),
    queryFn: ({ signal }) =>
      api.get<Review[]>(`${base(projectId)}/reviews${qs({ subjectType, subjectId })}`, { signal }),
    enabled: !!subjectId,
  });
}

/**
 * Records the caller's peer verdict on an open review.
 *
 * The verdict type is narrowed to the two advisory values on purpose. The server refuses
 * `APPROVED` and `REJECTED` with a field error — they end a review and belong to the artifact's own
 * status ladder — and a client type that could express them would be an affordance the product
 * does not have (§31's disable-don't-hide, at the level of the type).
 *
 * The mutation invalidates the review list rather than writing the response into the cache: the
 * server returns the whole re-rendered review, and taking it as truth here would leave the two
 * copies to diverge the first time a field is added to one of them.
 */
export function useRecordReviewVerdict(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      reviewId,
      verdict,
      comment,
    }: {
      reviewId: string;
      verdict: 'LGTM' | 'CHANGES_REQUESTED';
      comment?: string | null;
    }) =>
      api.post<Review>(`${base(projectId)}/reviews/${reviewId}/participations`, {
        verdict,
        comment: comment?.trim() ? comment.trim() : null,
      }),
    // The panel shows the 403 and the 409 in place, beside the review they are about — a toast
    // detached from the trail would be a vaguer copy of a message already on screen.
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: keys.reviews(projectId, subjectType, subjectId) }),
  });
}

/**
 * Writes one remark on an open review — §6g's threads.
 *
 * Costs `case:comment` whatever the subject is, which is the server's decision and not this hook's:
 * the catalogue has one comment key where it has three review keys, because commenting is the
 * bottom **tier** rather than a per-artifact judgement. `lib/permissions.ts` already carries the
 * key; what this file must not do is spell a `suite:comment` that does not exist.
 *
 * `parentId` is optional and absent means "start a thread". Invalidates rather than writing the
 * response into the cache, for `useRecordReviewVerdict`'s reason.
 */
export function useAddReviewComment(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      reviewId,
      body,
      parentId,
    }: {
      reviewId: string;
      body: string;
      parentId?: string | null;
    }) =>
      api.post<Review>(`${base(projectId)}/reviews/${reviewId}/comments`, {
        body: body.trim(),
        parentId: parentId ?? null,
      }),
    // In place, beside the thread it is about — `useRecordReviewVerdict`'s reason.
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: keys.reviews(projectId, subjectType, subjectId) }),
  });
}

/**
 * Asks a person or a team to review — §6g's reviewer assignment.
 *
 * Costs the subject's `*:review`, the same key the verdict does and **not** `case:comment`: asking
 * somebody to look at an artifact is a statement about the review's progress, so the bottom tier
 * does not get to fill other people's queues. That is the server's decision; what this hook must
 * not do is offer a shape the server refuses.
 *
 * The argument is a union rather than two optional ids, so "neither" and "both" — the server's one
 * field error — are **unrepresentable here**. §31's disable-don't-hide at the level of the type,
 * the narrowing `useRecordReviewVerdict` already applies to its verdict.
 *
 * Invalidates rather than writing the response into the cache, for `useRecordReviewVerdict`'s
 * reason.
 */
export function useAssignReviewer(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      reviewId,
      assignee,
    }: {
      reviewId: string;
      assignee: { userId: string } | { teamId: string };
    }) =>
      api.post<Review>(`${base(projectId)}/reviews/${reviewId}/assignees`, {
        userId: 'userId' in assignee ? assignee.userId : null,
        teamId: 'teamId' in assignee ? assignee.teamId : null,
      }),
    // In place, beside the list it is about — `useRecordReviewVerdict`'s reason. The refusals this
    // one can reach are all about *who* was named, which only means anything next to the picker.
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: keys.reviews(projectId, subjectType, subjectId) }),
  });
}

/**
 * Withdraws a request. Costs the same `*:review` the assignment did.
 *
 * Addresses the **assignment's** id, not the assignee's: one person may be asked to review several
 * artifacts, and an assignee-keyed delete could name a pairing that never existed.
 */
export function useUnassignReviewer(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ reviewId, assignmentId }: { reviewId: string; assignmentId: string }) =>
      api.del<Review>(`${base(projectId)}/reviews/${reviewId}/assignees/${assignmentId}`),
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: keys.reviews(projectId, subjectType, subjectId) }),
  });
}

/**
 * Marks a thread dealt with, or re-opens it. Costs the subject's `*:review` key — **not**
 * `case:comment`, and the asymmetry with the hook above is deliberate on the server's side.
 *
 * A `PUT` of the whole state rather than a toggle, so two clicks racing settle on what was asked for
 * rather than on how many requests arrived.
 */
export function useSetReviewCommentResolution(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      reviewId,
      commentId,
      resolved,
    }: {
      reviewId: string;
      commentId: string;
      resolved: boolean;
    }) =>
      api.put<Review>(
        `${base(projectId)}/reviews/${reviewId}/comments/${commentId}/resolution`,
        { resolved },
      ),
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: keys.reviews(projectId, subjectType, subjectId) }),
  });
}
