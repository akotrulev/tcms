/** The kanban board's work items. Instance-scoped paths, project-scoped keys. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { WorkItem } from '../types';
import { keys } from './keys';

export function useWorkItems(projectId: string, mine: boolean): UseQueryResult<WorkItem[]> {
  return useQuery({
    queryKey: keys.workItems(projectId, mine),
    queryFn: ({ signal }) =>
      api.get<WorkItem[]>(`/work-items${qs({ projectId, mine: mine ? 'true' : undefined })}`, { signal }),
  });
}

export function useMoveWorkItem(projectId: string, mine: boolean) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) =>
      api.patch<WorkItem>(`/work-items/${id}`, { status }),
    // The board says which card failed to move and where it snapped back to; that is strictly
    // more useful than "couldn't save", so the global handler stays out.
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workItems(projectId, mine) }),
  });
}

export function useCreateWorkItem(projectId: string, mine: boolean) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: Record<string, unknown>) =>
      api.post<WorkItem>('/work-items', { ...input, projectId }),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workItems(projectId, mine) }),
  });
}
