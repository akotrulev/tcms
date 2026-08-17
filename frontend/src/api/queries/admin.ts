/** The company's admin surface — its people, its org chart, the roles, and who holds them. */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api } from '../client';
import type {
  ApiToken,
  ApiTokenInput,
  EmailDomains,
  IdentityProvider,
  IdentityProviderInput,
  IssuedApiToken,
  OrgChart,
  Role,
  RoleAssignment,
  RoleAssignmentInput,
  Team,
  TeamInput,
  TeamMemberInput,
  User,
  UserInvitation,
  UserInvitationInput,
} from '../types';
import { keys } from './keys';

export function useUsers(): UseQueryResult<User[]> {
  return useQuery({
    queryKey: keys.users,
    queryFn: ({ signal }) => api.get<User[]>('/users', { signal }),
    staleTime: 60_000,
  });
}

export function useTeams(): UseQueryResult<Team[]> {
  return useQuery({
    queryKey: keys.teams,
    queryFn: ({ signal }) => api.get<Team[]>('/teams', { signal }),
  });
}

/**
 * The org chart — people, teams and the matrix, in one document (§9.5).
 *
 * One query rather than three, because the three axes have to come from one snapshot: a grid whose
 * rows, columns and cells were read at three different moments renders a company that never
 * existed. It is also the only shape that *can* be assembled — the matrix cells cannot be computed
 * on the client at all for the readers this screen is for, because the only company-wide grant list
 * on the wire is filtered to the grants the caller could revoke and a plain member receives `[]`.
 *
 * **Gated on nothing.** Every member of a company may read this, so there is no `enabled` term and
 * no permission check in front of it — unlike `useApiTokens` and `useIdentityProviders`, whose
 * endpoints refuse rather than narrow.
 */
export function useOrgChart(): UseQueryResult<OrgChart> {
  return useQuery({
    queryKey: keys.orgChart,
    queryFn: ({ signal }) => api.get<OrgChart>('/org-chart', { signal }),
  });
}

/**
 * Creates a team — **the org chart's first writer**, and the first time `useTeams` has had
 * anything to show on a real instance.
 *
 * `team` has had readers since the schema's first migration and no writer anywhere, so in live
 * mode `GET /teams` returned `[]` for every company that has ever existed and `GrantRoleDialog`'s
 * "grant to a team" group was permanently empty. **Mock mode has always looked correct**, because
 * the fixtures carry teams — the "mock agrees with itself" class, and the reason this was invisible
 * for the whole life of the screen.
 *
 * Only the team list is invalidated. A new team holds nothing and reaches nobody until a grant
 * names it, so neither `role-assignments` nor `projects` can have changed — the same reasoning
 * `useInviteUser` applies to an invitation, and the opposite of what `useGrantRole` must do.
 */
export function useCreateTeam(): UseMutationResult<Team, Error, TeamInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: TeamInput) => api.post<Team>('/teams', input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.teams });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/**
 * Renames, re-describes or moves a team.
 *
 * **A `PUT` carrying every editable field**, because `parentTeamId: null` — "move this to the
 * root" — is indistinguishable from an absent key once a body is deserialized. The contract
 * records the same reasoning, and it is why the classification-value editor is a PUT too.
 *
 * Still only the team list: moving a team changes the picture and not anybody's access, since
 * grants never cascade through the parent edge.
 */
export function useUpdateTeam(): UseMutationResult<
  Team,
  Error,
  { teamId: string; input: TeamInput }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ teamId, input }) =>
      api.put<Team>(`/teams/${encodeURIComponent(teamId)}`, input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.teams });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/**
 * Deletes a team.
 *
 * **The 409 is the interesting answer**, not the 204: a team holding grants is refused with the
 * count, because the row's cascade would revoke every one of them silently. The dialog renders that
 * refusal itself — hence `handled` — since "3 role grants are made through this team" is only
 * useful beside the team it names.
 *
 * `role-assignments` is invalidated on success even though a *successful* delete had no grants to
 * take with it: the list carries a team name per row, and leaving it cached would render a revoke
 * button beside a team that no longer exists.
 */
export function useDeleteTeam(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (teamId: string) => api.del<void>(`/teams/${encodeURIComponent(teamId)}`),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.teams });
      void client.invalidateQueries({ queryKey: keys.roleAssignments });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/**
 * Puts somebody in a team, or changes whether they lead it.
 *
 * **This one invalidates `projects`, and that is the difference from every other write here.** A
 * team can hold grants, so joining one is an authorization change for that person — the same
 * reasoning `useGrantRole` gives, arriving by a different route. It is easy to read this as an
 * org-chart edit and invalidate only the chart, which would leave the newly-staffed colleague's own
 * rail showing the world as it was before they joined.
 */
export function usePutTeamMember(): UseMutationResult<
  Team,
  Error,
  { teamId: string; userId: string; input: TeamMemberInput }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ teamId, userId, input }) =>
      api.put<Team>(
        `/teams/${encodeURIComponent(teamId)}/members/${encodeURIComponent(userId)}`,
        input,
      ),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.teams });
      void client.invalidateQueries({ queryKey: keys.projects });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/** Takes somebody out of a team — an authorization change too, so the same three keys. */
export function useRemoveTeamMember(): UseMutationResult<
  void,
  Error,
  { teamId: string; userId: string }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ teamId, userId }) =>
      api.del<void>(
        `/teams/${encodeURIComponent(teamId)}/members/${encodeURIComponent(userId)}`,
      ),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.teams });
      void client.invalidateQueries({ queryKey: keys.projects });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

export function useRoles(): UseQueryResult<Role[]> {
  return useQuery({
    queryKey: keys.roles,
    queryFn: ({ signal }) => api.get<Role[]>('/roles', { signal }),
    staleTime: 5 * 60_000,
  });
}

/**
 * Every grant this caller may administer, unfiltered — and the *unfiltered* is a decision (§63).
 *
 * This took an optional `projectId` until §63 and **no caller ever passed one.** That is not an
 * oversight to be corrected by finding it a consumer: the server's filter returns only the grants
 * *naming* the project, and there is **no filter for the ones naming none**. Since §A1.1 those are
 * two populations with opposite override semantics — a PROJECT-scope grant held company-wide is
 * inherited by every project, a COMPANY-scope one is never overridden — so the one screen that
 * would want the narrowed list, `ProjectAccessPage`, cannot be built from it. Pairing the two calls
 * is strictly worse: the unfiltered list is a superset, so it fetches everything anyway plus a
 * redundant round trip, and the override computed *across* two snapshots can render "replaced here"
 * against a grant a revoke has already removed.
 *
 * The parameter stays on the contract and bound by the handler, where it costs nothing and a future
 * consumer can reach it in one line. **The payload argument for narrowing is real but this is the
 * wrong lever**: measured at ~1.1 KB per row, dominated by the full `Role` — description and
 * permissions — repeated on every row for the same six roles. Sending `roleId` and letting the
 * client join against `/roles` (already fetched and cached by both consumers) cuts both screens
 * without raising the scoping question at all.
 */
export function useRoleAssignments(): UseQueryResult<RoleAssignment[]> {
  return useQuery({
    queryKey: keys.roleAssignments,
    queryFn: ({ signal }) => api.get<RoleAssignment[]>('/role-assignments', { signal }),
  });
}

/**
 * Invites a person into the company — the write that gets a colleague in at all.
 *
 * The two decisions here:
 *
 *   - **`meta: { handled: true }`.** The dialog lands field errors on its own inputs and a 403 in
 *     its own alert, so a global toast would be a vaguer second copy of a message already on the
 *     screen the user is reading.
 *   - **Only the people list is invalidated, deliberately.** An invitation is not a grant: the
 *     invitee holds nothing until somebody grants them a role, so neither `role-assignments` nor
 *     `projects` can have changed. Invalidating them would imply an authorization change that did
 *     not happen — and it is the *other* direction of the mistake `useGrantRole` records above.
 *
 * There is **no `Idempotency-Key`**, and its absence is the design rather than an omission: the
 * identity of this request is the email address, so the server's own retry path finds the
 * membership it already made. A key per mount would add nothing and would need the mounting
 * discipline §54 had to repair.
 */
export function useInviteUser(): UseMutationResult<UserInvitation, Error, UserInvitationInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: UserInvitationInput) => api.post<UserInvitation>('/users', input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.users });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/**
 * Removes a person from the company — the offboarding half of `company:members`.
 *
 * Three decisions, and the second is the one that is easy to get backwards:
 *
 *   - **`meta: { handled: true }`.** The confirmation dialog renders the refusal itself, next to
 *     the name it is about.
 *   - **The role assignments and the project list are invalidated too, unlike `useInviteUser`.**
 *     An invitation conveys nothing, so nothing else can have changed; a removal makes every grant
 *     that person holds inert, which is exactly an authorization change. `GET /role-assignments`
 *     answers the grants the caller may administer and the removed person's rows are still among
 *     them — the server keeps them, deliberately, so re-inviting restores their access.
 *   - **A 204 is the only success, and it means "they are not a member here" rather than "a row
 *     was written".** Removing somebody twice answers the same way; nothing here distinguishes it,
 *     because nothing the caller can act on differs.
 */
export function useRemoveUser(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => api.del<void>(`/users/${encodeURIComponent(userId)}`),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.users });
      void client.invalidateQueries({ queryKey: keys.roleAssignments });
      void client.invalidateQueries({ queryKey: keys.projects });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/**
 * Grants a role to a person or a team — the write that lets a colleague see a project at all.
 *
 * Four things here are decisions rather than defaults:
 *
 *   - **`meta: { handled: true }`.** The dialog renders field errors on its own inputs and the 403
 *     in its own alert. A global toast would be a second, vaguer copy of a message already on the
 *     screen the user is looking at.
 *   - **Both the assignment list and the project list are invalidated.** The second is the one
 *     that is easy to forget and the whole point of the feature: granting yourself — or being
 *     granted — a project changes what `GET /projects` returns, and a stale five-minute project
 *     cache would leave the rail showing the world as it was before the grant.
 *   - ~~**Every assignment key, not just the one that was filtered.** `keys.roleAssignments` takes
 *     an optional project, so `['role-assignments']` as a prefix invalidates the unfiltered list
 *     and every per-project one at once.~~ → **superseded in §63**: the key is no longer
 *     parameterised, because the SPA no longer asks for a filtered list (see `useRoleAssignments`).
 *     There is one entry now, so this invalidates it exactly rather than by prefix — and both
 *     screens share it, which is what makes a grant made on one visible on the other.
 *   - **A 200 is a success, not a special case.** The server answers 200 for an identical re-grant
 *     and 201 for a new one; both mean "they hold it now", which is what the caller asked for, so
 *     nothing here distinguishes them.
 */
export function useGrantRole(): UseMutationResult<RoleAssignment, Error, RoleAssignmentInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: RoleAssignmentInput) => api.post<RoleAssignment>('/role-assignments', input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.roleAssignments });
      void client.invalidateQueries({ queryKey: keys.projects });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/**
 * Revokes a grant. `204` whether the grant was there or not, so a double-click is not an error.
 *
 * The one refusal worth knowing about is a `400` on `assignmentId`: a caller may not revoke the
 * grant carrying their own ability to revoke. `PermissionGate` cannot predict that one — it is a
 * fact about the row rather than about the caller's permissions — so it is surfaced where it
 * happens, in the row's own error, rather than pretended away by disabling the button.
 */
export function useRevokeRole(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (assignmentId: string) =>
      api.del<void>(`/role-assignments/${encodeURIComponent(assignmentId)}`),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.roleAssignments });
      void client.invalidateQueries({ queryKey: keys.projects });
      void client.invalidateQueries({ queryKey: keys.orgChart });
    },
  });
}

/**
 * The company's API and CI tokens — §116's endpoint getting its first consumer.
 *
 * **`enabled` is the whole reason this takes a parameter.** The server *refuses* this list for a
 * caller without `token:issue` rather than narrowing it, deliberately: an empty array would tell
 * somebody who may simply not ask that this company has no credentials. That is the right server
 * behaviour and the wrong thing to fire blindly from a screen — an ungated read would 403 for
 * every Test Lead who opened the page and raise a global "Something didn't load" over a screen
 * that is not broken. The caller passes the gate's verdict, so the request happens only when it
 * can succeed, and the page renders the refusal itself as a sentence instead.
 *
 * `staleTime` is deliberately short. A token list is read while somebody is rotating a credential
 * in a pipeline, and a five-minute cache is how the row they just revoked still reads as live in
 * the other tab they have open.
 */
export function useApiTokens(enabled: boolean): UseQueryResult<ApiToken[]> {
  return useQuery({
    queryKey: keys.apiTokens,
    queryFn: ({ signal }) => api.get<ApiToken[]>('/api-tokens', { signal }),
    enabled,
    staleTime: 30_000,
  });
}

/**
 * Issues a token — the one mutation in this app whose **response is a credential**.
 *
 * Three decisions, and the first is the one that would be a real disclosure if it were wrong:
 *
 *   - **Nothing here writes the secret anywhere.** No `setQueryData`, no optimistic insert into
 *     `keys.apiTokens`, no logging. The plaintext lives in this mutation's own `data` — memory
 *     held by the dialog's hook instance — and dies when the dialog unmounts. Seeding it into the
 *     query cache is the obvious "avoid a refetch" optimisation and it would persist a credential
 *     into a store the whole app reads, kept alive by every other consumer of that key.
 *   - **`meta: { handled: true }`.** The dialog lands `name` / `roleId` / `expiresAt` field errors
 *     on its own inputs; a global toast would be a vaguer second copy of a message already on the
 *     screen being read.
 *   - **Only the token list is invalidated.** A token confers nothing on anybody who is signed in
 *     — it is a credential a machine presents, not a grant — so `role-assignments` and `projects`
 *     cannot have changed. This is `useInviteUser`'s reasoning, not `useGrantRole`'s.
 */
export function useIssueApiToken(): UseMutationResult<IssuedApiToken, Error, ApiTokenInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ApiTokenInput) => api.post<IssuedApiToken>('/api-tokens', input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.apiTokens });
    },
  });
}

/**
 * Revokes a token. `204` whether it was live or already revoked, so a second click is not an error
 * and the trail still reports the moment the credential stopped working rather than the moment
 * somebody clicked again.
 *
 * The row is **kept**, revoked — so this invalidates rather than removes, and the list is expected
 * to come back the same length. A revoked credential that was live in a pipeline yesterday is
 * evidence; a list that dropped the row would read as though it had never existed.
 */
export function useRevokeApiToken(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (tokenId: string) => api.del<void>(`/api-tokens/${encodeURIComponent(tokenId)}`),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.apiTokens });
    },
  });
}

/**
 * The company's identity providers (§222) — `company:idp`'s first read in this SPA.
 *
 * **`enabled` is the gate's verdict, not a convenience**, and it is `useApiTokens`' argument
 * arriving unchanged: `GET /identity-providers` *refuses* a caller without `company:idp` rather
 * than answering an empty list, so firing it ungated turns a correct 403 into "Something didn't
 * load" across a page that is working as designed.
 *
 * **Disabled providers are in this list and stay in it.** A list that hid them could not be used to
 * enable one — and registration always produces a disabled provider, so the first row an
 * administrator ever sees here would otherwise be invisible.
 */
export function useIdentityProviders(enabled: boolean): UseQueryResult<IdentityProvider[]> {
  return useQuery({
    queryKey: keys.identityProviders,
    queryFn: ({ signal }) => api.get<IdentityProvider[]>('/identity-providers', { signal }),
    enabled,
    staleTime: 30_000,
  });
}

/** The domains this company claims. Gated for `useIdentityProviders`' reason — same permission. */
export function useEmailDomains(enabled: boolean): UseQueryResult<EmailDomains> {
  return useQuery({
    queryKey: keys.emailDomains,
    queryFn: ({ signal }) => api.get<EmailDomains>('/identity-providers/email-domains', { signal }),
    enabled,
    staleTime: 30_000,
  });
}

/**
 * Registers or reconfigures a provider. One hook for both, because the screen has one form: the
 * only difference is whether an id exists, and `key`/`kind` are ignored on the edit anyway.
 *
 * **A null `clientSecret` is a keep, not a clear** — no read path can echo the secret back, so an
 * administrator renaming a provider has nothing to resend. The property is *required* by
 * `IdentityProviderInput`, so unlike a notification channel's secret the keep cannot be spelled as
 * an omission; the form sends `null` and the server leaves the stored value alone.
 */
export function useSaveIdentityProvider(): UseMutationResult<
  IdentityProvider,
  Error,
  { providerId?: string; input: IdentityProviderInput }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ providerId, input }) =>
      providerId
        ? api.put<IdentityProvider>(`/identity-providers/${encodeURIComponent(providerId)}`, input)
        : api.post<IdentityProvider>('/identity-providers', input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.identityProviders });
    },
  });
}

/**
 * Replaces the whole claimed set — the unit an administrator thinks in, and there is no lifecycle
 * on a claim to preserve.
 *
 * Only the domains are invalidated. Claiming a domain changes which addresses discovery resolves to
 * this company's providers and changes no provider, so sweeping the provider list here would refetch
 * a list that cannot have moved.
 */
export function useSaveEmailDomains(): UseMutationResult<EmailDomains, Error, string[]> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (domains: string[]) =>
      api.put<EmailDomains>('/identity-providers/email-domains', { domains }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.emailDomains });
    },
  });
}
