/** The documentation space and the artifact register. */
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { Artifact, DocPage, DocPageSummary } from '../types';
import { base, keys } from './keys';

export function useDocPages(projectId: string): UseQueryResult<DocPageSummary[]> {
  return useQuery({
    queryKey: keys.docPages(projectId),
    queryFn: ({ signal }) => api.get<DocPageSummary[]>(`${base(projectId)}/doc-pages`, { signal }),
  });
}

export function useDocPage(projectId: string, pageId: string | undefined): UseQueryResult<DocPage> {
  return useQuery({
    queryKey: keys.docPage(projectId, pageId ?? ''),
    queryFn: ({ signal }) => api.get<DocPage>(`${base(projectId)}/doc-pages/${pageId}`, { signal }),
    enabled: !!pageId,
  });
}

export function useArtifacts(
  projectId: string,
  scope?: 'PROJECT' | 'PLAN',
  planId?: string,
): UseQueryResult<Artifact[]> {
  return useQuery({
    queryKey: keys.artifacts(projectId, scope, planId),
    queryFn: ({ signal }) =>
      api.get<Artifact[]>(`${base(projectId)}/artifacts${qs({ scope, planId })}`, { signal }),
  });
}
