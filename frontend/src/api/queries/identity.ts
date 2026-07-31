/** Who the caller is, how they got in, and what they may do. */
import { useMutation, useQuery, type UseMutationResult, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { CurrentUser, EffectivePermissions, InvitationPreview } from '../types';
import { keys } from './keys';

export function useCurrentUser(): UseQueryResult<CurrentUser> {
  return useQuery({
    queryKey: keys.me,
    queryFn: ({ signal }) => api.get<CurrentUser>('/me', { signal }),
  });
}

/**
 * What an invitation admits its holder to — the screen that precedes acceptance.
 *
 * Three things here are deliberate and all three are security properties of the contract
 * rather than UI preferences:
 *
 *   - **`meta: { handled: true }`.** Every failure of this endpoint is a 404 with no body
 *     (unknown token, expired, already redeemed, membership removed, account deactivated —
 *     told apart they would let a stranger probe which addresses exist on the instance). The
 *     page renders that as one non-committal message; a notification saying "Not found" in the
 *     corner would add nothing and read as a bug.
 *   - **No retry.** 404 is not transient, and the retry policy already excludes 4xx — stated
 *     here because a token is single-use and re-requesting it is never useful.
 *   - **`staleTime: Infinity`.** A refetch after the token has been redeemed would turn the
 *     success screen into "invitation not found" under the user's hands.
 */
export function useInvitationPreview(token: string): UseQueryResult<InvitationPreview> {
  return useQuery({
    queryKey: keys.invitation(token),
    queryFn: ({ signal }) => api.get<InvitationPreview>(`/auth/invitations/${encodeURIComponent(token)}`, { signal }),
    enabled: token.length > 0,
    staleTime: Infinity,
    meta: { handled: true },
  });
}

/**
 * Redeems the invitation. Answers 204 — a session is *not* issued here (see the contract):
 * the invitee signs in afterwards with the password they just chose, which keeps token
 * issuance to a single path and proves the password round-tripped.
 */
export function useAcceptInvitation(token: string): UseMutationResult<void, Error, { password?: string }> {
  return useMutation({
    mutationFn: (input: { password?: string }) =>
      api.post<void>(`/auth/invitations/${encodeURIComponent(token)}/accept`, input),
    // The form owns every outcome: a 422 lands on the password field, a 404 replaces the whole
    // panel. Neither is improved by a notification in the corner of a page with one purpose.
    meta: { handled: true },
  });
}

/**
 * What this caller may do **in this project** — the answer every write affordance is gated on.
 *
 * Four things here are decisions rather than defaults:
 *
 *   - **Keyed by project**, because the answer is. The same user is a Test Lead on one project
 *     and a Viewer on the next; a single `['permissions']` key would let the first project's
 *     answer decide the second project's buttons.
 *   - **`meta: { handled: true }`.** A failure of this call is invisible by design — the gate
 *     falls open (see `useCan`) and every action stays clickable, which is exactly how the app
 *     behaved before it existed. A toast saying "Couldn't load permissions" would report a
 *     degradation the user cannot act on and will not notice.
 *   - **`staleTime` of five minutes**, matching `useProjects`. Grants change when an admin
 *     changes them, which is rare, and every project-scoped screen reads this — a shorter
 *     window would refetch on every navigation to answer a question whose answer did not move.
 *   - **No `projectId` means no call.** `enabled` gates it, so the hook is safe to call from a
 *     component that renders before the route params resolve.
 */
export function useMyPermissions(projectId: string | undefined): UseQueryResult<EffectivePermissions> {
  return useQuery({
    queryKey: keys.permissions(projectId ?? ''),
    queryFn: ({ signal }) =>
      api.get<EffectivePermissions>(`/me/permissions${qs({ projectId })}`, { signal }),
    enabled: !!projectId,
    staleTime: 5 * 60_000,
    meta: { handled: true },
  });
}
