/** A project's repositories — reference data (015 D16), and the four writes that maintain it. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api } from '../client';
import type { ProjectRepository, ProjectRepositoryInput } from '../types';
import { base, keys } from './keys';

/**
 * The project's repositories, in position order.
 *
 * A project with none answers `[]` rather than a 404, and the screen says so in words: nothing else
 * in the project changes when the list is empty. Nothing in the product reads a repository except
 * the agent context and the settings screen, which is what "reference data" means here.
 */
export function useProjectRepositories(projectId: string): UseQueryResult<ProjectRepository[]> {
  return useQuery({
    queryKey: keys.projectRepositories(projectId),
    queryFn: ({ signal }) =>
      api.get<ProjectRepository[]>(`${base(projectId)}/repositories`, { signal }),
    enabled: projectId !== '',
  });
}

/**
 * Adds one, appended at the next position.
 *
 * The three writes below all invalidate the **whole list** rather than patching one row, and that
 * is not laziness: `primary` is computed from `position`, so removing the first repository moves
 * the badge onto the second, and a cache update that touched only the edited row would leave two
 * rows claiming it or none.
 */
export function useAddRepository(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ProjectRepositoryInput) =>
      api.post<ProjectRepository>(`${base(projectId)}/repositories`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.projectRepositories(projectId) });
    },
  });
}

/** Edits one — a full replacement of the five values, which is what the contract declares. */
export function useUpdateRepository(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: ProjectRepositoryInput }) =>
      api.patch<ProjectRepository>(
        `${base(projectId)}/repositories/${encodeURIComponent(id)}`,
        input,
        { timeoutMs: TIMEOUTS.interactive },
      ),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.projectRepositories(projectId) });
    },
  });
}

/** Removes one. Nothing references a repository row, so the delete is clean and idempotent. */
export function useDeleteRepository(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      api.del<void>(`${base(projectId)}/repositories/${encodeURIComponent(id)}`, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.projectRepositories(projectId) });
    },
  });
}
