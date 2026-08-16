/** Who the caller is, how they got in, and what they may do. */
import { useMutation, useQuery, useQueryClient, type UseMutationResult, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type {
  AuthResult,
  CurrentUser,
  EffectivePermissions,
  InvitationPreview,
  SocialIdentityList,
  SocialProvider,
  SocialProviderList,
  SocialRedirect,
  SsoProviderDiscovery,
} from '../types';
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

/**
 * What this caller may do **in the company**, with no project in the question.
 *
 * The same endpoint with `projectId` omitted, and a genuinely different answer: the SYSTEM and
 * COMPANY terms only, deliberately *not* the union of the caller's project grants. A Test Lead
 * holds `project:settings` on the project they run and nothing company-wide, so this is the only
 * question that can gate a company-scope affordance — today, `project:create` on the picker.
 *
 * Separate hook rather than `useMyPermissions(undefined)`, which is disabled by design: a screen
 * calling that before its route params resolve wants "no answer yet", not "ask about the company".
 * Two questions that differ only in a missing argument is how one silently gets asked for the
 * other.
 *
 * @param enabled `false` where the question is not being asked. `PermissionGate` renders on every
 *   project screen and consults one scope or the other, and a hook cannot be called conditionally —
 *   so without this gate the company question would be fired from every gated button in the app,
 *   which is a request per screen to answer something no screen there is asking.
 */
export function useMyCompanyPermissions(enabled = true): UseQueryResult<EffectivePermissions> {
  return useQuery({
    queryKey: keys.companyPermissions,
    queryFn: ({ signal }) => api.get<EffectivePermissions>('/me/permissions', { signal }),
    enabled,
    staleTime: 5 * 60_000,
    meta: { handled: true },
  });
}

/**
 * Which sign-in buttons an address reaches (§222).
 *
 * **A mutation for a read, deliberately.** The operation is a `POST` because an email address in a
 * query string lands in access logs, browser history and `Referer` headers — and this one is typed
 * by somebody who has not signed in and may never do so. Nothing about it is cacheable per key
 * either: the key would *be* the address.
 *
 * `meta: { handled: true }`: the sign-in form owns every outcome, and every outcome here is
 * silence. A notification saying single sign-on could not be checked tells an anonymous caller
 * something about this instance and helps nobody — and the endpoint answers 200 with an empty list
 * for an unclaimed domain, a disabled provider and a malformed address alike (§6r), so a failure
 * and an empty answer must be indistinguishable on screen.
 */
export function useSsoProviders(): UseMutationResult<SsoProviderDiscovery, Error, string> {
  return useMutation({
    mutationFn: (email: string) => api.post<SsoProviderDiscovery>('/auth/providers', { email }),
    meta: { handled: true },
  });
}

/**
 * Which social sign-in buttons this instance offers (V58). Instance configuration, identical
 * for every caller — so `staleTime: Infinity`, and `meta: { handled: true }` for the SSO
 * discovery's reason: on the sign-in page a failure and an empty answer must look the same,
 * and on the security page the section explains itself.
 */
export function useSocialProviders(): UseQueryResult<SocialProviderList> {
  return useQuery({
    queryKey: keys.socialProviders,
    queryFn: ({ signal }) => api.get<SocialProviderList>('/auth/social/providers', { signal }),
    staleTime: Infinity,
    meta: { handled: true },
  });
}

/** The calling account's linked social identities, for the security page. */
export function useMySocialIdentities(): UseQueryResult<SocialIdentityList> {
  return useQuery({
    queryKey: keys.mySocialIdentities,
    queryFn: ({ signal }) => api.get<SocialIdentityList>('/me/social-identities', { signal }),
  });
}

/**
 * Begins linking a social identity: the answer is a URL to NAVIGATE to (top-level), never to
 * fetch — a top-level navigation cannot carry the Authorization header, so the authenticated
 * leg is this call and the navigation that follows holds only the single-use `state`.
 */
export function useStartSocialLink(): UseMutationResult<SocialRedirect, Error, SocialProvider> {
  return useMutation({
    mutationFn: (provider: SocialProvider) =>
      api.post<SocialRedirect>(`/me/social-identities/${provider}`),
  });
}

export function useUnlinkSocialIdentity(): UseMutationResult<void, Error, SocialProvider> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (provider: SocialProvider) => api.del<void>(`/me/social-identities/${provider}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.mySocialIdentities }),
  });
}

/**
 * Signing in against a company's own directory (§223).
 *
 * A `POST` and not a navigation, which is the whole difference between the two kinds of provider:
 * an LDAP route has no third party to send a browser to, so the password is collected on the
 * sign-in screen. The answer is `AuthResult` — the same body `POST /auth/login` returns — and the
 * screen hands its token to `adoptSession`, so there is still exactly one writer of `tcms.token`.
 *
 * `meta: { handled: true }` for the local sign-in's reason: the form owns every outcome, the 401
 * is empty by design, and a corner notification would say nothing the one sentence does not.
 */
export function useDirectoryLogin(): UseMutationResult<
  AuthResult,
  Error,
  { providerId: string; email: string; password: string; rememberMe: boolean }
> {
  return useMutation({
    mutationFn: ({ providerId, ...credentials }) =>
      api.post<AuthResult>(`/auth/ldap/${encodeURIComponent(providerId)}/login`, credentials),
    meta: { handled: true },
  });
}
