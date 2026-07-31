/** Test plans and the plan statistics report. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type { PlanStatistics, TestPlan } from '../types';
import { base, keys } from './keys';

export function usePlans(projectId: string): UseQueryResult<TestPlan[]> {
  return useQuery({
    queryKey: keys.plans(projectId),
    queryFn: ({ signal }) => api.get<TestPlan[]>(`${base(projectId)}/plans`, { signal }),
  });
}

export function usePlan(projectId: string, planId: string | undefined): UseQueryResult<TestPlan> {
  return useQuery({
    queryKey: keys.plan(projectId, planId ?? ''),
    queryFn: ({ signal }) => api.get<TestPlan>(`${base(projectId)}/plans/${planId}`, { signal }),
    enabled: !!planId,
  });
}

export function usePlanStatistics(
  projectId: string,
  planId: string | undefined,
  aggregation: string,
): UseQueryResult<PlanStatistics> {
  return useQuery({
    queryKey: keys.planStats(projectId, planId ?? '', aggregation),
    queryFn: ({ signal }) =>
      api.get<PlanStatistics>(`${base(projectId)}/plans/${planId}/statistics${qs({ aggregation })}`, {
        signal,
        timeoutMs: TIMEOUTS.long,
      }),
    enabled: !!planId,
  });
}

export function useCreatePlan(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: Record<string, unknown>) => api.post<TestPlan>(`${base(projectId)}/plans`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.plans(projectId) }),
  });
}
