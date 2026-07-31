/** The suite tree and its three writes. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api } from '../client';
import type { Suite, SuiteRunSummary, VersionMarker } from '../types';
import { base, keys } from './keys';

export function useSuites(projectId: string): UseQueryResult<Suite[]> {
  return useQuery({
    queryKey: keys.suites(projectId),
    queryFn: ({ signal }) => api.get<Suite[]>(`${base(projectId)}/suites`, { signal }),
  });
}

/**
 * The three suite writes share one input shape, because the contract declares one `SuiteInput`
 * for both verbs. `parentId` means different things on each: on a create it chooses the parent,
 * and on a rename the server accepts it only when it repeats the stored one — moving is refused
 * with a field error rather than silently dropped, so the rename dialog does not send it at all.
 */
export type SuiteInput = { name: string; parentId?: string | null; description?: string | null };

export function useCreateSuite(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: SuiteInput) => api.post<Suite>(`${base(projectId)}/suites`, input),
    // The dialog renders field errors on its own inputs and the 403 in its own alert, so the
    // global notification would be a second, vaguer copy of a message already on screen.
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.suites(projectId) }),
  });
}

export function useRenameSuite(projectId: string, suiteId: string) {
  const client = useQueryClient();
  return useMutation({
    // `lockVersion` travels as `If-Match`, never in the body — the header is where the contract
    // puts it, and a body field would be a value on the input schema that must never persist.
    // The SPA sent it in the body against the mock for as long as no server read it, which is
    // exactly the divergence this call site exists not to repeat.
    mutationFn: ({ lockVersion, ...input }: SuiteInput & { lockVersion: number }) =>
      api.patch<Suite>(`${base(projectId)}/suites/${suiteId}`, input, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.suites(projectId) }),
  });
}

export function useDeleteSuite(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (suiteId: string) => api.del<void>(`${base(projectId)}/suites/${suiteId}`),
    // 409 here is "this folder still holds things", whose detail names the counts — a message
    // the dialog shows in place, beside the folder it is about. Handed to the global surface it
    // would arrive as a toast detached from the thing it describes.
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
      // The case list is scoped by suite, so a deleted folder must not stay selectable in a
      // filter chip that now matches nothing.
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
    },
  });
}

export function useSuiteHistory(
  projectId: string,
  suiteId: string | undefined,
): UseQueryResult<{ runs: SuiteRunSummary[]; versionMarkers: VersionMarker[] }> {
  return useQuery({
    queryKey: keys.suiteHistory(projectId, suiteId ?? ''),
    queryFn: ({ signal }) =>
      api.get<{ runs: SuiteRunSummary[]; versionMarkers: VersionMarker[] }>(
        `${base(projectId)}/suites/${suiteId}/history`,
        { signal },
      ),
    enabled: !!suiteId,
  });
}
