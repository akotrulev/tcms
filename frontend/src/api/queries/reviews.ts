/** Plan §6g's review record — the trail behind a submitted case, suite or plan. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { Review, ReviewSubjectType } from '../types';
import { base, keys } from './keys';

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
