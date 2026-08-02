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
