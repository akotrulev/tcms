/** The company's admin surface — its people, its org chart, the roles, and who holds them. */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api, qs } from '../client';
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

export function useRoleAssignments(projectId?: string): UseQueryResult<RoleAssignment[]> {
  return useQuery({
    queryKey: keys.roleAssignments(projectId),
    queryFn: ({ signal }) => api.get<RoleAssignment[]>(`/role-assignments${qs({ projectId })}`, { signal }),
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
 *   - **Every assignment key, not just the one that was filtered.** `keys.roleAssignments` takes an
 *     optional project, so `['role-assignments']` as a prefix invalidates the unfiltered list and
 *     every per-project one at once. Invalidating only the exact key leaves whichever list the user
 *     navigates to next showing the grant they just made as absent.
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
      void client.invalidateQueries({ queryKey: ['role-assignments'] });
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
      void client.invalidateQueries({ queryKey: ['role-assignments'] });
      void client.invalidateQueries({ queryKey: keys.projects });
    },
  });
}
