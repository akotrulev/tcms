/** The kanban board's work items. Instance-scoped paths, project-scoped keys. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { WorkItem, WorkItemInput, WorkItemStatus } from '../types';
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
    // `status` is the contract's enum rather than a bare string. Until §53 it was `string` on both
    // sides and the board compared it against English labels — see `lib/labels.ts` for the defect
    // that hid behind an unconstrained type on a field the mock and the page both invented.
    mutationFn: ({ id, status }: { id: string; status: WorkItemStatus }) =>
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
    // `Omit<…, 'projectId'>` rather than `Record<string, unknown>`: the project comes from the
    // hook's own argument, so a caller passing one would have it silently overwritten — and an
    // untyped body was how this hook sat unused for sixteen slices without anyone noticing it
    // could not have compiled against a real payload.
    mutationFn: (input: Omit<WorkItemInput, 'projectId'>) =>
      api.post<WorkItem>('/work-items', { ...input, projectId }),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workItems(projectId, mine) }),
  });
}
