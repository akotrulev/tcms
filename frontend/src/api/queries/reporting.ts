/** The repository status dashboard. */
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api } from '../client';
import type { RepositoryDashboard } from '../types';
import { base, keys } from './keys';

export function useDashboard(projectId: string): UseQueryResult<RepositoryDashboard> {
  return useQuery({
    queryKey: keys.dashboard(projectId),
    queryFn: ({ signal }) =>
      api.get<RepositoryDashboard>(`${base(projectId)}/dashboard`, { signal, timeoutMs: TIMEOUTS.long }),
  });
}
