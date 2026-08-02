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
  Role,
  RoleAssignment,
  RoleAssignmentInput,
  Team,
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
    },
  });
}
