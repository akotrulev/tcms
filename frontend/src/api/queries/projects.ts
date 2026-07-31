/** The project list, one project, the two per-project vocabularies, and creating one. */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api } from '../client';
import type { CaseStatus, ClassificationDimension, Project, ProjectInput } from '../types';
import { base, keys } from './keys';

export function useProjects(): UseQueryResult<Project[]> {
  return useQuery({
    queryKey: keys.projects,
    queryFn: ({ signal }) => api.get<Project[]>('/projects', { signal }),
    // The project list changes rarely and is read on every screen via the rail.
    staleTime: 5 * 60_000,
  });
}

/**
 * Creates a project — the one mutation in the app whose permission is company-scope.
 *
 * Three things here are decisions:
 *
 *   - **`meta: { handled: true }`.** The dialog puts field errors on its own inputs (`key` is the
 *     one that fails, and it fails for a reason the user can act on) and the 403 in its own alert.
 *     A global toast would be a second, vaguer copy of a message already on screen.
 *   - **The whole project list is invalidated, not appended to.** `caseCount` and `myRole` are
 *     computed per caller by the server, so a locally-assembled row would be the client's guess at
 *     two fields it cannot compute. The list is one small request and it is already cached for
 *     five minutes.
 *   - **No `Idempotency-Key`.** Deliberate, and the opposite of `useCreateCase`: the key the user
 *     types *is* the idempotency key, because `(company, key)` is unique — a retry of a create
 *     that in fact succeeded cannot make a second project, it is refused on the field. Sending a
 *     header the server does not read is how the mock came to prove a property live mode lacked.
 */
export function useCreateProject(): UseMutationResult<Project, Error, ProjectInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ProjectInput) => api.post<Project>('/projects', input),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.projects }),
  });
}

export function useProject(projectId: string | undefined): UseQueryResult<Project> {
  return useQuery({
    queryKey: keys.project(projectId ?? ''),
    queryFn: ({ signal }) => api.get<Project>(base(projectId!), { signal }),
    enabled: !!projectId,
    staleTime: 5 * 60_000,
  });
}

export function useClassification(projectId: string): UseQueryResult<ClassificationDimension[]> {
  return useQuery({
    queryKey: keys.classification(projectId),
    queryFn: ({ signal }) => api.get<ClassificationDimension[]>(`${base(projectId)}/classification`, { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useStatuses(projectId: string): UseQueryResult<CaseStatus[]> {
  return useQuery({
    queryKey: keys.statuses(projectId),
    queryFn: ({ signal }) => api.get<CaseStatus[]>(`${base(projectId)}/statuses`, { signal }),
    staleTime: 5 * 60_000,
  });
}
