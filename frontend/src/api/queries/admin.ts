/** Instance-level reference reads — users, teams, roles and their assignments. */
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { Role, RoleAssignment, Team, User } from '../types';
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
